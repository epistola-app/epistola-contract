// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.model.DataModelValidationError;
import app.epistola.client.jakarta.model.ValidationError;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemDetailParserTest {

    @Test
    void reads_the_base_problem_members() {
        var parsed = ProblemDetailParser.parse(
                """
                {
                  "type": "https://epistola.app/errors/not-found",
                  "title": "Not Found",
                  "status": 404,
                  "detail": "Template 'monthly-invoice' does not exist",
                  "instance": "/api/tenants/acme-corp/catalogs/default/templates/monthly-invoice"
                }
                """);

        assertNotNull(parsed);
        assertEquals("https://epistola.app/errors/not-found", parsed.problem().getType().toString());
        assertEquals("Not Found", parsed.problem().getTitle());
        assertEquals(404, parsed.problem().getStatus());
        assertEquals("Template 'monthly-invoice' does not exist", parsed.problem().getDetail());
        assertTrue(parsed.errors().isEmpty());
        assertTrue(parsed.validationErrors().isEmpty());
    }

    @Test
    void reads_the_errors_extension_of_a_validation_problem() {
        var parsed = ProblemDetailParser.parse(
                """
                {
                  "type": "https://epistola.app/errors/validation-error",
                  "title": "Validation Failed",
                  "status": 400,
                  "errors": [
                    {"field": "id", "message": "must match pattern", "rejectedValue": "ACME"},
                    {"field": "name", "message": "must not be blank"}
                  ]
                }
                """);

        assertNotNull(parsed);
        List<ValidationError> errors = parsed.errors();
        assertEquals(2, errors.size());
        assertEquals("id", errors.get(0).getField());
        assertEquals("must match pattern", errors.get(0).getMessage());
        assertEquals("ACME", errors.get(0).getRejectedValue());
        assertEquals("name", errors.get(1).getField());
        assertTrue(parsed.validationErrors().isEmpty());
    }

    @Test
    void reads_the_validation_errors_extension_of_a_data_model_problem() {
        var parsed = ProblemDetailParser.parse(
                """
                {
                  "type": "https://epistola.app/errors/data-model-validation-error",
                  "title": "Data Model Validation Failed",
                  "status": 422,
                  "validationErrors": {
                    "happy-path": [
                      {"path": "/customer/name", "message": "is required"},
                      {"path": "/total", "message": "must be a number"}
                    ],
                    "empty-invoice": [
                      {"path": "/lineItems", "message": "must have at least 1 item"}
                    ]
                  }
                }
                """);

        assertNotNull(parsed);
        assertEquals(2, parsed.validationErrors().size());
        List<DataModelValidationError> happyPath = parsed.validationErrors().get("happy-path");
        assertEquals(2, happyPath.size());
        assertEquals("/customer/name", happyPath.get(0).getPath());
        assertEquals("is required", happyPath.get(0).getMessage());
        assertEquals(1, parsed.validationErrors().get("empty-invoice").size());
        assertTrue(parsed.errors().isEmpty());
    }

    @Test
    void ignores_extension_members_the_contract_does_not_model() {
        var parsed = ProblemDetailParser.parse(
                """
                {
                  "type": "https://epistola.app/errors/rate-limited",
                  "title": "Too Many Requests",
                  "status": 429,
                  "retryAfterSeconds": 30,
                  "quota": {"limit": 100, "window": "1m"}
                }
                """);

        assertNotNull(parsed);
        assertEquals("Too Many Requests", parsed.problem().getTitle());
    }

    @Test
    void defaults_the_type_to_about_blank_when_absent() {
        var parsed = ProblemDetailParser.parse("{\"title\":\"Internal Server Error\",\"status\":500}");

        assertNotNull(parsed);
        assertEquals(ProblemTypes.BLANK_TYPE, parsed.problem().getType());
        assertNull(ProblemTypes.slugFor(parsed.problem().getType()));
    }

    @Test
    void returns_null_for_bodies_that_are_not_usable_problem_documents() {
        assertNull(ProblemDetailParser.parse(null));
        assertNull(ProblemDetailParser.parse(""));
        assertNull(ProblemDetailParser.parse("   "));
        assertNull(ProblemDetailParser.parse("not json at all"));
        assertNull(ProblemDetailParser.parse("{\"unterminated\": "));
        assertNull(ProblemDetailParser.parse("[{\"title\":\"an array is not a problem\"}]"));
    }

    @Test
    void recognises_the_problem_media_type_and_only_that() {
        assertTrue(ProblemDetailParser.isProblemJson(MediaType.valueOf("application/problem+json")));
        assertTrue(ProblemDetailParser.isProblemJson(MediaType.valueOf("application/problem+json;charset=UTF-8")));
        assertTrue(ProblemDetailParser.isProblemJson(MediaType.valueOf("APPLICATION/PROBLEM+JSON")));

        assertFalse(ProblemDetailParser.isProblemJson(null));
        assertFalse(ProblemDetailParser.isProblemJson(MediaType.APPLICATION_JSON_TYPE));
        assertFalse(ProblemDetailParser.isProblemJson(MediaType.valueOf("application/vnd.epistola.v1+json")));
        assertFalse(ProblemDetailParser.isProblemJson(MediaType.TEXT_PLAIN_TYPE));
    }
}
