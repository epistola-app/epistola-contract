package app.epistola.client.validation

import app.epistola.client.model.BatchGenerationItem
import app.epistola.client.model.CreateEnvironmentRequest
import app.epistola.client.model.CreateTenantRequest
import app.epistola.client.model.GenerateBatchRequest
import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.SetActivationRequest
import app.epistola.client.model.UpdateTenantRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelValidationTest {

    // --- CreateTenantRequest ---

    @Test
    fun `valid CreateTenantRequest passes validation`() {
        val request = CreateTenantRequest(id = "acme-corp", name = "Acme Corporation")
        assertEquals(request, request.validate())
    }

    @Test
    fun `CreateTenantRequest with id too short fails`() {
        val request = CreateTenantRequest(id = "ab", name = "Acme")
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("id: length must be between 3 and 63", ex.message)
    }

    @Test
    fun `CreateTenantRequest with id too long fails`() {
        val request = CreateTenantRequest(id = "a".repeat(64), name = "Acme")
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("id: length must be between 3 and 63", ex.message)
    }

    @Test
    fun `CreateTenantRequest with invalid id pattern fails`() {
        val request = CreateTenantRequest(id = "INVALID-ID", name = "Acme")
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("id: must match pattern ^[a-z][a-z0-9]*(-[a-z0-9]+)*\$", ex.message)
    }

    @Test
    fun `CreateTenantRequest with empty name fails`() {
        val request = CreateTenantRequest(id = "acme", name = "")
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("name: length must be between 1 and 255", ex.message)
    }

    @Test
    fun `CreateTenantRequest with name at max length passes`() {
        val request = CreateTenantRequest(id = "acme", name = "x".repeat(255))
        assertEquals(request, request.validate())
    }

    @Test
    fun `CreateTenantRequest with name exceeding max length fails`() {
        val request = CreateTenantRequest(id = "acme", name = "x".repeat(256))
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("name: length must be between 1 and 255", ex.message)
    }

    // --- UpdateTenantRequest (nullable field) ---

    @Test
    fun `UpdateTenantRequest with null name passes validation`() {
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
        val request = UpdateTenantRequest(name = "")
        assertFailsWith<IllegalArgumentException> { request.validate() }
    }

    // --- SetActivationRequest (integer range) ---

    @Test
    fun `SetActivationRequest with valid versionId passes`() {
        val request = SetActivationRequest(versionId = 1)
        assertEquals(request, request.validate())
    }

    @Test
    fun `SetActivationRequest with versionId at max passes`() {
        val request = SetActivationRequest(versionId = 200)
        assertEquals(request, request.validate())
    }

    @Test
    fun `SetActivationRequest with versionId too low fails`() {
        val request = SetActivationRequest(versionId = 0)
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("versionId: must be between 1 and 200", ex.message)
    }

    @Test
    fun `SetActivationRequest with versionId too high fails`() {
        val request = SetActivationRequest(versionId = 201)
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("versionId: must be between 1 and 200", ex.message)
    }

    // --- GenerateDocumentRequest (mixed required/nullable) ---

    @Test
    fun `GenerateDocumentRequest with valid required fields passes`() {
        val request = GenerateDocumentRequest(
            templateId = "invoice",
            variantId = "english",
            data = mapOf("key" to "value"),
        )
        assertEquals(request, request.validate())
    }

    @Test
    fun `GenerateDocumentRequest with nullable versionId validates when present`() {
        val request = GenerateDocumentRequest(
            templateId = "invoice",
            variantId = "english",
            data = mapOf("key" to "value"),
            versionId = 300,
        )
        assertFailsWith<IllegalArgumentException> { request.validate() }
    }

    @Test
    fun `GenerateDocumentRequest with null versionId skips validation`() {
        val request = GenerateDocumentRequest(
            templateId = "invoice",
            variantId = "english",
            data = mapOf("key" to "value"),
            versionId = null,
        )
        assertEquals(request, request.validate())
    }

    @Test
    fun `GenerateDocumentRequest with too-long filename fails`() {
        val request = GenerateDocumentRequest(
            templateId = "invoice",
            variantId = "english",
            data = mapOf("key" to "value"),
            filename = "x".repeat(256),
        )
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("filename: length must be at most 255", ex.message)
    }

    // --- GenerateBatchRequest (minItems) ---

    @Test
    fun `GenerateBatchRequest with items passes`() {
        val item = BatchGenerationItem(
            templateId = "invoice",
            variantId = "english",
            data = mapOf("key" to "value"),
        )
        val request = GenerateBatchRequest(items = listOf(item))
        assertEquals(request, request.validate())
    }

    @Test
    fun `GenerateBatchRequest with empty items fails`() {
        val request = GenerateBatchRequest(items = emptyList())
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("items: must have at least 1 item(s)", ex.message)
    }

    // --- CreateEnvironmentRequest (different maxLength) ---

    @Test
    fun `CreateEnvironmentRequest with id at max 30 chars passes`() {
        val request = CreateEnvironmentRequest(id = "a".repeat(30), name = "Env")
        // id is 30 chars of 'a' which satisfies length but will fail pattern since it's not slug
        // Let's use a proper slug at max length
        val request2 = CreateEnvironmentRequest(id = "abc" + "d".repeat(27), name = "Env")
        assertEquals(request2, request2.validate())
    }

    @Test
    fun `CreateEnvironmentRequest with id exceeding 30 chars fails`() {
        val request = CreateEnvironmentRequest(id = "a".repeat(31), name = "Env")
        val ex = assertFailsWith<IllegalArgumentException> { request.validate() }
        assertEquals("id: length must be between 3 and 30", ex.message)
    }

    // --- Slug pattern edge cases ---

    @Test
    fun `slug pattern accepts valid slugs`() {
        val validSlugs = listOf("abc", "a1b", "test-env", "my-long-slug-123")
        for (slug in validSlugs) {
            val request = CreateTenantRequest(id = slug, name = "Test")
            assertEquals(request, request.validate(), "Expected '$slug' to be valid")
        }
    }

    @Test
    fun `slug pattern rejects invalid slugs`() {
        val invalidSlugs = listOf("ABC", "1abc", "-abc", "abc-", "ab--c", "ab c")
        for (slug in invalidSlugs) {
            assertFailsWith<IllegalArgumentException>("Expected '$slug' to be invalid") {
                CreateTenantRequest(id = slug, name = "Test").validate()
            }
        }
    }
}
