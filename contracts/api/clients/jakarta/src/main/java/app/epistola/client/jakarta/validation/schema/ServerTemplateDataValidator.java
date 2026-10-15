// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.TemplateDataValidationResult;
import app.epistola.client.jakarta.model.ValidateTemplateDataRequest;
import java.util.Collections;
import java.util.List;

/**
 * The {@link TemplateDataValidator} the client ships: it asks Epistola.
 *
 * <p>This is the default, and it carries no JSON Schema engine. The server already owns the verdict
 * — it validates every generation request whatever the client did first — so asking it is the only
 * answer that cannot disagree with what generation will do. It also knows things a schema alone
 * does not: which optional fields the resolved version's template actually reads.
 *
 * <p>Validation is a far cheaper call than rendering, so checking as data is entered is reasonable.
 * It is still a network call, which is why {@link #preflightsGeneration()} is false.
 *
 * <p><strong>Server floor.</strong> {@code validateTemplateData} arrived with contract
 * <strong>1.4.0</strong>. Against an older server the call fails like any other unknown operation,
 * as an {@link app.epistola.client.jakarta.api.ApiException}; it is not degraded into "valid",
 * because silently reporting unvalidated data as acceptable is worse than failing.
 */
public class ServerTemplateDataValidator implements TemplateDataValidator {

    private final TemplatesApi templatesApi;
    private final String variantId;
    private final String version;
    private final String environmentId;

    public ServerTemplateDataValidator(TemplatesApi templatesApi) {
        this(templatesApi, null, null, null);
    }

    /**
     * @param templatesApi  the generated API used to reach {@code validateTemplateData}
     * @param variantId      optional variant to check against; without it, the default variant
     * @param version        optional version selector for the release of the template's catalog to
     *                       check against ({@code 1.4.0}, {@code ^1.4.0}); without it, the highest
     *                       release, as preview and generation would render
     * @param environmentId  optional environment whose deployed releases to choose from; combines
     *                       with {@code version}
     */
    public ServerTemplateDataValidator(
            TemplatesApi templatesApi, String variantId, String version, String environmentId) {
        this.templatesApi = templatesApi;
        this.variantId = variantId;
        this.version = version;
        this.environmentId = environmentId;
    }

    @Override
    public List<TemplateDataValidationException.ValidationError> validate(
            String tenantId, String namespace, String catalogKey, String templateId, Object data) {

        ValidateTemplateDataRequest request = new ValidateTemplateDataRequest();
        request.setData(data);
        request.setVariantId(variantId);
        request.setVersion(version);
        request.setEnvironmentId(environmentId);

        TemplateDataValidationResult result =
                templatesApi.validateTemplateData(tenantId, namespace, catalogKey, templateId, request);
        if (result == null) {
            return Collections.emptyList();
        }
        if (Boolean.TRUE.equals(result.getValid())) {
            return Collections.emptyList();
        }
        return TemplateDataProblems.toValidationErrors(
                result.getErrors(), result.getMissingFields(), result.getInvalidFields());
    }

    /**
     * False — the server checks the same data when the generation request is submitted, so
     * pre-flighting here would only spend a second round trip to learn the same thing.
     */
    @Override
    public boolean preflightsGeneration() {
        return false;
    }
}
