// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.model.FontVariantDto;
import app.epistola.client.jakarta.model.BatchGenerationItem;
import app.epistola.client.jakarta.model.CreateCatalogDraftRequest;
import app.epistola.client.jakarta.model.CreateEnvironmentRequest;
import app.epistola.client.jakarta.model.CreateTemplateRequest;
import app.epistola.client.jakarta.model.CreateTenantRequest;
import app.epistola.client.jakarta.model.GenerateBatchRequest;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.PreviewDocumentRequest;
import app.epistola.client.jakarta.model.PromoteCatalogDraftRequest;
import app.epistola.client.jakarta.model.ReleaseCatalogDraftRequest;
import app.epistola.client.jakarta.model.RenameCatalogDraftRequest;
import app.epistola.client.jakarta.model.UpdateTenantRequest;
import app.epistola.client.jakarta.model.ResourceStatus;
import app.epistola.client.jakarta.model.VariantContentDto;
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
            CreateTenantRequest request = new CreateTenantRequest().key(slug).name("Test");
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
                () -> ModelValidation.validate(new CreateTenantRequest().key(slug).name("Test")),
                "expected '" + slug + "' to fail (" + reason + ")"));
    }

    // --- String length boundaries ---

    @Test
    void a_key_at_the_exact_bounds_passes_and_one_step_outside_fails() {
        ModelValidation.validate(new CreateTenantRequest().key("abc").name("Tenant"));
        ModelValidation.validate(new CreateTenantRequest().key("a" + "b".repeat(62)).name("Tenant"));

        assertEquals(
                "key: length must be between 3 and 63",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(new CreateTenantRequest().key("ab").name("Tenant")))
                        .getMessage());
        assertEquals(
                "key: length must be between 3 and 63",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(
                                        new CreateTenantRequest().key("a".repeat(64)).name("Tenant")))
                        .getMessage());
    }

    @Test
    void a_name_is_checked_at_both_of_its_bounds() {
        ModelValidation.validate(new CreateTenantRequest().key("abc").name("x"));
        ModelValidation.validate(new CreateTenantRequest().key("abc").name("x".repeat(255)));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new CreateTenantRequest().key("abc").name("")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new CreateTenantRequest().key("abc").name("x".repeat(256))));
    }

    @Test
    void environments_have_their_own_shorter_key_bound() {
        ModelValidation.validate(new CreateEnvironmentRequest().key("a" + "b".repeat(29)).name("Env"));

        assertEquals(
                "key: length must be between 3 and 30",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(
                                        new CreateEnvironmentRequest().key("a".repeat(31)).name("Env")))
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
                "key: is required",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> ModelValidation.validate(new CreateTenantRequest().name("Tenant")))
                        .getMessage());
    }

    // --- Integer ranges ---

    @Test
    void integer_ranges_are_enforced() {
        FontVariantDto valid = new FontVariantDto().weight(400).italic(false);
        assertSame(valid, ModelValidation.validate(valid));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new FontVariantDto().weight(0).italic(false)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new FontVariantDto().weight(1001).italic(true)));
    }

    @Test
    void a_key_inside_a_catalog_versions_content_is_checked() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(new VariantContentDto()
                        .variantKey("English")
                        .status(ResourceStatus.MODIFIED)
                        .lastModified(OffsetDateTime.now())));
    }

    // --- Catalog drafts and release numbers ---

    @Test
    void a_release_number_is_a_semver_number_or_pre_release() {
        // Long-term work is released as a pre-release (ADR 0002, amendment 2).
        for (String version : List.of(
                "0.1.0", "1.4.0", "10.20.30", "1.4.0-rc.1", "2.0.0-beta.1", "2.0.0-redesign-2027.1", "2.0.0-x-y.z-1")) {
            ReleaseCatalogDraftRequest request = new ReleaseCatalogDraftRequest().version(version);
            assertSame(request, ModelValidation.validate(request), "expected '" + version + "' to be valid");
        }
        // Omitted, the server numbers the release from its changes.
        ModelValidation.validate(new ReleaseCatalogDraftRequest());

        for (String version : List.of(
                "1.4", "v1.4.0", "01.4.0", "1.4.0+build", "1.4.0-rc.1+build", "1.4.0-", "1.4.0-rc..1", "1.4.0-rc_1",
                "^1.4.0", "next")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelValidation.validate(new ReleaseCatalogDraftRequest().version(version)),
                    "expected a release as '" + version + "' to fail");
        }
    }

    @Test
    void long_term_work_is_named_and_starts_from_the_next_version_or_a_release() {
        ModelValidation.validate(new CreateCatalogDraftRequest().name("Redesign 2027"));
        ModelValidation.validate(new CreateCatalogDraftRequest().name("Redesign 2027").from("1.4.0"));
        ModelValidation.validate(new CreateCatalogDraftRequest().name("Follow-up").from("2.0.0-redesign-2027.1"));
        ModelValidation.validate(new RenameCatalogDraftRequest().name("Redesign 2028"));

        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> ModelValidation.validate(
                                new CreateCatalogDraftRequest().name("Redesign 2027").from("latest")))
                .getMessage()
                .startsWith("from: must match pattern"));
        for (String name : List.of("", "x".repeat(101))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelValidation.validate(new CreateCatalogDraftRequest().name(name)),
                    "expected a draft named '" + name + "' to fail");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelValidation.validate(new RenameCatalogDraftRequest().name(name)),
                    "expected a rename to '" + name + "' to fail");
        }
    }

    @Test
    void promoting_work_may_keep_the_previous_next_version_under_a_name() {
        ModelValidation.validate(new PromoteCatalogDraftRequest());
        ModelValidation.validate(new PromoteCatalogDraftRequest()
                .keepPreviousAs("Before the redesign")
                .acknowledgedBehind(List.of("1.5.0")));

        for (String name : List.of("", "x".repeat(101))) {
            assertTrue(assertThrows(
                            IllegalArgumentException.class,
                            () -> ModelValidation.validate(new PromoteCatalogDraftRequest().keepPreviousAs(name)),
                            "expected keeping the previous next version as '" + name + "' to fail")
                    .getMessage()
                    .startsWith("keepPreviousAs: "));
        }
    }

    @Test
    void a_preview_names_a_draft_as_next_or_by_its_id_never_by_a_number() {
        for (String draft : List.of("next", "0193a6b2-8a10-7d2e-8b3c-4d5e6f708192")) {
            ModelValidation.validate(previewOf(draft));
        }
        for (String draft : List.of("1.4.0", "NEXT", "0193A6B2-8A10-7D2E-8B3C-4D5E6F708192", "draft")) {
            assertTrue(assertThrows(
                            IllegalArgumentException.class,
                            () -> ModelValidation.validate(previewOf(draft)),
                            "expected a preview of draft '" + draft + "' to fail")
                    .getMessage()
                    .startsWith("draft: "));
        }
    }

    private static PreviewDocumentRequest previewOf(String draft) {
        return new PreviewDocumentRequest()
                .namespace("acme-corp")
                .catalogKey("letters")
                .templateKey("invoice")
                .draft(draft)
                .data(Map.of());
    }

    // --- Generation requests ---

    @Test
    void a_realistic_generation_request_passes() {
        GenerateDocumentRequest request = new GenerateDocumentRequest()
                .namespace("acme-corp")
                .catalogKey("default")
                .templateKey("monthly-invoice")
                .variantKey("english")
                .version("^1.4.0")
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
        ModelValidation.validate(baseGenerationRequest().version(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(baseGenerationRequest().version("")));

        ModelValidation.validate(baseGenerationRequest().environmentKey(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelValidation.validate(baseGenerationRequest().environmentKey("PROD")));

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
                        .namespace("acme-corp")
                        .catalogKey("default")
                        .templateKey("invoice")
                        .variantKey("english")
                        .data(Map.of("invoiceNumber", "INV-2026-001"))
                        .filename("invoice-001.pdf"),
                new BatchGenerationItem()
                        .namespace("acme-corp")
                        .catalogKey("default")
                        .templateKey("invoice")
                        .variantKey("english")
                        .data(Map.of("invoiceNumber", "INV-2026-002")));

        GenerateBatchRequest request = new GenerateBatchRequest().items(items);
        assertSame(request, ModelValidation.validate(request));
    }

    @Test
    void a_template_with_a_nested_json_schema_passes() {
        CreateTemplateRequest request = new CreateTemplateRequest()
                .key("monthly-invoice")
                .name("Monthly Invoice")
                .schema(Map.of(
                        "type", "object",
                        "required", List.of("customerName"),
                        "properties", Map.of("customerName", Map.of("type", "string"))));

        assertSame(request, ModelValidation.validate(request));
    }

    @Test
    void validate_returns_its_argument_so_it_composes_into_a_call() {
        CreateTenantRequest request = new CreateTenantRequest().key("acme-corp").name("Acme Corporation");
        assertSame(request, ModelValidation.validate(request));
    }

    private static GenerateDocumentRequest baseGenerationRequest() {
        return new GenerateDocumentRequest()
                .namespace("acme-corp")
                .catalogKey("default")
                .templateKey("invoice")
                .variantKey("english")
                .data(Map.of());
    }
}
