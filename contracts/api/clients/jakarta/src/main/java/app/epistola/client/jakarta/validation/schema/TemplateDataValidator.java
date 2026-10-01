// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import java.util.List;

/**
 * Decides whether template data satisfies a template's data contract.
 *
 * <p>The client ships exactly one implementation, {@link ServerTemplateDataValidator}, and uses it
 * by default. That is deliberate: nothing in this artifact pins a JSON Schema engine, so no
 * container inherits one. A consumer who wants the check to run in-process — pre-flighting a large
 * batch without a request per item, say — implements this interface over the engine of their
 * choice, and keeps the parts worth sharing: the typed exception, and the batch aggregation in
 * {@link ValidatingGenerationApi}.
 *
 * <h2>The errors an implementation returns</h2>
 *
 * <p>An empty list means the data is acceptable. Anything else is a finding, and the three members
 * are a <strong>contract rather than a convention</strong> — {@link TemplateSchemaValidator} puts
 * them straight into a {@link TemplateDataValidationException}, and callers read them to point at
 * the field that is wrong:
 *
 * <ul>
 *   <li><strong>{@code path} is a JSON Pointer (RFC 6901) into the data</strong>:
 *       {@code /customer/email}, {@code /lineItems/0/quantity}, and {@code ""} for the document
 *       root. It is not a JSONPath ({@code $.customer.email}) and not a dotted key. Engines
 *       genuinely disagree here — ajv reports {@code instancePath}, networknt an
 *       {@code instanceLocation} whose {@code toString()} is JSONPath — so converting is the
 *       adapter's job, not the caller's.
 *   <li><strong>{@code keyword}</strong> is the JSON Schema keyword that failed
 *       ({@code required}, {@code type}, {@code minLength}), or null.
 *   <li><strong>{@code message}</strong> is human-readable text. It is shown to people, so it
 *       should not contain the raw schema or the failing pattern.
 * </ul>
 *
 * <p>Holding every implementation to one shape is the reason this interface exists. Without it a
 * {@code TemplateDataValidationException} would mean something different depending on which engine
 * produced it, which is the drift that shipping four validators caused in the first place.
 */
public interface TemplateDataValidator {

    /**
     * Returns the findings for {@code data} against the given template's data contract, empty when
     * there are none.
     *
     * @param tenantId   tenant identifier
     * @param catalogId  catalog identifier; the same template id in two catalogs of one tenant is
     *                   two different templates with two different contracts
     * @param templateId template identifier
     * @param data       the data object to check
     */
    List<TemplateDataValidationException.ValidationError> validate(
            String tenantId, String catalogId, String templateId, Object data);

    /**
     * Whether {@link ValidatingGenerationApi} should check data with this validator <em>before</em>
     * submitting a generation request.
     *
     * <p>True for a validator that answers in-process, where checking first is nearly free and
     * reports every item of a batch at once. False for one that asks the server, because the server
     * validates the very same data when the job is submitted: pre-flighting would double the
     * requests for a single document and add one per item for a batch, to reach the same verdict. A
     * validator that declines pre-flight still produces a {@link TemplateDataValidationException}
     * out of the generation call itself — {@code ValidatingGenerationApi} translates the server's
     * {@code template-data-invalid} problem into one either way.
     */
    default boolean preflightsGeneration() {
        return true;
    }
}
