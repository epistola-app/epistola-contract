// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Generic;
using System.Linq;
using Epistola.Client.Api;
using Newtonsoft.Json;
using NJsonSchema;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Validates template data against the JSON Schema defined on the template.
///
/// Fetches the template from the server on first use and caches the compiled schema.
///
/// <code>
/// var validator = new TemplateSchemaValidator(templatesApi);
/// validator.Validate("my-tenant", "my-catalog", "my-template", myData);
/// </code>
/// </summary>
public sealed class TemplateSchemaValidator
{
    private readonly ITemplatesApi _templatesApi;
    private readonly ISchemaCache _cache;

    /// <param name="templatesApi">The generated <see cref="ITemplatesApi"/> used to fetch template metadata.</param>
    /// <param name="cache">Schema cache. Defaults to <see cref="TtlSchemaCache"/> with a 5-minute TTL.</param>
    public TemplateSchemaValidator(ITemplatesApi templatesApi, ISchemaCache? cache = null)
    {
        _templatesApi = templatesApi;
        _cache = cache ?? new TtlSchemaCache();
    }

    /// <summary>
    /// Validates <paramref name="data"/> against the schema of the specified template.
    /// No-op when the template has no schema.
    /// </summary>
    /// <exception cref="TemplateDataValidationException">If validation fails.</exception>
    public void Validate(string tenantId, string catalogId, string templateId, object data)
    {
        var schema = _cache.GetOrLoad(tenantId, catalogId, templateId, () => LoadSchema(tenantId, catalogId, templateId));
        if (schema == null)
        {
            return; // No schema defined on the template — nothing to validate.
        }

        var dataJson = JsonConvert.SerializeObject(data);
        var messages = schema.Validate(dataJson);

        if (messages.Count > 0)
        {
            var errors = messages
                .Select(m => new TemplateDataValidationException.ValidationError(
                    m.Path ?? string.Empty,
                    m.ToString(),
                    m.Kind.ToString()))
                .ToList();
            throw new TemplateDataValidationException(errors);
        }
    }

    private JsonSchema? LoadSchema(string tenantId, string catalogId, string templateId)
    {
        var template = _templatesApi.GetTemplate(tenantId, catalogId, templateId);
        if (template.Schema == null)
        {
            return null;
        }

        var schemaJson = JsonConvert.SerializeObject(template.Schema);
        return JsonSchema.FromJsonAsync(schemaJson).GetAwaiter().GetResult();
    }
}
