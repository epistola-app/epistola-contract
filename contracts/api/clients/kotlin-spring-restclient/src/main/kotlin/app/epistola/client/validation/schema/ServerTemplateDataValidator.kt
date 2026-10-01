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
 * @param variantId Optional variant to check against. Without any of the three selectors the data
 *   is checked against the template's latest contract; with one, against the contract of the
 *   version that selection resolves to — the version preview and generation would render.
 * @param versionId Optional explicit version number (mutually exclusive with [environmentId]).
 * @param environmentId Optional environment whose active version to check against.
 */
class ServerTemplateDataValidator @JvmOverloads constructor(
    private val templatesApi: TemplatesApi,
    private val variantId: String? = null,
    private val versionId: Int? = null,
    private val environmentId: String? = null,
) : TemplateDataValidator {

    override fun validate(
        tenantId: String,
        catalogId: String,
        templateId: String,
        data: Any,
    ): List<TemplateDataValidationException.ValidationError> {
        val result = templatesApi.validateTemplateData(
            tenantId,
            catalogId,
            templateId,
            ValidateTemplateDataRequest(
                data = data,
                variantId = variantId,
                versionId = versionId,
                environmentId = environmentId,
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
