// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.epistola.client.jakarta.model.ActivationDto;
import app.epistola.client.jakarta.model.BatchGenerationItem;
import app.epistola.client.jakarta.model.CreateEnvironmentRequest;
import app.epistola.client.jakarta.model.CreateTemplateRequest;
import app.epistola.client.jakarta.model.CreateTenantRequest;
import app.epistola.client.jakarta.model.GenerateBatchRequest;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.UpdateTenantRequest;
import app.epistola.client.jakarta.model.VersionDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the constraints {@code ModelValidation} is generated from — the slug pattern, string
 * length bounds, integer ranges and array minimums — at their boundaries, on the models a consumer
 * actually builds.
 */
class ModelValidationTest {

    // --- Slug pattern ---

    @Test
    void the_slug_pattern_accepts_valid_slugs() {
        for (String slug : List.of("abc", "a1b", "test-env", "my-long-slug-123")) {
            CreateTenantRequest request = new CreateTenantRequest().id(slug).name("Test");
            assertSame(request, ModelValidation.validate(request), "expected '" + slug + "' to be valid");
        }
    }

    @Test
    void the_slug_pattern_rejects_the_shapes_the_contract_forbids() {
        Map<String, String> invalid = Map.of(
                "ABC", "uppercase",
                "1abc", "starts with a digit",
                "-abc", "leading hyphen",
                "abc-", "trailing hyphen",
                "ab--c", "consecutive hyphens",
                "ab c", "contains a space",
                "ab.c", "contains a dot");

        invalid.forEach((slug, reason) -> assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new CreateTenantRequest().id(slug).name("Test")),
                "expected '" + slug + "' to fail (" + reason + ")"));
    }

    // --- String length boundaries ---

    @Test
    void an_id_at_the_exact_bounds_passes_and_one_step_outside_fails() {
        ModelValidation.validate(new CreateTenantRequest().id("abc").name("Tenant"));
        ModelValidation.validate(new CreateTenantRequest().id("a" + "b".repeat(62)).name("Tenant"));

        assertEquals(
                "id: length must be between 3 and 63",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(new CreateTenantRequest().id("ab").name("Tenant")))
                        .getMessage());
        assertEquals(
                "id: length must be between 3 and 63",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(
                                        new CreateTenantRequest().id("a".repeat(64)).name("Tenant")))
                        .getMessage());
    }

    @Test
    void a_name_is_checked_at_both_of_its_bounds() {
        ModelValidation.validate(new CreateTenantRequest().id("abc").name("x"));
        ModelValidation.validate(new CreateTenantRequest().id("abc").name("x".repeat(255)));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new CreateTenantRequest().id("abc").name("")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new CreateTenantRequest().id("abc").name("x".repeat(256))));
    }

    @Test
    void environments_have_their_own_shorter_id_bound() {
        ModelValidation.validate(new CreateEnvironmentRequest().id("a" + "b".repeat(29)).name("Env"));

        assertEquals(
                "id: length must be between 3 and 30",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(
                                        new CreateEnvironmentRequest().id("a".repeat(31)).name("Env")))
                        .getMessage());
    }

    // --- Optional fields ---

    @Test
    void an_absent_optional_field_is_skipped_but_a_present_one_is_checked() {
        ModelValidation.validate(new UpdateTenantRequest());
        ModelValidation.validate(new UpdateTenantRequest().name("New Name"));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new UpdateTenantRequest().name("")));
    }

    // --- A required field left null ---

    @Test
    void a_missing_required_field_is_named_rather_than_causing_a_null_pointer() {
        assertEquals(
                "id: is required",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(new CreateTenantRequest().name("Tenant")))
                        .getMessage());
    }

    // --- Integer ranges ---

    @Test
    void integer_ranges_are_enforced_alongside_slug_patterns() {
        OffsetDateTime now = OffsetDateTime.now();
        ActivationDto valid = new ActivationDto()
                .environmentId("production")
                .environmentName("Production")
                .versionId(42)
                .activatedAt(now);
        assertSame(valid, ModelValidation.validate(valid));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new ActivationDto()
                        .environmentId("PRODUCTION")
                        .environmentName("Production")
                        .versionId(1)
                        .activatedAt(now)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new ActivationDto()
                        .environmentId("production")
                        .environmentName("Production")
                        .versionId(0)
                        .activatedAt(now)));
    }

    @Test
    void a_version_id_above_the_contract_maximum_is_rejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new VersionDto()
                        .id(201)
                        .variantId("english")
                        .status(VersionDto.StatusEnum.DRAFT)
                        .createdAt(OffsetDateTime.now())));
    }

    // --- Generation requests ---

    @Test
    void a_realistic_generation_request_passes() {
        GenerateDocumentRequest request = new GenerateDocumentRequest()
                .catalogId("default")
                .templateId("monthly-invoice")
                .variantId("english")
                .versionId(2)
                .filename("invoice-2026-001.pdf")
                .correlationId("order-7890")
                .data(Map.of(
                        "customer", Map.of("name", "Jane Smith", "email", "jane@example.com"),
                        "lineItems", List.of(Map.of("description", "Widget A", "quantity", 10)),
                        "invoiceNumber", "INV-2026-001"));

        assertSame(request, ModelValidation.validate(request));
    }

    @Test
    void each_optional_field_of_a_generation_request_is_checked_independently() {
        ModelValidation.validate(baseGenerationRequest().versionId(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(baseGenerationRequest().versionId(300)));

        ModelValidation.validate(baseGenerationRequest().environmentId(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(baseGenerationRequest().environmentId("PROD")));

        ModelValidation.validate(baseGenerationRequest().filename("x".repeat(255)));
        assertEquals(
                "filename: length must be at most 255",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(baseGenerationRequest().filename("x".repeat(256))))
                        .getMessage());
    }

    @Test
    void a_batch_must_carry_at_least_one_item() {
        assertEquals(
                "items: must have at least 1 item(s)",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(new GenerateBatchRequest().items(List.of())))
                        .getMessage());
    }

    @Test
    void a_populated_batch_passes() {
        List<BatchGenerationItem> items = List.of(
                new BatchGenerationItem()
                        .catalogId("default")
                        .templateId("invoice")
                        .variantId("english")
                        .data(Map.of("invoiceNumber", "INV-2026-001"))
                        .filename("invoice-001.pdf"),
                new BatchGenerationItem()
                        .catalogId("default")
                        .templateId("invoice")
                        .variantId("english")
                        .data(Map.of("invoiceNumber", "INV-2026-002")));

        GenerateBatchRequest request = new GenerateBatchRequest().items(items);
        assertSame(request, ModelValidation.validate(request));
    }

    @Test
    void a_template_with_a_nested_json_schema_passes() {
        CreateTemplateRequest request = new CreateTemplateRequest()
                .id("monthly-invoice")
                .name("Monthly Invoice")
                .schema(Map.of(
                        "type", "object",
                        "required", List.of("customerName"),
                        "properties", Map.of("customerName", Map.of("type", "string"))));

        assertSame(request, ModelValidation.validate(request));
    }

    @Test
    void validate_returns_its_argument_so_it_composes_into_a_call() {
        CreateTenantRequest request = new CreateTenantRequest().id("acme-corp").name("Acme Corporation");
        assertSame(request, ModelValidation.validate(request));
    }

    private static GenerateDocumentRequest baseGenerationRequest() {
        return new GenerateDocumentRequest()
                .catalogId("default")
                .templateId("invoice")
                .variantId("english")
                .data(Map.of());
    }
}
