// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.validation.schema

/**
 * Decides whether template data satisfies a template's data contract.
 *
 * The client ships exactly one implementation, [ServerTemplateDataValidator], and uses it by
 * default. That is deliberate: nothing in this artifact pins a JSON Schema library, so no consumer
 * inherits one. A consumer who wants the check to run in-process — pre-flighting a large batch
 * without a request per item, say — implements this interface over the engine of their choice. The
 * client keeps providing the parts worth sharing: the typed exception, and the batch aggregation in
 * [ValidatingGenerationApi].
 *
 * ## The errors an implementation returns
 *
 * An empty list means the data is acceptable. Anything else is a finding, and the three members are
 * a **contract rather than a convention** — [TemplateSchemaValidator] puts them straight into a
 * [TemplateDataValidationException], and callers read them to point at the field that is wrong:
 *
 * - **`path` is a JSON Pointer (RFC 6901) into the data**: `/customer/email`,
 *   `/lineItems/0/quantity`, and `""` for the document root. It is not a JSONPath
 *   (`$.customer.email`) and not a dotted key. Engines genuinely disagree here — ajv reports
 *   `instancePath`, networknt an `instanceLocation` whose `toString()` is JSONPath, Python's
 *   `jsonschema` a deque of path segments — so converting is the adapter's job, not the caller's.
 * - **`keyword`** is the JSON Schema keyword that failed (`required`, `type`, `minLength`), or
 *   `null` when the engine does not report one.
 * - **`message`** is human-readable text describing the failure. It is shown to people, so it
 *   should not contain the raw schema or the failing pattern.
 *
 * Holding every implementation to one shape is the reason this interface exists. Without it a
 * `TemplateDataValidationException` would mean something different depending on which engine
 * produced it, which is the drift that shipping four validators caused in the first place.
 */
fun interface TemplateDataValidator {

    /**
     * Returns the findings for [data] against the data contract of the given template, empty when
     * there are none.
     *
     * @param tenantId Tenant identifier.
     * @param catalogId Catalog identifier. The same template id in two catalogs of one tenant is
     *   two different templates with two different contracts.
     * @param templateId Template identifier.
     * @param data The data object, typically a `Map<String, Any?>`.
     */
    fun validate(
        tenantId: String,
        catalogId: String,
        templateId: String,
        data: Any,
    ): List<TemplateDataValidationException.ValidationError>

    /**
     * Whether [ValidatingGenerationApi] should check data with this validator *before* submitting
     * a generation request.
     *
     * `true` for a validator that answers in-process, where checking first is nearly free and
     * reports every item of a batch at once. `false` for one that asks the server, because the
     * server validates the very same data when the job is submitted: pre-flighting would double
     * the requests for a single document and add one per item for a batch, to reach the same
     * verdict. A validator that declines pre-flight still gets a
     * [TemplateDataValidationException] out of the generation call itself — `ValidatingGenerationApi`
     * translates the server's `template-data-invalid` problem into one either way.
     */
    val preflightsGeneration: Boolean
        get() = true
}
