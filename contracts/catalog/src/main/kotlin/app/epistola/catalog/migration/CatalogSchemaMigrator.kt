// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import tools.jackson.core.JacksonException
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.io.InputStream

/**
 * Supported portable catalog wire-version interval.
 *
 * A version is accepted only when an explicit migration path exists from
 * [BASELINE_VERSION] through [CURRENT_VERSION].
 */
object CatalogWireSchema {
    const val CURRENT_VERSION: Int = 6
    const val BASELINE_VERSION: Int = 4
}

/** Stable diagnostic explaining why a wire document could not be migrated. */
data class CatalogMigrationFinding(
    val code: String,
    val path: String,
    val message: String,
)

/** Non-fatal diagnostic describing a successful migration conversion. */
data class CatalogMigrationNotice(
    val code: String,
    val path: String,
    val message: String,
)

/**
 * Outcome of parsing, version-gating, migrating, and binding one JSON document.
 *
 * Ordinary malformed or unsupported input produces findings and a null
 * [value]. Unrecoverable I/O remains exceptional.
 */
data class CatalogMigrationResult<T>(
    val value: T?,
    val sourceVersion: Int?,
    val findings: List<CatalogMigrationFinding>,
    val notices: List<CatalogMigrationNotice> = emptyList(),
) {
    /** True when a value was bound without migration findings. */
    val valid: Boolean get() = value != null && findings.isEmpty()
}

/**
 * Manifest state required to migrate and bind a resource detail consistently.
 */
data class CatalogMigrationContext(
    val sourceVersion: Int,
    val manifest: CatalogManifest,
)

/** Stable finding codes emitted by [CatalogSchemaMigrator]. */
object CatalogMigrationCodes {
    const val SCHEMA_UNKNOWN = "CATALOG_SCHEMA_UNKNOWN"
    const val SCHEMA_TOO_NEW = "CATALOG_SCHEMA_TOO_NEW"
    const val SCHEMA_TOO_OLD = "CATALOG_SCHEMA_TOO_OLD"
    const val SCHEMA_VERSION_MISMATCH = "CATALOG_SCHEMA_VERSION_MISMATCH"
    const val RESOURCE_TYPE_MISMATCH = "CATALOG_RESOURCE_TYPE_MISMATCH"
    const val DRAFT_MARKER_INVALID = "CATALOG_DRAFT_MARKER_INVALID"
    const val STALE_DRAFT_MARKER_REMOVED = "CATALOG_STALE_DRAFT_MARKER_REMOVED"
    const val DEFAULT_LANGUAGE_ADDED = "CATALOG_DEFAULT_LANGUAGE_ADDED"
    const val KEYWORD_INVALID = "CATALOG_KEYWORD_INVALID"
    const val KEYWORD_DUPLICATE = "CATALOG_KEYWORD_DUPLICATE"
}

/**
 * Portable catalog-wide wire-version gate.
 *
 * Catalog v4 is the migration baseline and v6 is the only emitted shape.
 */
object CatalogSchemaMigrator {
    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val migrations: List<CatalogSchemaMigration> = listOf(
        CatalogV4ToV5Migration(),
        CatalogV5ToV6Migration(),
    )

    /**
     * Parses and migrates `catalog.json` to the current [CatalogManifest].
     *
     * [input] is consumed but not closed by this method.
     *
     * @throws IOException when the stream cannot be read.
     */
    fun migrateManifest(input: InputStream): CatalogMigrationResult<CatalogManifest> {
        val tree = parse(input) ?: return failure(CatalogMigrationCodes.SCHEMA_UNKNOWN, "catalog.json", "manifest is not a JSON object")
        val source = schemaVersion(tree) ?: return failure(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            "catalog.json.schemaVersion",
            "manifest schemaVersion is missing or is not an integer",
        )
        versionFinding(source, "catalog.json.schemaVersion")?.let { return CatalogMigrationResult(null, source, listOf(it)) }
        val migration = migrate(source) { step -> step.migrateManifest(tree) }
        if (migration.findings.isNotEmpty()) return CatalogMigrationResult(null, source, migration.findings, migration.notices)
        val wireFindings = validateManifestWireValues(tree)
        if (wireFindings.isNotEmpty()) return CatalogMigrationResult(null, source, wireFindings, migration.notices)
        return bind(tree, CatalogManifest::class.java, source, "catalog.json", migration.notices)
    }

    /**
     * Parses and migrates one resource detail using its owning manifest.
     *
     * The document's schema version must match [context], and its resource
     * discriminator must match [declaredType]. [path] is copied into findings
     * so callers retain deterministic archive locations.
     *
     * @throws IllegalArgumentException when [declaredType] is blank.
     * @throws IOException when the stream cannot be read.
     */
    fun migrateResourceDetail(
        declaredType: String,
        input: InputStream,
        context: CatalogMigrationContext,
        path: String,
    ): CatalogMigrationResult<ResourceDetail> {
        require(declaredType.isNotBlank())
        val tree = parse(input) ?: return failure(CatalogMigrationCodes.SCHEMA_UNKNOWN, path, "resource detail is not a JSON object")
        val source = schemaVersion(tree) ?: return failure(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            "$path.schemaVersion",
            "resource detail schemaVersion is missing or is not an integer",
        )
        if (source != context.sourceVersion) {
            return failure(
                CatalogMigrationCodes.SCHEMA_VERSION_MISMATCH,
                "$path.schemaVersion",
                "resource detail is at schemaVersion $source but the catalog manifest is at ${context.sourceVersion}",
                source,
            )
        }
        val actualType = tree["resource"]?.get("type")?.takeIf { it.isString }?.asString()
        if (actualType != null && actualType != declaredType) {
            return failure(
                CatalogMigrationCodes.RESOURCE_TYPE_MISMATCH,
                "$path.resource.type",
                "resource detail declares type '$actualType' but the manifest entry is '$declaredType'",
                source,
            )
        }
        versionFinding(source, "$path.schemaVersion")?.let { return CatalogMigrationResult(null, source, listOf(it)) }
        val migration = migrate(source) { step -> step.migrateResource(tree, path) }
        if (migration.findings.isNotEmpty()) return CatalogMigrationResult(null, source, migration.findings, migration.notices)
        return bind(tree, ResourceDetail::class.java, source, path, migration.notices)
    }

    private fun parse(input: InputStream): ObjectNode? = try {
        mapper.readTree(input) as? ObjectNode
    } catch (exception: JacksonException) {
        null
    } catch (exception: IOException) {
        throw exception
    }

    private fun schemaVersion(tree: ObjectNode): Int? = tree["schemaVersion"]?.takeIf { it.isIntegralNumber }?.asInt()

    private fun validateManifestWireValues(tree: ObjectNode): List<CatalogMigrationFinding> {
        val keywords = tree["catalog"]?.get("keywords")?.takeIf { it.isArray } ?: return emptyList()
        val findings = mutableListOf<CatalogMigrationFinding>()
        val seen = mutableSetOf<String>()
        keywords.forEachIndexed { index, node ->
            if (!node.isString) return@forEachIndexed
            val keyword = node.asString()
            val path = "catalog.json.catalog.keywords[$index]"
            if (keyword.isBlank() || keyword != keyword.trim()) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.KEYWORD_INVALID,
                    path,
                    "keyword must be nonblank and must not contain leading or trailing whitespace",
                )
            }
            if (!seen.add(keyword)) {
                findings += CatalogMigrationFinding(
                    CatalogMigrationCodes.KEYWORD_DUPLICATE,
                    path,
                    "keyword '$keyword' is duplicated",
                )
            }
        }
        return findings
    }

    private fun versionFinding(
        source: Int,
        path: String,
    ): CatalogMigrationFinding? = when {
        source > CatalogWireSchema.CURRENT_VERSION -> CatalogMigrationFinding(
            CatalogMigrationCodes.SCHEMA_TOO_NEW,
            path,
            "catalog schemaVersion $source is newer than supported version ${CatalogWireSchema.CURRENT_VERSION}",
        )
        source < CatalogWireSchema.BASELINE_VERSION -> CatalogMigrationFinding(
            CatalogMigrationCodes.SCHEMA_TOO_OLD,
            path,
            "catalog schemaVersion $source is older than baseline ${CatalogWireSchema.BASELINE_VERSION}",
        )
        else -> null
    }

    private fun <T : Any> bind(
        tree: ObjectNode,
        type: Class<T>,
        source: Int,
        path: String,
        notices: List<CatalogMigrationNotice> = emptyList(),
    ): CatalogMigrationResult<T> = try {
        CatalogMigrationResult(mapper.treeToValue(tree, type), source, emptyList(), notices)
    } catch (exception: JacksonException) {
        failure(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            path,
            "document has a valid schemaVersion but does not bind to the current contract: ${exception.originalMessage}",
            source,
        )
    }

    private fun <T> failure(
        code: String,
        path: String,
        message: String,
        sourceVersion: Int? = null,
    ): CatalogMigrationResult<T> = CatalogMigrationResult(
        value = null,
        sourceVersion = sourceVersion,
        findings = listOf(CatalogMigrationFinding(code, path, message)),
    )

    private fun migrate(
        sourceVersion: Int,
        apply: (CatalogSchemaMigration) -> CatalogMigrationStepResult,
    ): CatalogMigrationStepResult {
        val findings = mutableListOf<CatalogMigrationFinding>()
        val notices = mutableListOf<CatalogMigrationNotice>()
        var version = sourceVersion
        while (version < CatalogWireSchema.CURRENT_VERSION) {
            val migration = requireNotNull(migrations.singleOrNull { it.fromVersion == version }) {
                "missing catalog migration from schemaVersion $version"
            }
            val result = apply(migration)
            findings += result.findings
            notices += result.notices
            if (result.findings.isNotEmpty()) break
            version = migration.toVersion
        }
        return CatalogMigrationStepResult(findings, notices)
    }
}
