using System.Collections.Generic;
using System.Linq;
using Epistola.Client.Api;
using Epistola.Client.Client;
using Epistola.Client.Model;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// A wrapper around <see cref="IGenerationApi"/> that validates request data against the template's
/// JSON Schema before sending it to the server.
///
/// For single-document requests, validation errors are thrown immediately. For batch requests, all
/// items are validated and errors are collected into a single <see cref="TemplateDataValidationException"/>.
///
/// <code>
/// var validating = new ValidatingGenerationApi(generationApi, templatesApi);
/// validating.GenerateDocument("my-tenant", request);   // validates before calling the server
/// </code>
/// </summary>
public sealed class ValidatingGenerationApi
{
    private readonly IGenerationApi _delegate;
    private readonly TemplateSchemaValidator _validator;

    public ValidatingGenerationApi(IGenerationApi generationApi, ITemplatesApi templatesApi, ISchemaCache? cache = null)
    {
        _delegate = generationApi;
        _validator = new TemplateSchemaValidator(templatesApi, cache ?? new TtlSchemaCache());
    }

    public GenerationJobResponse GenerateDocument(string tenantId, GenerateDocumentRequest request)
    {
        _validator.Validate(tenantId, request.CatalogId, request.TemplateId, request.Data);
        return _delegate.GenerateDocument(tenantId, request);
    }

    public ApiResponse<GenerationJobResponse> GenerateDocumentWithHttpInfo(string tenantId, GenerateDocumentRequest request)
    {
        _validator.Validate(tenantId, request.CatalogId, request.TemplateId, request.Data);
        return _delegate.GenerateDocumentWithHttpInfo(tenantId, request);
    }

    public GenerationJobResponse GenerateDocumentBatch(string tenantId, GenerateBatchRequest request)
    {
        ValidateBatch(tenantId, request);
        return _delegate.GenerateDocumentBatch(tenantId, request);
    }

    public ApiResponse<GenerationJobResponse> GenerateDocumentBatchWithHttpInfo(string tenantId, GenerateBatchRequest request)
    {
        ValidateBatch(tenantId, request);
        return _delegate.GenerateDocumentBatchWithHttpInfo(tenantId, request);
    }

    private void ValidateBatch(string tenantId, GenerateBatchRequest request)
    {
        var allErrors = new List<TemplateDataValidationException.ValidationError>();
        for (var index = 0; index < request.Items.Count; index++)
        {
            var item = request.Items[index];
            try
            {
                _validator.Validate(tenantId, item.CatalogId, item.TemplateId, item.Data);
            }
            catch (TemplateDataValidationException e)
            {
                allErrors.AddRange(e.Errors.Select(error => error with { Path = $"items[{index}]{error.Path}" }));
            }
        }

        if (allErrors.Count > 0)
        {
            throw new TemplateDataValidationException(allErrors);
        }
    }
}
