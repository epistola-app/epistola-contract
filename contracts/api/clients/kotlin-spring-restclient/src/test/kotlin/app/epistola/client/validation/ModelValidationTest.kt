// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation

import app.epistola.client.model.BatchGenerationItem
import app.epistola.client.model.CatalogDependencyRequirement
import app.epistola.client.model.CreateEnvironmentRequest
import app.epistola.client.model.CreateTemplateRequest
import app.epistola.client.model.CreateTenantRequest
import app.epistola.client.model.DocumentDto
import app.epistola.client.model.FontVariantDto
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.Node
import app.epistola.client.model.ResourceStatus
import app.epistola.client.model.SetEnvironmentOverrideRequest
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

    // --- A referenced plain string schema (ReleaseVersion) is checked as if inline ---

    @Test
    fun `a dependency declaration pins an exact release`() {
        val pinned = CatalogDependencyRequirement(namespace = "epistola", catalogKey = "system", releaseVersion = "1.3.0")
        assertEquals(pinned, pinned.validate())
        for (release in listOf("^1.3.0", "1.x", "*", "01.3.0", "1.3.0-rc.1")) {
            assertFailsWith<IllegalArgumentException>("Expected '$release' to fail") {
                pinned.copy(releaseVersion = release).validate()
            }
        }
    }

    // --- Slug pattern: regex edge cases ---

    @Test
    fun `slug pattern accepts valid slugs`() {
        val validSlugs = listOf("abc", "a1b", "test-env", "my-long-slug-123", "x".repeat(3))
        for (slug in validSlugs) {
            val request = CreateTenantRequest(key = slug, name = "Test")
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
                CreateTenantRequest(key = slug, name = "Test").validate()
            }
        }
    }

    // --- String length boundaries ---

    @Test
    fun `key at exact minimum length passes`() {
        val request = CreateTenantRequest(key = "abc", name = "Tenant")
        assertEquals(request, request.validate())
    }

    @Test
    fun `key one below minimum length fails`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(key = "ab", name = "Tenant").validate()
        }
        assertEquals("key: length must be between 3 and 63", ex.message)
    }

    @Test
    fun `key at exact maximum length passes`() {
        // 63 chars, valid slug: 'a' followed by 62 lowercase chars
        val key = "a" + "b".repeat(62)
        val request = CreateTenantRequest(key = key, name = "Tenant")
        assertEquals(request, request.validate())
    }

    @Test
    fun `key one above maximum length fails`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(key = "a".repeat(64), name = "Tenant").validate()
        }
        assertEquals("key: length must be between 3 and 63", ex.message)
    }

    @Test
    fun `name at boundary values`() {
        // min=1 passes
        assertEquals(
            CreateTenantRequest(key = "abc", name = "x"),
            CreateTenantRequest(key = "abc", name = "x").validate(),
        )
        // max=255 passes
        assertEquals(
            CreateTenantRequest(key = "abc", name = "x".repeat(255)),
            CreateTenantRequest(key = "abc", name = "x".repeat(255)).validate(),
        )
        // empty fails
        assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(key = "abc", name = "").validate()
        }
        // 256 fails
        assertFailsWith<IllegalArgumentException> {
            CreateTenantRequest(key = "abc", name = "x".repeat(256)).validate()
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

    // --- Different maxLength per schema (environment key capped at 30) ---

    @Test
    fun `CreateEnvironmentRequest enforces key maxLength of 30`() {
        val valid = CreateEnvironmentRequest(key = "a" + "b".repeat(29), name = "Env")
        assertEquals(valid, valid.validate())

        val ex = assertFailsWith<IllegalArgumentException> {
            CreateEnvironmentRequest(key = "a".repeat(31), name = "Env").validate()
        }
        assertEquals("key: length must be between 3 and 30", ex.message)
    }

    // --- An environment override names a release or a draft (ADR 0002, amendment 2) ---

    @Test
    fun `SetEnvironmentOverrideRequest takes an exact release or a draft`() {
        SetEnvironmentOverrideRequest(releaseVersion = "1.3.0").validate()
        SetEnvironmentOverrideRequest(catalogVersion = "next").validate()
        SetEnvironmentOverrideRequest(catalogVersion = "0193a6b2-8a10-7d2e-8b3c-4d5e6f708192").validate()

        for (release in listOf("^1.3.0", "1.3", "2.0.0-beta.1", "next")) {
            val ex = assertFailsWith<IllegalArgumentException>("expected release '$release' to fail") {
                SetEnvironmentOverrideRequest(releaseVersion = release).validate()
            }
            assertEquals(true, ex.message?.startsWith("releaseVersion: "), ex.message)
        }
        for (draft in listOf("1.3.0", "Next", "draft-1")) {
            val ex = assertFailsWith<IllegalArgumentException>("expected draft '$draft' to fail") {
                SetEnvironmentOverrideRequest(catalogVersion = draft).validate()
            }
            assertEquals(true, ex.message?.startsWith("catalogVersion: "), ex.message)
        }
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

    // --- VariantContentDto: variantKey (a slug) + nested TemplateDocument + status enum ---

    @Test
    fun `VariantContentDto validates its key and nested template model together`() {
        val valid = VariantContentDto(
            variantKey = "english",
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
    fun `VariantContentDto rejects an invalid variantKey pattern`() {
        assertFailsWith<IllegalArgumentException> {
            VariantContentDto(
                variantKey = "English",
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
            templateKey = "monthly-invoice",
            variantKey = "english",
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
            templateKey = "invoice",
            variantKey = "english",
            data = emptyMap<String, Any>(),
            version = null,
        ).validate()

        // empty version: fails
        assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                namespace = "acme-corp",
                catalogKey = "default",
                templateKey = "invoice",
                variantKey = "english",
                data = emptyMap<String, Any>(),
                version = "",
            ).validate()
        }

        // null environmentKey: skipped
        GenerateDocumentRequest(
            namespace = "acme-corp",
            catalogKey = "default",
            templateKey = "invoice",
            variantKey = "english",
            data = emptyMap<String, Any>(),
            environmentKey = null,
        ).validate()

        // invalid environmentKey pattern: fails
        assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                namespace = "acme-corp",
                catalogKey = "default",
                templateKey = "invoice",
                variantKey = "english",
                data = emptyMap<String, Any>(),
                environmentKey = "PROD",
            ).validate()
        }

        // filename at limit: passes
        GenerateDocumentRequest(
            namespace = "acme-corp",
            catalogKey = "default",
            templateKey = "invoice",
            variantKey = "english",
            data = emptyMap<String, Any>(),
            filename = "x".repeat(255),
        ).validate()

        // filename over limit: fails
        val ex = assertFailsWith<IllegalArgumentException> {
            GenerateDocumentRequest(
                namespace = "acme-corp",
                catalogKey = "default",
                templateKey = "invoice",
                variantKey = "english",
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
                templateKey = "INVALID",
                variantKey = "english",
                data = emptyMap<String, Any>(),
                version = "",
            ).validate()
        }
        // templateKey is validated first — fails on pattern before version is checked
        assertEquals("templateKey: must match pattern ^[a-z0-9]+(-[a-z0-9]+)*\$", ex.message)
    }

    @Test
    fun `GenerateDocumentRequest names its registry in full or leaves it out, and its namespace as a slug`() {
        fun request(registry: String?, namespace: String = "acme-corp") = GenerateDocumentRequest(
            registry = registry,
            namespace = namespace,
            catalogKey = "default",
            templateKey = "invoice",
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
            templateKey = "invoice",
            variantKey = "english",
            data = mapOf(
                "customer" to mapOf("name" to "Jane Smith"),
                "items" to listOf(mapOf("sku" to "A1", "qty" to 1)),
            ),
            version = null,
            environmentKey = "staging",
            filename = "batch-doc-001.pdf",
            correlationId = "batch-run-42",
        )
        assertEquals(item, item.validate())
    }

    @Test
    fun `BatchGenerationItem rejects environmentKey with invalid slug when version is null`() {
        assertFailsWith<IllegalArgumentException> {
            BatchGenerationItem(
                namespace = "acme-corp",
                catalogKey = "default",
                templateKey = "invoice",
                variantKey = "english",
                data = emptyMap<String, Any>(),
                version = null,
                environmentKey = "Production!", // invalid slug
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
                templateKey = "invoice",
                variantKey = "english",
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
            tenantKey = "acme-corp",
            templateKey = "monthly-invoice",
            variantKey = "english",
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
            tenantKey = "acme-corp",
            templateKey = "invoice",
            variantKey = "english",
            releaseVersion = "1.0.0",
            filename = "doc.pdf",
            contentType = "application/pdf",
            sizeBytes = 1024L,
            createdAt = now,
        )

        // invalid tenantKey
        assertFailsWith<IllegalArgumentException> {
            base.copy(tenantKey = "ACME").validate()
        }

        // invalid templateKey
        assertFailsWith<IllegalArgumentException> {
            base.copy(templateKey = "My Template").validate()
        }

        // invalid variantKey
        assertFailsWith<IllegalArgumentException> {
            base.copy(variantKey = "English!").validate()
        }

        // releaseVersion over its length limit
        assertFailsWith<IllegalArgumentException> {
            base.copy(releaseVersion = "1".repeat(51)).validate()
        }
    }

    // --- CreateTemplateRequest: key + name + optional nested schema ---

    @Test
    fun `CreateTemplateRequest with JSON Schema passes`() {
        val request = CreateTemplateRequest(
            key = "monthly-invoice",
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
    fun `CreateTemplateRequest rejects invalid key even with valid schema`() {
        assertFailsWith<IllegalArgumentException> {
            CreateTemplateRequest(
                key = "Monthly Invoice",
                name = "Monthly Invoice",
                schema = mapOf("type" to "object"),
            ).validate()
        }
    }

    // --- Fluent chaining ---

    @Test
    fun `validate returns this for fluent chaining`() {
        val request = CreateTenantRequest(key = "acme-corp", name = "Acme Corporation")
        val result = request.validate()
        assert(request === result) { "validate() should return the same instance" }
    }
}
