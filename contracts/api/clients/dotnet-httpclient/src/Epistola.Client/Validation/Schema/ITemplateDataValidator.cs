// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Collections.Generic;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Decides whether template data satisfies a template's data contract.
///
/// <para>
/// This package ships exactly one implementation, <see cref="ServerTemplateDataValidator"/>, and
/// uses it by default. That is deliberate: nothing here pins a JSON Schema library, so no consumer
/// installs one for a feature they may never call. A consumer who wants the check to run in-process
/// — pre-flighting a large batch without a request per item, say — implements this interface over
/// the library of their choice and keeps the parts worth sharing: the typed exception, and the
/// batch aggregation in <see cref="ValidatingGenerationApi"/>.
/// </para>
///
/// <para>
/// <b>The errors an implementation returns.</b> An empty list means the data is acceptable.
/// Anything else is a finding, and the three members are a <i>contract rather than a
/// convention</i> — <see cref="TemplateSchemaValidator"/> puts them straight into a
/// <see cref="TemplateDataValidationException"/>, and callers read them to point at the field that
/// is wrong:
/// </para>
///
/// <list type="bullet">
///   <item><description>
///     <b><c>Path</c> is a JSON Pointer (RFC 6901) into the data</b>: <c>/customer/email</c>,
///     <c>/lineItems/0/quantity</c>, and <c>""</c> for the document root. Libraries genuinely
///     disagree here — NJsonSchema reports a <c>#/customer/email</c> style path, ajv an
///     already-pointer <c>instancePath</c>, networknt a JSONPath — so converting is the adapter's
///     job, not the caller's.
///   </description></item>
///   <item><description>
///     <b><c>Keyword</c></b> is the JSON Schema keyword that failed (<c>required</c>, <c>type</c>,
///     <c>minLength</c>), or <c>null</c>.
///   </description></item>
///   <item><description>
///     <b><c>Message</c></b> is human-readable text. It is shown to people, so it should not
///     contain the raw schema or the failing pattern.
///   </description></item>
/// </list>
///
/// <para>
/// Holding every implementation to one shape is the reason this interface exists. Without it a
/// <see cref="TemplateDataValidationException"/> means something different per library, which is
/// the drift that shipping a validator in each of five clients had already produced.
/// </para>
/// </summary>
public interface ITemplateDataValidator
{
    /// <summary>
    /// Returns the findings for <paramref name="data"/> against the given template's data contract,
    /// empty when there are none.
    /// </summary>
    /// <param name="tenantId">Tenant identifier.</param>
    /// <param name="catalogId">
    /// Catalog identifier. The same template id in two catalogs of one tenant is two different
    /// templates with two different contracts.
    /// </param>
    /// <param name="templateId">Template identifier.</param>
    /// <param name="data">The data object to check.</param>
    IReadOnlyList<TemplateDataValidationException.ValidationError> Validate(
        string tenantId, string catalogId, string templateId, object data);

    /// <summary>
    /// Whether <see cref="ValidatingGenerationApi"/> should check data with this validator
    /// <i>before</i> submitting a generation request.
    ///
    /// <para>
    /// <c>true</c> for a validator that answers in-process, where checking first is nearly free and
    /// reports every item of a batch at once. <c>false</c> for one that asks the server, because the
    /// server validates the very same data when the job is submitted: pre-flighting would double the
    /// requests for a single document and add one per item for a batch, to reach the same verdict. A
    /// validator that declines pre-flight still produces a
    /// <see cref="TemplateDataValidationException"/> out of the generation call itself —
    /// <see cref="ValidatingGenerationApi"/> translates the server's <c>template-data-invalid</c>
    /// problem into one either way.
    /// </para>
    /// </summary>
    bool PreflightsGeneration => true;
}
