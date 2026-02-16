package app.epistola.client.validation

import app.epistola.client.model.ActivationDto
import app.epistola.client.model.BatchGenerationItem
import app.epistola.client.model.CreateEnvironmentRequest
import app.epistola.client.model.CreateTemplateRequest
import app.epistola.client.model.CreateTenantRequest
import app.epistola.client.model.DocumentDto
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.UpdateTenantRequest
import app.epistola.client.model.VersionDto
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

    // --- Mixed constraint model: ActivationDto has slug pattern + integer range ---

    @Test
    fun `ActivationDto validates both slug pattern and integer range`() {
        val now = OffsetDateTime.now()
        val valid = ActivationDto(
            environmentId = "production",
            environmentName = "Production",
            versionId = 42,
            activatedAt = now,
        )
        assertEquals(valid, valid.validate())
    }

    @Test
    fun `ActivationDto rejects invalid environmentId pattern`() {
        val now = OffsetDateTime.now()
        assertFailsWith<IllegalArgumentException> {
            ActivationDto(
                environmentId = "PRODUCTION",
                environmentName = "Production",
                versionId = 1,
                activatedAt = now,
            ).validate()
        }
    }

    @Test
    fun `ActivationDto rejects versionId outside 1-200`() {
        val now = OffsetDateTime.now()
        assertFailsWith<IllegalArgumentException> {
            ActivationDto(
                environmentId = "production",
                environmentName = "Production",
                versionId = 0,
                activatedAt = now,
            ).validate()
        }
    }

    // --- VersionDto: integer id + slug variantId ---

    @Test
    fun `VersionDto validates integer id and slug variantId together`() {
        val now = OffsetDateTime.now()
        val valid = VersionDto(
            id = 1,
            variantId = "english",
            status = VersionDto.Status.PUBLISHED,
            createdAt = now,
            templateModel = mapOf(
                "blocks" to listOf(
                    mapOf("type" to "header", "content" to "Invoice {{invoiceNumber}}"),
                    mapOf("type" to "paragraph", "content" to "Dear {{customerName}},"),
                ),
            ),
            publishedAt = now,
        )
        assertEquals(valid, valid.validate())
    }

    @Test
    fun `VersionDto rejects id above 200`() {
        assertFailsWith<IllegalArgumentException> {
            VersionDto(
                id = 201,
                variantId = "english",
                status = VersionDto.Status.DRAFT,
                createdAt = OffsetDateTime.now(),
            ).validate()
        }
    }

    @Test
    fun `VersionDto rejects invalid variantId pattern`() {
        assertFailsWith<IllegalArgumentException> {
            VersionDto(
                id = 1,
                variantId = "English",
                status = VersionDto.Status.DRAFT,
                createdAt = OffsetDateTime.now(),
            ).validate()
        }
    }

    // --- GenerateDocumentRequest: nested data, multiple nullable fields ---

    @Test
    fun `GenerateDocumentRequest with realistic nested data passes`() {
        val request = GenerateDocumentRequest(
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
            versionId = 2,
            filename = "invoice-2026-001.pdf",
            correlationId = "order-7890",
        )
        assertEquals(request, request.validate())
    }

    @Test
    fun `GenerateDocumentRequest validates each nullable field independently`() {
        // null versionId: skipped
        GenerateDocumentRequest(
            templateId = "invoice",
            variantId = "english",
            data = emptyMap<String, Any>(),
            versionId = null,
        ).validate()

        // invalid versionId: fails
        assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                templateId = "invoice",
                variantId = "english",
                data = emptyMap<String, Any>(),
                versionId = 300,
            ).validate()
        }

        // null environmentId: skipped
        GenerateDocumentRequest(
            templateId = "invoice",
            variantId = "english",
            data = emptyMap<String, Any>(),
            environmentId = null,
        ).validate()

        // invalid environmentId pattern: fails
        assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                templateId = "invoice",
                variantId = "english",
                data = emptyMap<String, Any>(),
                environmentId = "PROD",
            ).validate()
        }

        // filename at limit: passes
        GenerateDocumentRequest(
            templateId = "invoice",
            variantId = "english",
            data = emptyMap<String, Any>(),
            filename = "x".repeat(255),
        ).validate()

        // filename over limit: fails
        val ex = assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
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
                templateId = "INVALID",
                variantId = "english",
                data = emptyMap<String, Any>(),
                versionId = 999,
            ).validate()
        }
        // templateId is validated first — fails on pattern before versionId is checked
        assertEquals("templateId: must match pattern ^[a-z][a-z0-9]*(-[a-z0-9]+)*\$", ex.message)
    }

    // --- BatchGenerationItem: all constraint types in one model ---

    @Test
    fun `BatchGenerationItem with all optional fields populated validates everything`() {
        val item = BatchGenerationItem(
            templateId = "invoice",
            variantId = "english",
            data = mapOf(
                "customer" to mapOf("name" to "Jane Smith"),
                "items" to listOf(mapOf("sku" to "A1", "qty" to 1)),
            ),
            versionId = 100,
            environmentId = "staging",
            filename = "batch-doc-001.pdf",
            correlationId = "batch-run-42",
        )
        assertEquals(item, item.validate())
    }

    @Test
    fun `BatchGenerationItem rejects environmentId with invalid slug when versionId is null`() {
        assertFailsWith<IllegalArgumentException> {
            BatchGenerationItem(
                templateId = "invoice",
                variantId = "english",
                data = emptyMap<String, Any>(),
                versionId = null,
                environmentId = "Production!", // invalid slug
            ).validate()
        }
    }

    // --- GenerateBatchRequest: array minItems with realistic batch ---

    @Test
    fun `GenerateBatchRequest with multiple items passes`() {
        val items = (1..5).map { i ->
            BatchGenerationItem(
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
            versionId = 3,
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
            versionId = 1,
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

        // versionId out of range
        assertFailsWith<IllegalArgumentException> {
            base.copy(versionId = 0).validate()
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
