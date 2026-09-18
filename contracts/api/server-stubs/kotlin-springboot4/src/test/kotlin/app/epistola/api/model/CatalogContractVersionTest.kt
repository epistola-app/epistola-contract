// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.model

import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.template.model.TemplateDocument
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards `x-epistola-catalog-contract`, the catalog versions the API declares it expects.
 *
 * Nothing generates from it, so without this test it silently falls behind the catalog: it still
 * said wire version 4 when the catalog had moved to 6. The server stubs build against the catalog
 * source, so they are the one module that sees both sides.
 */
class CatalogContractVersionTest {
    @Suppress("UNCHECKED_CAST")
    private fun declared(): Map<String, Any> {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Map<String, Any>>(it) }
        return assertNotNull(spec["x-epistola-catalog-contract"] as? Map<String, Any>, "spec has no x-epistola-catalog-contract")
    }

    @Test
    fun `the declared catalog wire version is the one the catalog emits`() {
        assertEquals(CatalogWireSchema.CURRENT_VERSION, declared()["wireSchemaVersion"])
    }

    @Test
    fun `the declared template model version is the catalog model's`() {
        val document = TemplateDocument(root = "n-root", nodes = emptyMap(), slots = emptyMap())

        assertEquals(document.modelVersion, declared()["templateModelVersion"])
    }
}
