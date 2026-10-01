// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import app.epistola.client.jakarta.api.GenerationApi;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.error.KnownProblemSlugs;
import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.model.BatchGenerationItem;
import app.epistola.client.jakarta.model.GenerateBatchRequest;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationJobResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Wraps {@link GenerationApi} to report unacceptable template data as a
 * {@link TemplateDataValidationException} rather than a generic problem response.
 *
 * <p>It gets there two ways, and which one applies is the {@link TemplateDataValidator}'s call:
 *
 * <ul>
 *   <li><strong>Before the request</strong>, when the validator answers in-process
 *       ({@link TemplateDataValidator#preflightsGeneration()}). Nothing is sent, and every item of
 *       a batch is reported at once with its {@code items[i]} path prefix — otherwise fixing a
 *       hundred-item batch takes a hundred round trips.
 *   <li><strong>From the response</strong>, always. The server validates the data it is given, so a
 *       rejected request comes back as a {@code template-data-invalid} problem, which is translated
 *       into the same exception with the same field pointers.
 * </ul>
 *
 * <p>The default validator asks the server, and therefore declines the pre-flight: checking first
 * would spend an extra round trip — one per item, for a batch — to learn what submitting already
 * tells us. Either way the caller catches one exception type and reads one error shape.
 *
 * <pre>{@code
 * ValidatingGenerationApi generation = new ValidatingGenerationApi(generationApi, templatesApi);
 * try {
 *     generation.generateDocument("my-tenant", request);
 * } catch (TemplateDataValidationException e) {
 *     log.warn("rejected:\n{}", e.formatErrors());
 * }
 * }</pre>
 */
public class ValidatingGenerationApi {

    private final GenerationApi delegate;
    private final TemplateDataValidator validator;

    /** Validates against the server, using {@link ServerTemplateDataValidator}. */
    public ValidatingGenerationApi(GenerationApi delegate, TemplatesApi templatesApi) {
        this(delegate, new ServerTemplateDataValidator(templatesApi));
    }

    public ValidatingGenerationApi(GenerationApi delegate, TemplateDataValidator validator) {
        this.delegate = delegate;
        this.validator = validator;
    }

    /** Checks the request data where the validator asks for it, then submits. */
    public GenerationJobResponse generateDocument(String tenantId, GenerateDocumentRequest request) {
        if (validator.preflightsGeneration()) {
            new TemplateSchemaValidator(validator)
                    .validate(tenantId, request.getCatalogId(), request.getTemplateId(), request.getData());
        }
        return translatingProblem(() -> delegate.generateDocument(tenantId, request));
    }

    /** The same for a batch, reporting every item's failures together when it pre-flights. */
    public GenerationJobResponse generateDocumentBatch(String tenantId, GenerateBatchRequest request) {
        preflightBatch(tenantId, request);
        return translatingProblem(() -> delegate.generateDocumentBatch(tenantId, request));
    }

    private void preflightBatch(String tenantId, GenerateBatchRequest request) {
        if (!validator.preflightsGeneration()) {
            return;
        }
        List<TemplateDataValidationException.ValidationError> allErrors = new ArrayList<>();
        List<BatchGenerationItem> items = request.getItems();
        for (int index = 0; index < items.size(); index++) {
            BatchGenerationItem item = items.get(index);
            String prefix = "items[" + index + "]";
            for (TemplateDataValidationException.ValidationError error :
                    validator.validate(tenantId, item.getCatalogId(), item.getTemplateId(), item.getData())) {
                allErrors.add(error.withPathPrefix(prefix));
            }
        }
        if (!allErrors.isEmpty()) {
            throw new TemplateDataValidationException(allErrors);
        }
    }

    /**
     * Rewrites the server's {@code template-data-invalid} problem into the exception a caller of
     * this class is already catching. Every other problem propagates untouched — this class narrows
     * one failure mode, it does not swallow failures.
     */
    private <T> T translatingProblem(Supplier<T> call) {
        try {
            return call.get();
        } catch (ProblemDetailException e) {
            if (!KnownProblemSlugs.TEMPLATE_DATA_INVALID.equals(e.getTypeSlug())) {
                throw e;
            }
            throw new TemplateDataValidationException(
                    TemplateDataProblems.toValidationErrors(null, e.getMissingFields(), e.getInvalidFields()));
        }
    }
}
