// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import app.epistola.client.jakarta.api.TemplatesApi;
import java.util.List;

/**
 * Checks generation data against a template's data contract, throwing when it does not fit.
 *
 * <p>The check itself is delegated to a {@link TemplateDataValidator}; this class is the thin,
 * throwing façade over it. By default that is {@link ServerTemplateDataValidator}, so no JSON
 * Schema engine is involved and the verdict is the server's:
 *
 * <pre>{@code
 * TemplateSchemaValidator validator = new TemplateSchemaValidator(templatesApi);
 * validator.validate("my-tenant", "default", "monthly-invoice", data);
 * }</pre>
 *
 * <p>To have the check run in-process instead, pass an implementation built on the JSON Schema
 * engine of your choice:
 *
 * <pre>{@code
 * TemplateSchemaValidator validator = new TemplateSchemaValidator(new MyNetworkntValidator(templatesApi));
 * }</pre>
 *
 * @see TemplateDataValidator for the error shape every implementation owes its callers
 */
public class TemplateSchemaValidator {

    private final TemplateDataValidator validator;

    /** Validates against the server, using {@link ServerTemplateDataValidator}. */
    public TemplateSchemaValidator(TemplatesApi templatesApi) {
        this(new ServerTemplateDataValidator(templatesApi));
    }

    /** @param validator where the verdict comes from */
    public TemplateSchemaValidator(TemplateDataValidator validator) {
        this.validator = validator;
    }

    /**
     * Validates {@code data} against the template's data contract.
     *
     * @throws TemplateDataValidationException when the data does not fit the contract
     * @throws app.epistola.client.jakarta.api.ApiException when the validator reaches the server
     *     and the call fails — including against a server older than contract 1.4.0, which does not
     *     offer {@code validateTemplateData}
     */
    public void validate(String tenantId, String catalogId, String templateId, Object data) {
        List<TemplateDataValidationException.ValidationError> errors =
                validator.validate(tenantId, catalogId, templateId, data);
        if (!errors.isEmpty()) {
            throw new TemplateDataValidationException(errors);
        }
    }
}
