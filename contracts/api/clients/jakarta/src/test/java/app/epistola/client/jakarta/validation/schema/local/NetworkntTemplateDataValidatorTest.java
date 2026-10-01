// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.FakeApis;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.TemplateDto;
import app.epistola.client.jakarta.validation.schema.TemplateDataValidationException.ValidationError;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The reference local adapter, held to the contract
 * {@link app.epistola.client.jakarta.validation.schema.TemplateDataValidator} states — above all
 * that {@code path} is a JSON Pointer.
 *
 * <p>This is what makes the interface's promise testable rather than aspirational: a real engine,
 * with its own idea of how to name a location, converted to the one shape callers read.
 */
class NetworkntTemplateDataValidatorTest {

    private static final Map<String, Object> INVOICE_SCHEMA = Map.of(
            "$schema", "https://json-schema.org/draft/2020-12/schema",
            "type", "object",
            "required", List.of("customer", "invoiceNumber"),
            "properties",
            Map.of(
                    "customer",
                    Map.of(
                            "type", "object",
                            "required", List.of("name", "email"),
                            "properties",
                            Map.of(
                                    "name", Map.of("type", "string", "minLength", 1),
                                    "email", Map.of("type", "string", "format", "email"))),
                    "invoiceNumber",
                    Map.of("type", "string"),
                    "lineItems",
                    Map.of(
                            "type", "array",
                            "items",
                            Map.of(
                                    "type", "object",
                                    "required", List.of("quantity"),
                                    "properties", Map.of("quantity", Map.of("type", "integer", "minimum", 1))))));

    private static final Map<String, Object> VALID_DATA = Map.of(
            "customer", Map.of("name", "Jane Smith", "email", "jane@example.com"),
            "invoiceNumber", "INV-2026-001",
            "lineItems", List.of(Map.of("quantity", 3)));

    private final AtomicInteger fetches = new AtomicInteger();

    private TemplatesApi templatesApi(Object schema) {
        return FakeApis.of(TemplatesApi.class, Map.of("getTemplate", args -> {
            fetches.incrementAndGet();
            return new TemplateDto()
                    .id((String) args[2])
                    .tenantId((String) args[0])
                    .name((String) args[2])
                    .schema(schema);
        }));
    }

    @Test
    void valid_data_yields_no_findings() {
        NetworkntTemplateDataValidator validator = new NetworkntTemplateDataValidator(templatesApi(INVOICE_SCHEMA));

        assertEquals(List.of(), validator.validate("acme-corp", "default", "invoice", VALID_DATA));
    }

    @Test
    void paths_are_json_pointers_not_the_engines_jsonpath() {
        NetworkntTemplateDataValidator validator = new NetworkntTemplateDataValidator(templatesApi(INVOICE_SCHEMA));
        Map<String, Object> data = Map.of(
                "customer", Map.of("name", "", "email", "jane@example.com"),
                "invoiceNumber", "INV-2026-001");

        List<ValidationError> findings = validator.validate("acme-corp", "default", "invoice", data);

        // The regression this guards: networknt's default renders `$.customer.name`.
        assertEquals(1, findings.size());
        assertEquals("/customer/name", findings.get(0).getPath());
        assertEquals("minLength", findings.get(0).getKeyword());
        assertTrue(findings.stream().noneMatch(f -> f.getPath().startsWith("$")));
    }

    @Test
    void a_pointer_into_an_array_uses_the_index_as_a_segment() {
        NetworkntTemplateDataValidator validator = new NetworkntTemplateDataValidator(templatesApi(INVOICE_SCHEMA));
        Map<String, Object> data = Map.of(
                "customer", Map.of("name", "Jane Smith", "email", "jane@example.com"),
                "invoiceNumber", "INV-2026-001",
                "lineItems", List.of(Map.of("quantity", 0)));

        List<ValidationError> findings = validator.validate("acme-corp", "default", "invoice", data);

        assertEquals("/lineItems/0/quantity", findings.get(0).getPath());
        assertEquals("minimum", findings.get(0).getKeyword());
    }

    @Test
    void format_is_not_asserted_which_is_why_the_server_owns_the_verdict() {
        NetworkntTemplateDataValidator validator = new NetworkntTemplateDataValidator(templatesApi(INVOICE_SCHEMA));
        Map<String, Object> data = Map.of(
                "customer", Map.of("name", "Jane Smith", "email", "not-an-email"),
                "invoiceNumber", "INV-2026-001");

        // Not a defect in the adapter: under 2020-12 `format` is an annotation, not an assertion.
        // It is recorded because it is the clearest case of a local engine reaching a different
        // answer than Epistola will, and the reason ServerTemplateDataValidator is the default.
        assertEquals(List.of(), validator.validate("acme-corp", "default", "invoice", data));
    }

    @Test
    void a_template_without_a_schema_claims_nothing_about_the_data() {
        NetworkntTemplateDataValidator validator = new NetworkntTemplateDataValidator(templatesApi(null));

        assertEquals(List.of(), validator.validate("acme-corp", "default", "invoice", Map.of("anything", 1)));
    }

    @Test
    void the_schema_is_fetched_once_and_reused() {
        NetworkntTemplateDataValidator validator = new NetworkntTemplateDataValidator(templatesApi(INVOICE_SCHEMA));

        validator.validate("acme-corp", "default", "invoice", VALID_DATA);
        validator.validate("acme-corp", "default", "invoice", VALID_DATA);

        // Without the cache a "local" validator costs a round trip per call, which is worse than
        // asking the server to validate outright.
        assertEquals(1, fetches.get());
    }
}
