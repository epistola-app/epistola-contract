// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema

import app.epistola.client.api.TemplatesApi

/**
 * Checks template data against a template's data contract, throwing when it does not fit.
 *
 * The check itself is delegated to a [TemplateDataValidator]; this class is the thin, throwing
 * façade over it. By default that is [ServerTemplateDataValidator], so no JSON Schema library is
 * involved and the verdict is the server's:
 *
 * ```kotlin
 * val validator = TemplateSchemaValidator(templatesApi)
 * validator.validate("my-tenant", "default", "my-template", myDataMap)
 * ```
 *
 * To have the check run in-process instead, pass an implementation of [TemplateDataValidator] built
 * on the JSON Schema engine of your choice:
 *
 * ```kotlin
 * val validator = TemplateSchemaValidator(MyNetworkntValidator(templatesApi))
 * ```
 *
 * @see TemplateDataValidator for the error shape every implementation owes its callers.
 */
class TemplateSchemaValidator(private val validator: TemplateDataValidator) {

    /**
     * Validates against the server, using [ServerTemplateDataValidator].
     *
     * @param templatesApi The generated [TemplatesApi] used to reach `validateTemplateData`.
     */
    constructor(templatesApi: TemplatesApi) : this(ServerTemplateDataValidator(templatesApi))

    /**
     * Validates [data] against the data contract of the specified template.
     *
     * @param tenantId Tenant identifier.
     * @param catalogId Catalog identifier.
     * @param templateId Template identifier.
     * @param data The data object (typically a `Map<String, Any?>`) to validate.
     * @throws TemplateDataValidationException if the data does not fit the contract.
     * @throws org.springframework.web.client.RestClientResponseException if the validator reaches
     *   the server and the call fails — including against a server older than contract 1.4.0,
     *   which does not offer `validateTemplateData`.
     */
    fun validate(tenantId: String, catalogId: String, templateId: String, data: Any) {
        val errors = validator.validate(tenantId, catalogId, templateId, data)
        if (errors.isNotEmpty()) {
            throw TemplateDataValidationException(errors)
        }
    }
}
