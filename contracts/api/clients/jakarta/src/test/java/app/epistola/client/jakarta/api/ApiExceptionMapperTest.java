// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.StubServer;
import app.epistola.client.jakarta.error.KnownProblemSlugs;
import app.epistola.client.jakarta.error.ProblemDetailException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Exercises the mapper the way a consumer meets it: through a real MicroProfile Rest Client call
 * to a stub server, so what is asserted is the behaviour of the generated interfaces plus the
 * registered mapper — not the mapper in isolation.
 */
class ApiExceptionMapperTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Test
    void a_problem_body_becomes_a_typed_exception_with_a_switchable_slug() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(404, PROBLEM_JSON,
                        """
                        {
                          "type": "https://epistola.app/errors/not-found",
                          "title": "Not Found",
                          "status": 404,
                          "detail": "Tenant 'acme-corp' does not exist"
                        }
                        """)));

        assertEquals(KnownProblemSlugs.NOT_FOUND, e.getTypeSlug());
        assertEquals(404, e.getStatusCode());
        assertEquals(404, e.getProblemStatus());
        assertEquals("Not Found", e.getTitle());
        assertEquals("Tenant 'acme-corp' does not exist", e.getDetail());
        assertFalse(e.isValidationProblem());
        assertFalse(e.isDataModelValidationProblem());
    }

    @Test
    void the_message_carries_the_title_and_detail_rather_than_the_bare_status() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        """
                        {"type":"https://epistola.app/errors/conflict","title":"Conflict","status":409,
                         "detail":"Tenant already exists"}
                        """)));

        assertEquals("409 Conflict: Tenant already exists", e.getMessage());
    }

    @Test
    void a_validation_problem_surfaces_its_field_errors() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(400, PROBLEM_JSON,
                        """
                        {
                          "type": "https://epistola.app/errors/validation-error",
                          "title": "Validation Failed",
                          "status": 400,
                          "errors": [{"field": "id", "message": "must match pattern"}]
                        }
                        """)));

        assertEquals(KnownProblemSlugs.VALIDATION_ERROR, e.getTypeSlug());
        assertTrue(e.isValidationProblem());
        assertEquals(1, e.getErrors().size());
        assertEquals("id", e.getErrors().get(0).getField());
    }

    @Test
    void a_data_model_problem_surfaces_its_per_example_failures() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(422, PROBLEM_JSON,
                        """
                        {
                          "type": "https://epistola.app/errors/data-model-validation-error",
                          "title": "Data Model Validation Failed",
                          "status": 422,
                          "validationErrors": {"happy-path": [{"path": "/total", "message": "is required"}]}
                        }
                        """)));

        assertEquals(KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR, e.getTypeSlug());
        assertTrue(e.isDataModelValidationProblem());
        assertEquals(1, e.getValidationErrors().get("happy-path").size());
    }

    @Test
    void an_error_that_is_not_problem_json_stays_a_plain_api_exception() {
        ApiException e = assertThrows(ApiException.class, () ->
                callGetTenant(request -> StubServer.StubResponse.of(503, "text/plain", "upstream is down")));

        assertFalse(e instanceof ProblemDetailException, "a non-problem body must not be dressed up as one");
        assertNotNull(e.getResponse());
        assertEquals(503, e.getResponse().getStatus());
    }

    @Test
    void a_malformed_problem_body_falls_back_instead_of_failing_to_report_the_error() {
        ApiException e = assertThrows(ApiException.class, () ->
                callGetTenant(request -> StubServer.StubResponse.of(500, PROBLEM_JSON, "{\"broken\": ")));

        assertFalse(e instanceof ProblemDetailException);
        assertEquals(500, e.getResponse().getStatus());
    }

    @Test
    void an_empty_problem_body_falls_back_to_the_untyped_exception() {
        ApiException e = assertThrows(ApiException.class, () ->
                callGetTenant(request -> StubServer.StubResponse.of(500, PROBLEM_JSON, "")));

        assertFalse(e instanceof ProblemDetailException);
    }

    @Test
    void a_problem_with_no_epistola_type_has_no_slug_but_still_parses() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(500, PROBLEM_JSON,
                        "{\"title\":\"Internal Server Error\",\"status\":500}")));

        assertNull(e.getTypeSlug(), "about:blank carries no Epistola slug — callers need their default branch");
        assertEquals("Internal Server Error", e.getTitle());
    }

    @Test
    void the_raw_body_survives_the_response_being_closed() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(403, PROBLEM_JSON,
                        "{\"type\":\"https://epistola.app/errors/forbidden\",\"title\":\"Forbidden\",\"status\":403}")));

        assertNotNull(e.getResponseBody());
        assertTrue(e.getResponseBody().contains("forbidden"));
    }

    private static void callGetTenant(Function<StubServer.RecordedRequest, StubServer.StubResponse> responder) {
        try (StubServer stub = StubServer.start(responder)) {
            EpistolaRestClients.builder()
                    .baseUri(stub.baseUri())
                    .build()
                    .api(TenantsApi.class)
                    .getTenant("acme-corp");
        }
    }
}
