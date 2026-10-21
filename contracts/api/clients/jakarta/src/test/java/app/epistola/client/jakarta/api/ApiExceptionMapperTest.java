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
import app.epistola.client.jakarta.error.MissingDependency;
import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.error.UnreleasedDependency;
import java.util.List;
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
    void a_catalog_source_problem_is_typed_whether_it_comes_as_a_503_or_a_502() {
        for (int status : List.of(503, 502)) {
            ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                    StubServer.StubResponse.of(status, PROBLEM_JSON,
                            """
                            {"type":"https://epistola.app/errors/catalog-source-unavailable",
                             "title":"Catalog Source Unavailable","status":%d,
                             "detail":"registry.gemeente-x.nl could not be used"}
                            """.formatted(status))));

            assertEquals(KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE, e.getTypeSlug());
            assertEquals(status, e.getStatusCode());
            assertEquals(status, e.getProblemStatus());
        }
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

    @Test
    void a_template_data_problem_carries_its_field_pointers() {
        // The members contract 1.4.0 added. Read over a real call rather than through the parser
        // directly, because the mapper is what a consumer actually meets.
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(400, PROBLEM_JSON,
                        """
                        {
                          "type": "https://epistola.app/errors/template-data-invalid",
                          "title": "Template data invalid",
                          "status": 400,
                          "detail": "The supplied data does not fit the template's data contract",
                          "errors": [],
                          "missingFields": [
                            {"path": "/customer/address", "required": true, "schema": {"type": "object"}},
                            {"path": "/customer/phone", "required": false, "schema": {"type": "string"}}
                          ],
                          "invalidFields": [
                            {"path": "/customer/age", "keyword": "type",
                             "message": "string found, integer expected", "schema": {"type": "integer"}}
                          ]
                        }
                        """)));

        assertEquals(KnownProblemSlugs.TEMPLATE_DATA_INVALID, e.getTypeSlug());
        assertTrue(e.isTemplateDataProblem());
        assertEquals(2, e.getMissingFields().size());
        assertEquals("/customer/address", e.getMissingFields().get(0).getPath());
        assertTrue(e.getMissingFields().get(0).getRequired());
        assertFalse(e.getMissingFields().get(1).getRequired());
        assertEquals(1, e.getInvalidFields().size());
        assertEquals("/customer/age", e.getInvalidFields().get(0).getPath());
        assertEquals("type", e.getInvalidFields().get(0).getKeyword());
    }

    @Test
    void another_problem_reports_no_template_data_fields() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(404, PROBLEM_JSON,
                        "{\"type\":\"https://epistola.app/errors/not-found\",\"title\":\"Not Found\",\"status\":404}")));

        assertFalse(e.isTemplateDataProblem());
        assertTrue(e.getMissingFields().isEmpty());
        assertTrue(e.getInvalidFields().isEmpty());
    }

    @Test
    void a_release_dependencies_problem_lists_what_is_missing() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        """
                        {
                          "type": "https://epistola.app/errors/release-dependencies-missing",
                          "title": "Release dependencies missing",
                          "status": 409,
                          "missingDependencies": [
                            {"registry": "exchange.epistola.app", "namespace": "acme-corp",
                             "catalogKey": "shared", "version": "1.2.0", "reason": "unavailable"}
                          ]
                        }
                        """)));

        assertEquals(KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING, e.getTypeSlug());
        assertEquals(409, e.getStatusCode());
        assertTrue(e.isReleaseDependenciesMissingProblem());
        assertEquals(
                List.of(new MissingDependency("exchange.epistola.app", "acme-corp", "shared", "1.2.0", "unavailable")),
                e.getMissingDependencies());
        assertFalse(e.isTemplateDataProblem());
    }

    @Test
    void a_release_dependencies_unreleased_problem_lists_the_drafts() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        """
                        {
                          "type": "https://epistola.app/errors/release-dependencies-unreleased",
                          "title": "Release dependencies unreleased",
                          "status": 409,
                          "unreleasedDependencies": [
                            {"registry": "exchange.epistola.app", "namespace": "acme-corp", "catalogKey": "branding",
                             "releaseId": "r4t8w2nc", "releaseName": "House style 2027"}
                          ]
                        }
                        """)));

        assertEquals(KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED, e.getTypeSlug());
        assertTrue(e.isReleaseDependenciesUnreleasedProblem());
        assertFalse(e.isReleaseDependenciesMissingProblem());
        assertEquals(
                List.of(new UnreleasedDependency("exchange.epistola.app", "acme-corp", "branding",
                        "r4t8w2nc", "House style 2027")),
                e.getUnreleasedDependencies());
    }

    @Test
    void a_catalog_read_only_problem_says_why() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        """
                        {"type": "https://epistola.app/errors/catalog-read-only", "title": "Catalog Read Only",
                         "status": 409, "readOnlyReason": "editing-disabled"}
                        """)));

        assertEquals(KnownProblemSlugs.CATALOG_READ_ONLY, e.getTypeSlug());
        assertTrue(e.isCatalogReadOnlyProblem());
        assertEquals("editing-disabled", e.getReadOnlyReason());
    }

    @Test
    void a_merge_conflicts_unresolved_problem_lists_the_conflicts() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        """
                        {"type": "https://epistola.app/errors/merge-conflicts-unresolved",
                         "title": "Merge Conflicts Unresolved", "status": 409,
                         "unresolvedConflicts": ["stencil/footer", 7, "template/invoice"]}
                        """)));

        assertEquals(KnownProblemSlugs.MERGE_CONFLICTS_UNRESOLVED, e.getTypeSlug());
        assertTrue(e.isMergeConflictsUnresolvedProblem());
        assertEquals(List.of("stencil/footer", "template/invoice"), e.getUnresolvedConflicts());
    }

    @Test
    void a_merge_base_missing_problem_names_the_release_to_get_first() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        """
                        {"type": "https://epistola.app/errors/merge-base-missing",
                         "title": "Merge Base Missing", "status": 409,
                         "version": "1.0.0", "fingerprint": "v5:9999999999999999999999999999999999999999999999999999999999999999"}
                        """)));

        assertEquals(KnownProblemSlugs.MERGE_BASE_MISSING, e.getTypeSlug());
        assertTrue(e.isMergeBaseMissingProblem());
        assertEquals("1.0.0", e.getMissingBaseVersion());
        assertEquals("v5:9999999999999999999999999999999999999999999999999999999999999999", e.getMissingBaseFingerprint());
    }

    @Test
    void another_problem_with_a_version_names_no_merge_base() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        """
                        {"type": "https://epistola.app/errors/catalog-schema-too-old",
                         "title": "Too old", "status": 409, "version": "4"}
                        """)));

        assertFalse(e.isMergeBaseMissingProblem());
        assertNull(e.getMissingBaseVersion());
        assertNull(e.getMissingBaseFingerprint());
    }

    @Test
    void another_problem_lists_no_missing_dependencies() {
        ProblemDetailException e = assertThrows(ProblemDetailException.class, () -> callGetTenant(request ->
                StubServer.StubResponse.of(409, PROBLEM_JSON,
                        "{\"type\":\"https://epistola.app/errors/conflict\",\"title\":\"Conflict\",\"status\":409}")));

        assertFalse(e.isReleaseDependenciesMissingProblem());
        assertTrue(e.getMissingDependencies().isEmpty());
        assertFalse(e.isReleaseDependenciesUnreleasedProblem());
        assertTrue(e.getUnreleasedDependencies().isEmpty());
        assertFalse(e.isCatalogReadOnlyProblem());
        assertNull(e.getReadOnlyReason());
        assertFalse(e.isMergeConflictsUnresolvedProblem());
        assertTrue(e.getUnresolvedConflicts().isEmpty());
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
