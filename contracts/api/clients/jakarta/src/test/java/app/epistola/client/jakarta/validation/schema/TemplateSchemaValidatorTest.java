// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.FakeApis;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.TemplateDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TemplateSchemaValidatorTest {

    private static final Map<String, Object> INVOICE_SCHEMA = Map.of(
            "$schema", "https://json-schema.org/draft/2020-12/schema",
            "type", "object",
            "required", List.of("customerName", "total"),
            "properties",
            Map.of(
                    "customerName", Map.of("type", "string", "minLength", 1),
                    "total", Map.of("type", "number", "minimum", 0)));

    @Test
    void data_that_satisfies_the_schema_passes() {
        TemplateSchemaValidator validator = new TemplateSchemaValidator(new StubTemplatesApi(INVOICE_SCHEMA).api());

        validator.validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 42.5));
    }

    @Test
    void a_missing_required_field_is_reported_with_its_path_and_keyword() {
        TemplateSchemaValidator validator = new TemplateSchemaValidator(new StubTemplatesApi(INVOICE_SCHEMA).api());

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> validator.validate("acme-corp", "default", "invoice", Map.of("total", 42.5)));

        assertEquals(1, e.getErrors().size());
        assertEquals("required", e.getErrors().get(0).getKeyword());
        assertTrue(e.getMessage().contains("1 error"), e.getMessage());
        assertTrue(e.formatErrors().contains("customerName"), e.formatErrors());
    }

    @Test
    void every_violation_is_collected_rather_than_just_the_first() {
        TemplateSchemaValidator validator = new TemplateSchemaValidator(new StubTemplatesApi(INVOICE_SCHEMA).api());

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> validator.validate(
                        "acme-corp", "default", "invoice", Map.of("customerName", "", "total", -1)));

        assertEquals(2, e.getErrors().size(), e.formatErrors());
    }

    @Test
    void a_template_without_a_schema_validates_anything() {
        TemplateSchemaValidator validator = new TemplateSchemaValidator(new StubTemplatesApi(null).api());

        validator.validate("acme-corp", "default", "invoice", Map.of("whatever", true));
    }

    @Test
    void a_draft_07_schema_is_compiled_under_draft_07_rules() {
        Map<String, Object> draft07 = Map.of(
                "$schema", "http://json-schema.org/draft-07/schema#",
                "type", "object",
                "required", List.of("id"),
                "properties", Map.of("id", Map.of("type", "string")));
        TemplateSchemaValidator validator = new TemplateSchemaValidator(new StubTemplatesApi(draft07).api());

        validator.validate("acme-corp", "default", "invoice", Map.of("id", "x"));
        assertThrows(
                TemplateDataValidationException.class,
                () -> validator.validate("acme-corp", "default", "invoice", Map.of("id", 1)));
    }

    @Test
    void a_schema_with_no_dollar_schema_still_compiles() {
        Map<String, Object> bare = Map.of("type", "object", "required", List.of("id"));
        TemplateSchemaValidator validator = new TemplateSchemaValidator(new StubTemplatesApi(bare).api());

        assertThrows(
                TemplateDataValidationException.class,
                () -> validator.validate("acme-corp", "default", "invoice", Map.of()));
    }

    @Test
    void the_template_is_fetched_once_and_then_served_from_the_cache() {
        StubTemplatesApi api = new StubTemplatesApi(INVOICE_SCHEMA);
        TemplateSchemaValidator validator = new TemplateSchemaValidator(api.api());

        for (int i = 0; i < 5; i++) {
            validator.validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 1));
        }

        assertEquals(1, api.fetches.get(), "a schema fetch per generation call would double the round trips");
    }

    @Test
    void evicting_the_cache_forces_a_refetch() {
        StubTemplatesApi api = new StubTemplatesApi(INVOICE_SCHEMA);
        TtlSchemaCache cache = new TtlSchemaCache(Duration.ofMinutes(5));
        TemplateSchemaValidator validator = new TemplateSchemaValidator(api.api(), cache);

        validator.validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 1));
        cache.evict("acme-corp", "default", "invoice");
        validator.validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 1));

        assertEquals(2, api.fetches.get());
    }

    @Test
    void an_expired_entry_is_reloaded() throws Exception {
        StubTemplatesApi api = new StubTemplatesApi(INVOICE_SCHEMA);
        TemplateSchemaValidator validator =
                new TemplateSchemaValidator(api.api(), new TtlSchemaCache(Duration.ofMillis(50)));

        validator.validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 1));
        Thread.sleep(80);
        validator.validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 1));

        assertEquals(2, api.fetches.get());
    }

    @Test
    void the_same_template_id_in_two_catalogs_is_two_cache_entries() {
        // Two catalogs of one tenant can both hold an "invoice" template, with different schemas.
        // Keying on (tenant, template) alone would validate one against the other's contract.
        StubTemplatesApi api = new StubTemplatesApi(INVOICE_SCHEMA);
        TemplateSchemaValidator validator = new TemplateSchemaValidator(api.api());

        validator.validate("acme-corp", "catalog-a", "invoice", Map.of("customerName", "Jane", "total", 1));
        validator.validate("acme-corp", "catalog-b", "invoice", Map.of("customerName", "Jane", "total", 1));

        assertEquals(2, api.fetches.get());
    }

    @Test
    void different_templates_get_different_cache_entries() {
        StubTemplatesApi api = new StubTemplatesApi(INVOICE_SCHEMA);
        TemplateSchemaValidator validator = new TemplateSchemaValidator(api.api());

        validator.validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 1));
        validator.validate("acme-corp", "default", "receipt", Map.of("customerName", "Jane", "total", 1));
        validator.validate("other-corp", "default", "invoice", Map.of("customerName", "Jane", "total", 1));

        assertEquals(3, api.fetches.get());
    }

    @Test
    void a_null_ttl_is_rejected_rather_than_caching_forever() {
        assertThrows(IllegalArgumentException.class, () -> new TtlSchemaCache(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TtlSchemaCache(null));
    }

    /** A TemplatesApi that serves one schema and counts fetches, so caching is observable. */
    private static final class StubTemplatesApi {

        private final AtomicInteger fetches = new AtomicInteger();
        private final TemplatesApi api;

        private StubTemplatesApi(Object schema) {
            this.api = FakeApis.of(TemplatesApi.class, Map.of("getTemplate", args -> {
                fetches.incrementAndGet();
                String tenantId = (String) args[0];
                String templateId = (String) args[2];
                return new TemplateDto().id(templateId).tenantId(tenantId).name(templateId).schema(schema);
            }));
        }

        private TemplatesApi api() {
            return api;
        }
    }
}
