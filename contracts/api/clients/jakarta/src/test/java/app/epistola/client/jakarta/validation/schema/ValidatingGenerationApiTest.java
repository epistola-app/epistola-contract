// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.FakeApis;
import app.epistola.client.jakarta.api.GenerationApi;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.model.BatchGenerationItem;
import app.epistola.client.jakarta.model.GenerateBatchRequest;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationJobResponse;
import app.epistola.client.jakarta.model.InvalidDataField;
import app.epistola.client.jakarta.model.MissingDataField;
import app.epistola.client.jakarta.model.ProblemDetail;
import app.epistola.client.jakarta.validation.schema.TemplateDataValidationException.ValidationError;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link ValidatingGenerationApi} reaches one exception type by two routes, and which route applies
 * is the validator's call. Both are pinned here, including that the server-backed default spends no
 * extra request to reach it.
 */
class ValidatingGenerationApiTest {

    private final AtomicInteger validations = new AtomicInteger();
    private final AtomicInteger submissions = new AtomicInteger();

    private final GenerateDocumentRequest request =
            new GenerateDocumentRequest().catalogId("default").templateId("invoice").data(Map.of());

    private final GenerateBatchRequest batch = new GenerateBatchRequest()
            .items(List.of(
                    new BatchGenerationItem().catalogId("default").templateId("invoice").data(Map.of()),
                    new BatchGenerationItem().catalogId("default").templateId("reminder").data(Map.of())));

    /** A validator that answers in-process, so it takes the pre-flight. */
    private TemplateDataValidator local(String... paths) {
        return (tenantId, catalogId, templateId, data) -> {
            validations.incrementAndGet();
            List<ValidationError> findings = new ArrayList<>();
            for (String path : paths) {
                findings.add(new ValidationError(path, "is required but was not supplied", "required"));
            }
            return findings;
        };
    }

    private TemplatesApi countingTemplatesApi() {
        return FakeApis.of(TemplatesApi.class, Map.of("validateTemplateData", args -> {
            validations.incrementAndGet();
            throw new AssertionError("the server must not be asked to pre-flight a generation request");
        }));
    }

    private GenerationApi generationApi(RuntimeException failure) {
        Map<String, java.util.function.Function<Object[], Object>> stubs = Map.of(
                "generateDocument", args -> {
                    submissions.incrementAndGet();
                    if (failure != null) {
                        throw failure;
                    }
                    return new GenerationJobResponse();
                },
                "generateDocumentBatch", args -> {
                    submissions.incrementAndGet();
                    if (failure != null) {
                        throw failure;
                    }
                    return new GenerationJobResponse();
                });
        return FakeApis.of(GenerationApi.class, stubs);
    }

    private static ProblemDetailException templateDataInvalid(
            List<MissingDataField> missingFields, List<InvalidDataField> invalidFields) {
        ProblemDetail problem = new ProblemDetail()
                .type(URI.create("https://epistola.app/errors/template-data-invalid"))
                .title("Template data invalid")
                .status(400)
                .detail("The supplied data does not fit the template's data contract");
        return new ProblemDetailException(
                Response.status(400).build(), problem, List.of(), Map.of(), missingFields, invalidFields, "{}");
    }

    @Test
    void the_default_validator_submits_without_a_preflight_request() {
        new ValidatingGenerationApi(generationApi(null), countingTemplatesApi()).generateDocument("acme-corp", request);

        // The point of ServerTemplateDataValidator.preflightsGeneration() being false: the server
        // validates what it is given, so asking it first would be a second round trip for the same
        // verdict.
        assertEquals(0, validations.get());
        assertEquals(1, submissions.get());
    }

    @Test
    void a_rejected_submission_becomes_a_template_data_validation_exception() {
        ProblemDetailException rejected = templateDataInvalid(
                List.of(
                        new MissingDataField().path("/invoiceNumber").required(true).schema(Map.of()),
                        new MissingDataField().path("/customer/phone").required(false).schema(Map.of())),
                List.of(new InvalidDataField()
                        .path("/customer/email")
                        .keyword("format")
                        .message("must be a valid email address")));

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new ValidatingGenerationApi(generationApi(rejected), countingTemplatesApi())
                        .generateDocument("acme-corp", request));

        // The optional missing field is not a finding; the required one is.
        assertEquals(
                List.of("/customer/email", "/invoiceNumber"),
                e.getErrors().stream().map(ValidationError::getPath).toList());
        assertEquals(List.of("format", "required"), e.getErrors().stream().map(ValidationError::getKeyword).toList());
    }

    @Test
    void any_other_problem_propagates_untouched() {
        ProblemDetailException notFound = new ProblemDetailException(
                Response.status(404).build(),
                new ProblemDetail()
                        .type(URI.create("https://epistola.app/errors/not-found"))
                        .title("Not Found")
                        .status(404),
                List.of(),
                Map.of(),
                "{}");

        ProblemDetailException thrown = assertThrows(
                ProblemDetailException.class,
                () -> new ValidatingGenerationApi(generationApi(notFound), countingTemplatesApi())
                        .generateDocument("acme-corp", request));

        assertSame(notFound, thrown);
    }

    @Test
    void a_local_validator_throws_before_the_request_is_sent() {
        assertThrows(
                TemplateDataValidationException.class,
                () -> new ValidatingGenerationApi(generationApi(null), local("/name"))
                        .generateDocument("acme-corp", request));

        assertEquals(0, submissions.get());
    }

    @Test
    void a_local_validator_reports_every_item_of_a_batch_at_once() {
        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> new ValidatingGenerationApi(generationApi(null), local("/name"))
                        .generateDocumentBatch("acme-corp", batch));

        assertEquals(
                List.of("items[0]/name", "items[1]/name"),
                e.getErrors().stream().map(ValidationError::getPath).toList());
        assertEquals(0, submissions.get());
    }

    @Test
    void a_local_validator_that_finds_nothing_lets_the_request_through() {
        new ValidatingGenerationApi(generationApi(null), local()).generateDocumentBatch("acme-corp", batch);

        assertEquals(1, submissions.get());
        assertTrue(validations.get() >= 2, "every item is checked");
    }

    @Test
    void the_default_validator_preflights_nothing_per_batch_item_either() {
        new ValidatingGenerationApi(generationApi(null), countingTemplatesApi())
                .generateDocumentBatch("acme-corp", batch);

        assertEquals(0, validations.get());
        assertEquals(1, submissions.get());
    }
}
