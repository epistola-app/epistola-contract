// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Collections.Generic;
using Epistola.Client.Api;
using Epistola.Client.Model;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// The <see cref="ITemplateDataValidator"/> this package ships: it asks Epistola.
///
/// <para>
/// This is the default, and it carries no JSON Schema library. The server already owns the verdict —
/// it validates every generation request whatever the client did first — so asking it is the only
/// answer that cannot disagree with what generation will do. It also knows things a schema alone
/// does not: which optional fields the resolved version's template actually reads.
/// </para>
///
/// <para>
/// Validation is a far cheaper call than rendering, so checking as data is entered is reasonable. It
/// is still a network call, which is why <see cref="PreflightsGeneration"/> is <c>false</c>.
/// </para>
///
/// <para>
/// <b>Server floor.</b> <c>validateTemplateData</c> arrived with contract <b>1.4.0</b>. Against an
/// older server the call fails like any other unknown operation, as an
/// <see cref="Epistola.Client.Client.ApiException"/>; it is not degraded into "valid", because
/// silently reporting unvalidated data as acceptable is worse than failing.
/// </para>
/// </summary>
public sealed class ServerTemplateDataValidator : ITemplateDataValidator
{
    private readonly ITemplatesApi _templatesApi;
    private readonly string? _variantId;
    private readonly int? _versionId;
    private readonly string? _environmentId;

    /// <param name="templatesApi">The generated API used to reach <c>validateTemplateData</c>.</param>
    /// <param name="variantId">Optional variant to check against.</param>
    /// <param name="versionId">Optional explicit version number (mutually exclusive with <paramref name="environmentId"/>).</param>
    /// <param name="environmentId">Optional environment whose active version to check against.</param>
    public ServerTemplateDataValidator(
        ITemplatesApi templatesApi,
        string? variantId = null,
        int? versionId = null,
        string? environmentId = null)
    {
        _templatesApi = templatesApi;
        _variantId = variantId;
        _versionId = versionId;
        _environmentId = environmentId;
    }

    /// <summary>
    /// <c>false</c> — the server checks the same data when the generation request is submitted, so
    /// pre-flighting here would only spend a second round trip to learn the same thing.
    /// </summary>
    public bool PreflightsGeneration => false;

    /// <inheritdoc />
    public IReadOnlyList<TemplateDataValidationException.ValidationError> Validate(
        string tenantId, string catalogId, string templateId, object data)
    {
        var request = new ValidateTemplateDataRequest(
            variantId: _variantId,
            versionId: _versionId,
            environmentId: _environmentId,
            data: data);

        var result = _templatesApi.ValidateTemplateData(tenantId, catalogId, templateId, request);
        if (result == null || result.Valid)
        {
            return new List<TemplateDataValidationException.ValidationError>();
        }
        return TemplateDataProblems.ToValidationErrors(result.Errors, result.MissingFields, result.InvalidFields);
    }
}
