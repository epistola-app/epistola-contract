// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import app.epistola.client.jakarta.api.GenerationApi;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.BatchGenerationItem;
import app.epistola.client.jakarta.model.GenerateBatchRequest;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationJobResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps {@link GenerationApi} to validate request data against the template's JSON Schema before
 * anything is sent.
 *
 * <p>A single-document request fails on the first violation. A batch is validated in full and every
 * violation is reported at once, each path prefixed with the item it came from — otherwise fixing a
 * hundred-item batch takes a hundred round trips.
 *
 * <pre>{@code
 * ValidatingGenerationApi generation = new ValidatingGenerationApi(generationApi, templatesApi);
 * generation.generateDocument("my-tenant", request);   // validated, then sent
 * }</pre>
 *
 * <p>Like {@link TemplateSchemaValidator}, this needs {@code com.networknt:json-schema-validator}
 * on the consumer's classpath.
 */
public class ValidatingGenerationApi {

    private final GenerationApi delegate;
    private final TemplateSchemaValidator validator;

    public ValidatingGenerationApi(GenerationApi delegate, TemplatesApi templatesApi) {
        this(delegate, templatesApi, new TtlSchemaCache());
    }

    public ValidatingGenerationApi(GenerationApi delegate, TemplatesApi templatesApi, SchemaCache cache) {
        this.delegate = delegate;
        this.validator = new TemplateSchemaValidator(templatesApi, cache);
    }

    /** Validates the request data, then submits it. */
    public GenerationJobResponse generateDocument(String tenantId, GenerateDocumentRequest request) {
        validator.validate(tenantId, request.getCatalogId(), request.getTemplateId(), request.getData());
        return delegate.generateDocument(tenantId, request);
    }

    /** Validates every item, reporting all failures together, then submits the batch. */
    public GenerationJobResponse generateDocumentBatch(String tenantId, GenerateBatchRequest request) {
        validateBatch(tenantId, request);
        return delegate.generateDocumentBatch(tenantId, request);
    }

    private void validateBatch(String tenantId, GenerateBatchRequest request) {
        List<TemplateDataValidationException.ValidationError> allErrors = new ArrayList<>();
        List<BatchGenerationItem> items = request.getItems();
        for (int index = 0; index < items.size(); index++) {
            BatchGenerationItem item = items.get(index);
            try {
                validator.validate(tenantId, item.getCatalogId(), item.getTemplateId(), item.getData());
            } catch (TemplateDataValidationException e) {
                String prefix = "items[" + index + "]";
                for (TemplateDataValidationException.ValidationError error : e.getErrors()) {
                    allErrors.add(error.withPathPrefix(prefix));
                }
            }
        }
        if (!allErrors.isEmpty()) {
            throw new TemplateDataValidationException(allErrors);
        }
    }
}
