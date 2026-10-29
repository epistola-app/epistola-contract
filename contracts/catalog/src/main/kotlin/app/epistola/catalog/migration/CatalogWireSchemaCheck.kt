// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.migration

import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.node.ObjectNode

/**
 * Holds a document that declares the current wire version to the published schemas of that
 * version, before it binds.
 *
 * Binding alone is lenient: Jackson drops a member the model does not know, and a model default
 * fills a member the schema requires. A document that declares wire v8 but carries a misspelled
 * member, or leaves out a required one, would then pass, lose the member, and fingerprint as if it
 * were not there. The schemas are the contract, so a native document is checked against them.
 *
 * An explicit `null` counts as leaving the member out, as the model binds it, so the type error a
 * schema reports for it is not a finding. A missing required member, an unknown one, a value of
 * the wrong type, or one out of bounds is.
 *
 * Only documents that declare [CatalogWireSchema.CURRENT_VERSION] are checked. One migrated from an
 * older version keeps what its own version allowed, and the migration has already repaired or
 * reported what it could; checking it again here would refuse archives that were valid when made.
 */
internal object CatalogWireSchemaCheck {
    private const val SCHEMA_BASE = "https://epistola.app/schemas/"
    private val schemaFiles = listOf(
        "catalog-manifest-v8.schema.json",
        "resource-detail-v8.schema.json",
        "template-document-v8.schema.json",
        "template-shared-v8.schema.json",
        "theme-v8.schema.json",
    )

    private val registry: SchemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        builder.schemas(
            schemaFiles.associate { file ->
                val text = requireNotNull(CatalogWireSchemaCheck::class.java.getResource("/META-INF/epistola-catalog/schemas/$file")) {
                    "Missing bundled catalog schema $file"
                }.readText()
                SCHEMA_BASE + file to text
            },
        )
    }

    private val manifestSchema: Schema by lazy { schemaAt("catalog-manifest-v8.schema.json") }
    private val catalogResourceSchema: Schema by lazy { schemaAt("resource-detail-v8.schema.json#/\$defs/CatalogResource") }

    /** Each resource type's own definition, so a resource is judged only by the one its `type` names. */
    private val resourceSchemas: Map<String, Schema> by lazy {
        mapOf(
            "template" to "TemplateResource",
            "theme" to "ThemeResource",
            "stencil" to "StencilResource",
            "attribute" to "AttributeResource",
            "image" to "ImageResource",
            "codeList" to "CodeListResource",
            "font" to "FontResource",
        ).mapValues { (_, definition) -> schemaAt("resource-detail-v8.schema.json#/\$defs/$definition") }
    }

    private fun schemaAt(location: String): Schema = registry.getSchema(SchemaLocation.of(SCHEMA_BASE + location))

    /** Where `catalog.json` breaks the current manifest schema; [path] prefixes each finding. */
    fun manifestFindings(
        tree: ObjectNode,
        path: String,
    ): List<CatalogMigrationFinding> = findings(manifestSchema, tree, path)

    /**
     * Where a resource detail breaks the current resource-detail schema; [path] prefixes each
     * finding. The resource is checked against the definition of the type it declares rather than
     * against the union of all of them, so a template's findings are about templates, not a list of
     * every other type it is not.
     */
    fun resourceDetailFindings(
        tree: ObjectNode,
        path: String,
    ): List<CatalogMigrationFinding> {
        val envelope = tree.propertyNames().filter { it != "schemaVersion" && it != "resource" }.map { member ->
            CatalogMigrationFinding(
                CatalogMigrationCodes.SCHEMA_UNKNOWN,
                "$path.$member",
                "document declares schemaVersion ${CatalogWireSchema.CURRENT_VERSION} but breaks its schema: property '$member' is not defined in the schema and the schema does not allow additional properties",
            )
        }
        val resource = tree["resource"] as? ObjectNode ?: return envelope
        val type = resource["type"]?.takeIf { it.isString }?.asString()
        return envelope + findings(resourceSchemas[type] ?: catalogResourceSchema, resource, "$path.resource")
    }

    private fun findings(
        schema: Schema,
        tree: ObjectNode,
        path: String,
    ): List<CatalogMigrationFinding> = schema.validate(tree)
        // An explicit null counts as leaving the member out, as the model binds it, so an archive
        // written with nulls for absent members (as earlier writers did) still reads.
        .filterNot { it.keyword == "type" && it.instanceNode?.isNull == true }
        .map { error ->
            CatalogMigrationFinding(
                CatalogMigrationCodes.SCHEMA_UNKNOWN,
                location(path, error),
                "document declares schemaVersion ${CatalogWireSchema.CURRENT_VERSION} but breaks its schema: ${error.message}",
            )
        }
        .distinct()
        .sortedWith(compareBy({ it.path }, { it.message }))

    /**
     * [error]'s instance location under [base], in the archive path form findings use
     * (`resources/template/letter.json.resource.variants[0]`), naming the member itself when the
     * error is about one member of an object (an unknown or a missing one).
     */
    private fun location(
        base: String,
        error: Error,
    ): String {
        val location = StringBuilder(base)
        val instance = error.instanceLocation
        for (index in 0 until instance.nameCount) {
            when (val element = instance.getElement(index)) {
                is Int -> location.append('[').append(element).append(']')
                else -> location.append('.').append(element)
            }
        }
        val member = error.property
        if (member != null && error.keyword in MEMBER_KEYWORDS) location.append('.').append(member)
        return location.toString()
    }

    private val MEMBER_KEYWORDS = setOf("additionalProperties", "unevaluatedProperties", "required")
}
