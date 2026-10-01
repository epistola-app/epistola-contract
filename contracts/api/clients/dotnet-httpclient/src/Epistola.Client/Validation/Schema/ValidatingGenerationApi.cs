// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Generic;
using Epistola.Client.Api;
using Epistola.Client.Client;
using Epistola.Client.Error;
using Epistola.Client.Model;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Wraps <see cref="IGenerationApi"/> to report unacceptable template data as a
/// <see cref="TemplateDataValidationException"/> rather than a generic problem response.
///
/// <para>It gets there two ways, and which one applies is the <see cref="ITemplateDataValidator"/>'s call:</para>
///
/// <list type="bullet">
///   <item><description>
///     <b>Before the request</b>, when the validator answers in-process
///     (<see cref="ITemplateDataValidator.PreflightsGeneration"/>). Nothing is sent, and every item
///     of a batch is reported at once with its <c>items[i]</c> path prefix — otherwise fixing a
///     hundred-item batch takes a hundred round trips.
///   </description></item>
///   <item><description>
///     <b>From the response</b>, always. The server validates the data it is given, so a rejected
///     request comes back as a <c>template-data-invalid</c> problem, which is translated into the
///     same exception with the same field pointers.
///   </description></item>
/// </list>
///
/// <para>
/// The default validator asks the server, and therefore declines the pre-flight: checking first
/// would spend an extra round trip — one per item, for a batch — to learn what submitting already
/// tells us. Either way the caller catches one exception type and reads one error shape.
/// </para>
///
/// <code>
/// var validating = new ValidatingGenerationApi(generationApi, templatesApi);
/// validating.GenerateDocument("my-tenant", request);
/// </code>
/// </summary>
public sealed class ValidatingGenerationApi
{
    private readonly IGenerationApi _delegate;
    private readonly ITemplateDataValidator _validator;

    /// <summary>Validates against the server, using <see cref="ServerTemplateDataValidator"/>.</summary>
    public ValidatingGenerationApi(IGenerationApi generationApi, ITemplatesApi templatesApi)
        : this(generationApi, new ServerTemplateDataValidator(templatesApi))
    {
    }

    public ValidatingGenerationApi(IGenerationApi generationApi, ITemplateDataValidator validator)
    {
        _delegate = generationApi;
        _validator = validator;
    }

    /// <summary>Checks the request data where the validator asks for it, then submits.</summary>
    public GenerationJobResponse GenerateDocument(string tenantId, GenerateDocumentRequest request)
    {
        Preflight(tenantId, request);
        return TranslatingProblem(() => _delegate.GenerateDocument(tenantId, request));
    }

    /// <inheritdoc cref="GenerateDocument"/>
    public ApiResponse<GenerationJobResponse> GenerateDocumentWithHttpInfo(string tenantId, GenerateDocumentRequest request)
    {
        Preflight(tenantId, request);
        return TranslatingProblem(() => _delegate.GenerateDocumentWithHttpInfo(tenantId, request));
    }

    /// <summary>The same for a batch, reporting every item's failures together when it pre-flights.</summary>
    public GenerationJobResponse GenerateDocumentBatch(string tenantId, GenerateBatchRequest request)
    {
        PreflightBatch(tenantId, request);
        return TranslatingProblem(() => _delegate.GenerateDocumentBatch(tenantId, request));
    }

    /// <inheritdoc cref="GenerateDocumentBatch"/>
    public ApiResponse<GenerationJobResponse> GenerateDocumentBatchWithHttpInfo(string tenantId, GenerateBatchRequest request)
    {
        PreflightBatch(tenantId, request);
        return TranslatingProblem(() => _delegate.GenerateDocumentBatchWithHttpInfo(tenantId, request));
    }

    private void Preflight(string tenantId, GenerateDocumentRequest request)
    {
        if (!_validator.PreflightsGeneration)
        {
            return;
        }
        new TemplateSchemaValidator(_validator)
            .Validate(tenantId, request.CatalogId, request.TemplateId, request.Data);
    }

    private void PreflightBatch(string tenantId, GenerateBatchRequest request)
    {
        if (!_validator.PreflightsGeneration)
        {
            return;
        }
        var allErrors = new List<TemplateDataValidationException.ValidationError>();
        for (var index = 0; index < request.Items.Count; index++)
        {
            var item = request.Items[index];
            var prefix = $"items[{index}]";
            foreach (var error in _validator.Validate(tenantId, item.CatalogId, item.TemplateId, item.Data))
            {
                allErrors.Add(error.WithPathPrefix(prefix));
            }
        }
        if (allErrors.Count > 0)
        {
            throw new TemplateDataValidationException(allErrors);
        }
    }

    /// <summary>
    /// Rewrites the server's <c>template-data-invalid</c> problem into the exception a caller of this
    /// class is already catching. Every other problem propagates untouched — this class narrows one
    /// failure mode, it does not swallow failures.
    /// </summary>
    private static T TranslatingProblem<T>(Func<T> call)
    {
        try
        {
            return call();
        }
        catch (ProblemDetailException e) when (e.TypeSlug == KnownProblemSlugs.TEMPLATE_DATA_INVALID)
        {
            throw new TemplateDataValidationException(
                TemplateDataProblems.ToValidationErrors(null, e.MissingFields, e.InvalidFields));
        }
    }
}
