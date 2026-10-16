// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema

import app.epistola.client.api.TemplatesApi
import app.epistola.client.model.ValidateTemplateDataRequest

/**
 * The [TemplateDataValidator] the client ships: it asks Epistola.
 *
 * This is the default, and it carries no JSON Schema library. The server already owns the verdict —
 * it validates every generation request whatever the client did first — so asking it is the only
 * answer that cannot disagree with what generation will do. It also knows things a schema alone
 * does not: which optional fields the resolved version's template actually reads.
 *
 * Validation is a far cheaper call than rendering, so checking as data is typed is reasonable. It
 * is still a network call, which is why [preflightsGeneration] is `false`: see
 * [TemplateDataValidator.preflightsGeneration].
 *
 * **Server floor.** `validateTemplateData` arrived with contract **1.4.0**. Against an older
 * server the call fails like any other unknown operation, as a
 * `org.springframework.web.client.RestClientResponseException`; it is not degraded into "valid",
 * because silently reporting unvalidated data as acceptable is worse than failing.
 *
 * @param templatesApi The generated [TemplatesApi].
 * @param variantKey Optional variant to check against; without it, the default variant.
 * @param version Optional version selector for the template's catalog release to check against,
 *   such as `1.4.0` or `^1.4.0`. It may be combined with [environmentKey], which then narrows the
 *   choice to the releases deployed there. Without either, the catalog's latest release is used —
 *   the release preview and generation would render for the same selection.
 * @param environmentKey Optional environment whose deployed releases to check against.
 */
class ServerTemplateDataValidator @JvmOverloads constructor(
    private val templatesApi: TemplatesApi,
    private val variantKey: String? = null,
    private val version: String? = null,
    private val environmentKey: String? = null,
) : TemplateDataValidator {

    override fun validate(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        templateKey: String,
        data: Any,
    ): List<TemplateDataValidationException.ValidationError> {
        val result = templatesApi.validateTemplateData(
            tenantKey,
            registry,
            namespace,
            catalogKey,
            templateKey,
            ValidateTemplateDataRequest(
                data = data,
                variantKey = variantKey,
                version = version,
                environmentKey = environmentKey,
            ),
        )
        if (result.valid) {
            return emptyList()
        }
        return templateDataValidationErrors(
            errors = result.errors,
            missingFields = result.missingFields,
            invalidFields = result.invalidFields,
        )
    }

    /**
     * `false` — the server checks the same data when the generation request is submitted, so
     * pre-flighting it here would only spend a second round trip to learn the same thing.
     */
    override val preflightsGeneration: Boolean
        get() = false
}
