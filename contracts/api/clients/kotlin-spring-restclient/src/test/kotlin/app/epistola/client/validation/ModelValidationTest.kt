// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation

import app.epistola.client.model.BatchGenerationItem
import app.epistola.client.model.CreateEnvironmentRequest
import app.epistola.client.model.CreateTemplateRequest
import app.epistola.client.model.CreateTenantRequest
import app.epistola.client.model.DocumentDto
import app.epistola.client.model.FontVariantDto
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.Node
import app.epistola.client.model.ResourceStatus
import app.epistola.client.model.Slot
import app.epistola.client.model.TemplateDocument
import app.epistola.client.model.ThemeRef
import app.epistola.client.model.UpdateTenantRequest
import app.epistola.client.model.VariantContentDto
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelValidationTest {

    // --- Slug pattern: regex edge cases ---

    @Test
    fun `slug pattern accepts valid slugs`() {
        val validSlugs = listOf("abc", "a1b", "test-env", "my-long-slug-123", "x".repeat(3))
        for (slug in validSlugs) {
            val request = CreateTenantRequest(id = slug, name = "Test")
            assertEquals(request, request.validate(), "Expected '$slug' to be valid")
        }
    }

    @Test
    fun `slug pattern rejects uppercase, leading digit, leading or trailing hyphen, consecutive hyphens, spaces`() {
        val invalid = mapOf(
            "ABC" to "uppercase",
            "1abc" to "starts with digit",
            "-abc" to "leading hyphen",
            "abc-" to "trailing hyphen",
            "ab--c" to "consecutive hyphens",
            "ab c" to "contains space",
            "ab.c" to "contains dot",
        )
        for ((slug, reason) in invalid) {
            assertFailsWith<IllegalArgumentException>("Expected '$slug' to fail ($reason)") {
                CreateTenantRequest(id = slug, name = "Test").validate()
            }
        }
    }

    // --- String length boundaries ---

    @Test
    fun `id at exact minimum length passes`() {
        val request = CreateTenantRequest(id = "abc", name = "Tenant")
        assertEquals(request, request.validate())
    }

    @Test
    fun `id one below minimum length fails`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(id = "ab", name = "Tenant").validate()
        }
        assertEquals("id: length must be between 3 and 63", ex.message)
    }

    @Test
    fun `id at exact maximum length passes`() {
        // 63 chars, valid slug: 'a' followed by 62 lowercase chars
        val id = "a" + "b".repeat(62)
        val request = CreateTenantRequest(id = id, name = "Tenant")
        assertEquals(request, request.validate())
    }

    @Test
    fun `id one above maximum length fails`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(id = "a".repeat(64), name = "Tenant").validate()
        }
        assertEquals("id: length must be between 3 and 63", ex.message)
    }

    @Test
    fun `name at boundary values`() {
        // min=1 passes
        assertEquals(
            CreateTenantRequest(id = "abc", name = "x"),
            CreateTenantRequest(id = "abc", name = "x").validate(),
        )
        // max=255 passes
        assertEquals(
            CreateTenantRequest(id = "abc", name = "x".repeat(255)),
            CreateTenantRequest(id = "abc", name = "x".repeat(255)).validate(),
        )
        // empty fails
        assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(id = "abc", name = "").validate()
        }
        // 256 fails
        assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(id = "abc", name = "x".repeat(256)).validate()
        }
    }

    // --- Nullable fields: null skips, present validates ---

    @Test
    fun `UpdateTenantRequest with null name skips validation`() {
        val request = UpdateTenantRequest(name = null)
        assertEquals(request, request.validate())
    }

    @Test
    fun `UpdateTenantRequest with valid name passes`() {
        val request = UpdateTenantRequest(name = "New Name")
        assertEquals(request, request.validate())
    }

    @Test
    fun `UpdateTenantRequest with empty name fails`() {
        assertFailsWith<IllegalArgumentException> {
            UpdateTenantRequest(name = "").validate()
        }
    }

    // --- Different maxLength per schema (environment id capped at 30) ---

    @Test
    fun `CreateEnvironmentRequest enforces id maxLength of 30`() {
        val valid = CreateEnvironmentRequest(id = "a" + "b".repeat(29), name = "Env")
        assertEquals(valid, valid.validate())

        val ex = assertFailsWith<IllegalArgumentException> {
            CreateEnvironmentRequest(id = "a".repeat(31), name = "Env").validate()
        }
        assertEquals("id: length must be between 3 and 30", ex.message)
    }

    // --- Integer range: FontVariantDto.weight is bounded 1-1000 ---

    @Test
    fun `FontVariantDto accepts a weight inside 1-1000`() {
        val valid = FontVariantDto(weight = 400, italic = false)
        assertEquals(valid, valid.validate())
    }

    @Test
    fun `FontVariantDto rejects a weight outside 1-1000`() {
        assertFailsWith<IllegalArgumentException> { FontVariantDto(weight = 0, italic = false).validate() }
        assertFailsWith<IllegalArgumentException> { FontVariantDto(weight = 1001, italic = true).validate() }
    }

    // --- VariantContentDto: slug variantId + nested TemplateDocument + status enum ---

    @Test
    fun `VariantContentDto validates its slug and nested template model together`() {
        val valid = VariantContentDto(
            variantId = "english",
            status = ResourceStatus.ready,
            lastModified = OffsetDateTime.now(),
            templateModel = TemplateDocument(
                modelVersion = TemplateDocument.ModelVersion._1,
                root = "node-root",
                nodes = mapOf(
                    "node-root" to Node(id = "node-root", type = "container", slots = listOf("slot-root")),
                    "node-header" to Node(id = "node-header", type = "text", slots = emptyList()),
                ),
                slots = mapOf(
                    "slot-root" to Slot(id = "slot-root", nodeId = "node-root", name = "children", children = listOf("node-header")),
                ),
                themeRef = ThemeRef(type = ThemeRef.Type.inherit),
            ),
        )
        assertEquals(valid, valid.validate())
    }

    @Test
    fun `VariantContentDto rejects an invalid variantId pattern`() {
        assertFailsWith<IllegalArgumentException> {
            VariantContentDto(
                variantId = "English",
                status = ResourceStatus.modified,
                lastModified = OffsetDateTime.now(),
                templateModel = TemplateDocument(
                    modelVersion = TemplateDocument.ModelVersion._1,
                    root = "node-root",
                    nodes = mapOf("node-root" to Node(id = "node-root", type = "container", slots = emptyList())),
                    slots = emptyMap(),
                    themeRef = ThemeRef(type = ThemeRef.Type.inherit),
                ),
            ).validate()
        }
    }

    // --- GenerateDocumentRequest: nested data, multiple nullable fields ---

    @Test
    fun `GenerateDocumentRequest with realistic nested data passes`() {
        val request = GenerateDocumentRequest(
            namespace = "acme-corp",
            catalogKey = "default",
            templateId = "monthly-invoice",
            variantId = "english",
            data = mapOf(
                "customer" to mapOf(
                    "name" to "Jane Smith",
                    "email" to "jane@example.com",
                    "address" to mapOf(
                        "street" to "123 Main St",
                        "city" to "Springfield",
                        "zip" to "62704",
                    ),
                ),
                "lineItems" to listOf(
                    mapOf("description" to "Widget A", "quantity" to 10, "unitPrice" to 9.99),
                    mapOf("description" to "Widget B", "quantity" to 5, "unitPrice" to 24.50),
                ),
                "invoiceNumber" to "INV-2026-001",
                "totals" to mapOf("subtotal" to 222.40, "tax" to 44.48, "total" to 266.88),
            ),
            version = "^1.4.0",
            filename = "invoice-2026-001.pdf",
            correlationId = "order-7890",
        )
        assertEquals(request, request.validate())
    }

    @Test
    fun `GenerateDocumentRequest validates each nullable field independently`() {
        // null version: skipped
        GenerateDocumentRequest(
            namespace = "acme-corp",
            catalogKey = "default",
            templateId = "invoice",
            variantId = "english",
            data = emptyMap<String, Any>(),
            version = null,
        ).validate()

        // empty version: fails
        assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                namespace = "acme-corp",
                catalogKey = "default",
                templateId = "invoice",
                variantId = "english",
                data = emptyMap<String, Any>(),
                version = "",
            ).validate()
        }

        // null environmentId: skipped
        GenerateDocumentRequest(
            namespace = "acme-corp",
            catalogKey = "default",
            templateId = "invoice",
            variantId = "english",
            data = emptyMap<String, Any>(),
            environmentId = null,
        ).validate()

        // invalid environmentId pattern: fails
        assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                namespace = "acme-corp",
                catalogKey = "default",
                templateId = "invoice",
                variantId = "english",
                data = emptyMap<String, Any>(),
                environmentId = "PROD",
            ).validate()
        }

        // filename at limit: passes
        GenerateDocumentRequest(
            namespace = "acme-corp",
            catalogKey = "default",
            templateId = "invoice",
            variantId = "english",
            data = emptyMap<String, Any>(),
            filename = "x".repeat(255),
        ).validate()

        // filename over limit: fails
        val ex = assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                namespace = "acme-corp",
                catalogKey = "default",
                templateId = "invoice",
                variantId = "english",
                data = emptyMap<String, Any>(),
                filename = "x".repeat(256),
            ).validate()
        }
        assertEquals("filename: length must be at most 255", ex.message)
    }

    @Test
    fun `GenerateDocumentRequest fails on invalid required slug field before reaching nullable fields`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                namespace = "acme-corp",
                catalogKey = "default",
                templateId = "INVALID",
                variantId = "english",
                data = emptyMap<String, Any>(),
                version = "",
            ).validate()
        }
        // templateId is validated first — fails on pattern before version is checked
        assertEquals("templateId: must match pattern ^[a-z][a-z0-9]*(-[a-z0-9]+)*\$", ex.message)
    }

    @Test
    fun `GenerateDocumentRequest names its registry in full or leaves it out, and its namespace as a slug`() {
        fun request(registry: String?, namespace: String = "acme-corp") = GenerateDocumentRequest(
            registry = registry,
            namespace = namespace,
            catalogKey = "default",
            templateId = "invoice",
            data = emptyMap<String, Any>(),
        )

        // omitted: the server's default registry
        request(registry = null).validate()
        // a registry in full
        request(registry = "exchange.example.org").validate()

        // `~` addresses the default registry in a path only; a body leaves the registry out instead
        assertFailsWith<IllegalArgumentException> { request(registry = "~").validate() }
        // a registry is a DNS name with at least one dot
        assertFailsWith<IllegalArgumentException> { request(registry = "localhost").validate() }
        // the registry is its own member; the namespace no longer carries it
        assertFailsWith<IllegalArgumentException> { request(registry = null, namespace = "exchange.example.org/team-a").validate() }
    }

    // --- BatchGenerationItem: all constraint types in one model ---

    @Test
    fun `BatchGenerationItem with all optional fields populated validates everything`() {
        val item = BatchGenerationItem(
            namespace = "acme-corp",
            catalogKey = "default",
            templateId = "invoice",
            variantId = "english",
            data = mapOf(
                "customer" to mapOf("name" to "Jane Smith"),
                "items" to listOf(mapOf("sku" to "A1", "qty" to 1)),
            ),
            version = null,
            environmentId = "staging",
            filename = "batch-doc-001.pdf",
            correlationId = "batch-run-42",
        )
        assertEquals(item, item.validate())
    }

    @Test
    fun `BatchGenerationItem rejects environmentId with invalid slug when version is null`() {
        assertFailsWith<IllegalArgumentException> {
            BatchGenerationItem(
                namespace = "acme-corp",
                catalogKey = "default",
                templateId = "invoice",
                variantId = "english",
                data = emptyMap<String, Any>(),
                version = null,
                environmentId = "Production!", // invalid slug
            ).validate()
        }
    }

    // --- GenerateBatchRequest: array minItems with realistic batch ---

    @Test
    fun `GenerateBatchRequest with multiple items passes`() {
        val items = (1..5).map { i ->
            BatchGenerationItem(
                namespace = "acme-corp",
                catalogKey = "default",
                templateId = "invoice",
                variantId = "english",
                data = mapOf(
                    "invoiceNumber" to "INV-2026-%03d".format(i),
                    "customer" to mapOf("name" to "Customer $i"),
                    "lineItems" to listOf(
                        mapOf("description" to "Service", "amount" to 100.0 * i),
                    ),
                ),
                filename = "invoice-%03d.pdf".format(i),
                correlationId = "order-$i",
            )
        }
        val request = GenerateBatchRequest(items = items)
        assertEquals(request, request.validate())
    }

    @Test
    fun `GenerateBatchRequest with empty items fails`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            GenerateBatchRequest(items = emptyList()).validate()
        }
        assertEquals("items: must have at least 1 item(s)", ex.message)
    }

    // --- DocumentDto: many slug fields + integer range in one DTO ---

    @Test
    fun `DocumentDto with realistic values passes`() {
        val doc = DocumentDto(
            id = UUID.randomUUID(),
            tenantId = "acme-corp",
            templateId = "monthly-invoice",
            variantId = "english",
            releaseVersion = "1.4.0",
            filename = "invoice-2026-001.pdf",
            contentType = "application/pdf",
            sizeBytes = 184_320L,
            createdAt = OffsetDateTime.now(),
            correlationId = "order-7890",
        )
        assertEquals(doc, doc.validate())
    }

    @Test
    fun `DocumentDto rejects any invalid slug among its multiple slug fields`() {
        val now = OffsetDateTime.now()
        val base = DocumentDto(
            id = UUID.randomUUID(),
            tenantId = "acme-corp",
            templateId = "invoice",
            variantId = "english",
            releaseVersion = "1.0.0",
            filename = "doc.pdf",
            contentType = "application/pdf",
            sizeBytes = 1024L,
            createdAt = now,
        )

        // invalid tenantId
        assertFailsWith<IllegalArgumentException> {
            base.copy(tenantId = "ACME").validate()
        }

        // invalid templateId
        assertFailsWith<IllegalArgumentException> {
            base.copy(templateId = "My Template").validate()
        }

        // invalid variantId
        assertFailsWith<IllegalArgumentException> {
            base.copy(variantId = "English!").validate()
        }

        // releaseVersion over its length limit
        assertFailsWith<IllegalArgumentException> {
            base.copy(releaseVersion = "1".repeat(51)).validate()
        }
    }

    // --- CreateTemplateRequest: slug + name + optional nested schema ---

    @Test
    fun `CreateTemplateRequest with JSON Schema passes`() {
        val request = CreateTemplateRequest(
            id = "monthly-invoice",
            name = "Monthly Invoice",
            schema = mapOf(
                "type" to "object",
                "required" to listOf("customerName", "invoiceNumber"),
                "properties" to mapOf(
                    "customerName" to mapOf("type" to "string"),
                    "invoiceNumber" to mapOf("type" to "string", "pattern" to "^INV-\\d{4}-\\d{3}$"),
                    "lineItems" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "description" to mapOf("type" to "string"),
                                "quantity" to mapOf("type" to "integer", "minimum" to 1),
                                "unitPrice" to mapOf("type" to "number"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(request, request.validate())
    }

    @Test
    fun `CreateTemplateRequest rejects invalid id even with valid schema`() {
        assertFailsWith<IllegalArgumentException> {
            CreateTemplateRequest(
                id = "Monthly Invoice",
                name = "Monthly Invoice",
                schema = mapOf("type" to "object"),
            ).validate()
        }
    }

    // --- Fluent chaining ---

    @Test
    fun `validate returns this for fluent chaining`() {
        val request = CreateTenantRequest(id = "acme-corp", name = "Acme Corporation")
        val result = request.validate()
        assert(request === result) { "validate() should return the same instance" }
    }
}
