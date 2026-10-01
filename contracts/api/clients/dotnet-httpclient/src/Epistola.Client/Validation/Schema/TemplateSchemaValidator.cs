// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using Epistola.Client.Api;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Checks template data against a template's data contract, throwing when it does not fit.
///
/// <para>
/// The check itself is delegated to an <see cref="ITemplateDataValidator"/>; this class is the thin,
/// throwing façade over it. By default that is <see cref="ServerTemplateDataValidator"/>, so no
/// JSON Schema library is involved and the verdict is the server's:
/// </para>
///
/// <code>
/// var validator = new TemplateSchemaValidator(templatesApi);
/// validator.Validate("my-tenant", "my-catalog", "my-template", myData);
/// </code>
///
/// <para>
/// To have the check run in-process instead, pass an implementation built on the library of your
/// choice:
/// </para>
///
/// <code>
/// var validator = new TemplateSchemaValidator(new MyNJsonSchemaValidator(templatesApi));
/// </code>
///
/// <para>See <see cref="ITemplateDataValidator"/> for the error shape every implementation owes its callers.</para>
/// </summary>
public sealed class TemplateSchemaValidator
{
    private readonly ITemplateDataValidator _validator;

    /// <summary>Validates against the server, using <see cref="ServerTemplateDataValidator"/>.</summary>
    /// <param name="templatesApi">The generated API used to reach <c>validateTemplateData</c>.</param>
    public TemplateSchemaValidator(ITemplatesApi templatesApi)
        : this(new ServerTemplateDataValidator(templatesApi))
    {
    }

    /// <param name="validator">Where the verdict comes from.</param>
    public TemplateSchemaValidator(ITemplateDataValidator validator)
    {
        _validator = validator;
    }

    /// <summary>
    /// Validates <paramref name="data"/> against the template's data contract.
    /// </summary>
    /// <exception cref="TemplateDataValidationException">If the data does not fit the contract.</exception>
    /// <exception cref="Epistola.Client.Client.ApiException">
    /// If the validator reaches the server and the call fails — including against a server older
    /// than contract 1.4.0, which does not offer <c>validateTemplateData</c>.
    /// </exception>
    public void Validate(string tenantId, string catalogId, string templateId, object data)
    {
        var errors = _validator.Validate(tenantId, catalogId, templateId, data);
        if (errors.Count > 0)
        {
            throw new TemplateDataValidationException(errors);
        }
    }
}
