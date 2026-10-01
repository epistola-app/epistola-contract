// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.FakeApis;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.InvalidDataField;
import app.epistola.client.jakarta.model.MissingDataField;
import app.epistola.client.jakarta.model.TemplateDataValidationError;
import app.epistola.client.jakarta.model.TemplateDataValidationResult;
import app.epistola.client.jakarta.model.ValidateTemplateDataRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link TemplateSchemaValidator} over the shipped default, {@link ServerTemplateDataValidator}.
 *
 * <p>The behaviour worth pinning is the mapping, not the HTTP: the server answers in three members
 * of one result and the client owes its callers a single shape, documented on
 * {@link TemplateDataValidator}.
 */
class TemplateSchemaValidatorTest {

    private final List<ValidateTemplateDataRequest> sent = new ArrayList<>();

    private TemplatesApi answering(TemplateDataValidationResult result) {
        return FakeApis.of(TemplatesApi.class, Map.of("validateTemplateData", args -> {
            sent.add((ValidateTemplateDataRequest) args[3]);
            return result;
        }));
    }

    private static TemplateDataValidationResult valid() {
        return new TemplateDataValidationResult().valid(true);
    }

    @Test
    void valid_data_passes_without_throwing() {
        new TemplateSchemaValidator(answering(valid()))
                .validate("acme-corp", "default", "invoice", Map.of("customerName", "Jane"));

        assertEquals(1, sent.size());
        assertEquals(Map.of("customerName", "Jane"), sent.get(0).getData());
    }

    @Test
    void invalid_fields_become_errors_keyed_by_their_json_pointer() {
        TemplateDataValidationResult result = new TemplateDataValidationResult()
                .valid(false)
                .invalidFields(List.of(
                        new InvalidDataField()
                                .path("/customer/email")
                                .keyword("format")
                                .message("must be a valid email address"),
                        new InvalidDataField()
                                .path("/lineItems/0/quantity")
                                .keyword("minimum")
                                .message("must be at least 1")));

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new TemplateSchemaValidator(answering(result)).validate("acme-corp", "default", "invoice", Map.of()));

        assertEquals(
                List.of("/customer/email", "/lineItems/0/quantity"),
                e.getErrors().stream().map(TemplateDataValidationException.ValidationError::getPath).toList());
        assertEquals("format", e.getErrors().get(0).getKeyword());
    }

    @Test
    void a_missing_required_field_is_an_error_and_a_missing_optional_field_is_not() {
        TemplateDataValidationResult result = new TemplateDataValidationResult()
                .valid(false)
                .missingFields(List.of(
                        new MissingDataField().path("/customer/address").required(true).schema(Map.of("type", "object")),
                        new MissingDataField().path("/customer/phone").required(false).schema(Map.of("type", "string"))));

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new TemplateSchemaValidator(answering(result)).validate("acme-corp", "default", "invoice", Map.of()));

        assertEquals(1, e.getErrors().size());
        assertEquals("/customer/address", e.getErrors().get(0).getPath());
        assertEquals("required", e.getErrors().get(0).getKeyword());
    }

    @Test
    void errors_is_used_when_the_server_sends_no_field_members() {
        TemplateDataValidationResult result = new TemplateDataValidationResult()
                .valid(false)
                .errors(List.of(new TemplateDataValidationError()
                        .path("/invoiceNumber")
                        .message("does not match the required format")
                        .keyword("pattern")));

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new TemplateSchemaValidator(answering(result)).validate("acme-corp", "default", "invoice", Map.of()));

        assertEquals("/invoiceNumber", e.getErrors().get(0).getPath());
        assertEquals("pattern", e.getErrors().get(0).getKeyword());
    }

    @Test
    void the_field_members_win_over_errors() {
        // `errors[].path` documents itself as a JSON Pointer but is specified with a JSONPath
        // example, so preferring invalidFields keeps the interface's promise from depending on
        // which member the server happened to fill.
        TemplateDataValidationResult result = new TemplateDataValidationResult()
                .valid(false)
                .errors(List.of(new TemplateDataValidationError()
                        .path("$.customer.email")
                        .message("must be a valid email address")
                        .keyword("format")))
                .invalidFields(List.of(new InvalidDataField()
                        .path("/customer/email")
                        .keyword("format")
                        .message("must be a valid email address")));

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new TemplateSchemaValidator(answering(result)).validate("acme-corp", "default", "invoice", Map.of()));

        assertEquals(1, e.getErrors().size());
        assertEquals("/customer/email", e.getErrors().get(0).getPath());
    }

    @Test
    void an_invalid_result_with_nothing_to_report_still_throws() {
        // `valid=false` is the verdict, and reporting no reason must not become "fine".
        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new TemplateSchemaValidator(answering(new TemplateDataValidationResult().valid(false)))
                        .validate("acme-corp", "default", "invoice", Map.of()));

        assertEquals(1, e.getErrors().size());
        assertEquals("", e.getErrors().get(0).getPath());
    }

    @Test
    void the_version_selectors_are_passed_through_when_configured() {
        TemplatesApi api = answering(valid());

        new TemplateSchemaValidator(new ServerTemplateDataValidator(api, "nl-nl", null, "production"))
                .validate("acme-corp", "default", "invoice", Map.of());

        assertEquals("nl-nl", sent.get(0).getVariantId());
        assertEquals("production", sent.get(0).getEnvironmentId());
    }

    @Test
    void a_plugged_in_validator_replaces_the_server_entirely() {
        List<TemplateDataValidationException.ValidationError> findings =
                List.of(new TemplateDataValidationException.ValidationError("/name", "is required", "required"));

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new TemplateSchemaValidator((tenantId, catalogId, templateId, data) -> findings)
                        .validate("acme-corp", "default", "invoice", Map.of()));

        assertEquals(findings, e.getErrors());
        assertTrue(sent.isEmpty(), "the server must not be consulted");
    }

    @Test
    void the_server_backed_validator_declines_generation_preflight() {
        assertFalse(new ServerTemplateDataValidator(answering(valid())).preflightsGeneration());
        assertTrue(((TemplateDataValidator) (t, c, tp, d) -> List.of()).preflightsGeneration());
    }
}
