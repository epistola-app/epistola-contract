package app.epistola.catalog.migration

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import tools.jackson.core.JacksonException
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.io.InputStream

object CatalogWireSchema {
    const val CURRENT_VERSION: Int = 4
    const val BASELINE_VERSION: Int = 4
}

data class CatalogMigrationFinding(
    val code: String,
    val path: String,
    val message: String,
)

data class CatalogMigrationResult<T>(
    val value: T?,
    val sourceVersion: Int?,
    val findings: List<CatalogMigrationFinding>,
) {
    val valid: Boolean get() = value != null && findings.isEmpty()
}

data class CatalogMigrationContext(
    val sourceVersion: Int,
    val manifest: CatalogManifest,
)

object CatalogMigrationCodes {
    const val SCHEMA_UNKNOWN = "CATALOG_SCHEMA_UNKNOWN"
    const val SCHEMA_TOO_NEW = "CATALOG_SCHEMA_TOO_NEW"
    const val SCHEMA_TOO_OLD = "CATALOG_SCHEMA_TOO_OLD"
    const val SCHEMA_VERSION_MISMATCH = "CATALOG_SCHEMA_VERSION_MISMATCH"
    const val RESOURCE_TYPE_MISMATCH = "CATALOG_RESOURCE_TYPE_MISMATCH"
}

/**
 * Portable catalog-wide wire-version gate.
 *
 * The current contract has no historical transform steps:
 * `BASELINE_VERSION == CURRENT_VERSION == 4`. For compatibility with the
 * existing Suite gate, sub-current payloads that already bind to the current
 * shape pass through transitionally; the first real migration will make the
 * baseline gate strict and provide an explicit transform.
 */
object CatalogSchemaMigrator {
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    fun migrateManifest(input: InputStream): CatalogMigrationResult<CatalogManifest> {
        val tree = parse(input) ?: return failure(CatalogMigrationCodes.SCHEMA_UNKNOWN, "catalog.json", "manifest is not a JSON object")
        val source = schemaVersion(tree) ?: return failure(
            CatalogMigrationCodes.SCHEMA_UNKNOWN,
            "catalog.json.schemaVersion",
            "manifest schemaVersion is missing or is not an integer",
        )
        versionFinding(source, "catalog.json.schemaVersion")?.let { return CatalogMigrationResult(null, source, listOf(it)) }
        return bind(tree, CatalogManifest::class.java, source, "catalog.json")
    }

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
        return bind(tree, ResourceDetail::class.java, source, path)
    }

    private fun parse(input: InputStream): ObjectNode? = try {
        mapper.readTree(input) as? ObjectNode
    } catch (exception: JacksonException) {
        null
    } catch (exception: IOException) {
        throw exception
    }

    private fun schemaVersion(tree: ObjectNode): Int? = tree["schemaVersion"]?.takeIf { it.isIntegralNumber }?.asInt()

    private fun versionFinding(
        source: Int,
        path: String,
    ): CatalogMigrationFinding? = when {
        source > CatalogWireSchema.CURRENT_VERSION -> CatalogMigrationFinding(
            CatalogMigrationCodes.SCHEMA_TOO_NEW,
            path,
            "catalog schemaVersion $source is newer than supported version ${CatalogWireSchema.CURRENT_VERSION}",
        )
        source < CatalogWireSchema.BASELINE_VERSION && HAS_MIGRATIONS -> CatalogMigrationFinding(
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
    ): CatalogMigrationResult<T> = try {
        CatalogMigrationResult(mapper.treeToValue(tree, type), source, emptyList())
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

    private const val HAS_MIGRATIONS = false
}
