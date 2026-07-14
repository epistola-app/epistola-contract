using System.Collections.Generic;
using Epistola.Client.Api;
using Epistola.Client.Model;
using Epistola.Client.Validation.Schema;
using NSubstitute;
using Xunit;

namespace Epistola.Client.Tests.Validation.Schema;

public class ValidatingGenerationApiTest
{
    private static readonly Dictionary<string, object> Schema = new()
    {
        ["type"] = "object",
        ["required"] = new List<object> { "name" },
        ["properties"] = new Dictionary<string, object>
        {
            ["name"] = new Dictionary<string, object> { ["type"] = "string" },
        },
    };

    private static (ValidatingGenerationApi Api, IGenerationApi Delegate) Build()
    {
        var templates = Substitute.For<ITemplatesApi>();
        templates.GetTemplate("acme", "default", "invoice").Returns(new TemplateDto(id: "invoice", tenantId: "acme", name: "Invoice", schema: Schema, variants: new List<VariantSummaryDto>()));
        var generation = Substitute.For<IGenerationApi>();
        generation.GenerateDocument("acme", Arg.Any<GenerateDocumentRequest>()).Returns(new GenerationJobResponse());
        generation.GenerateDocumentBatch("acme", Arg.Any<GenerateBatchRequest>()).Returns(new GenerationJobResponse());
        return (new ValidatingGenerationApi(generation, templates), generation);
    }

    [Fact]
    public void ValidSingleRequestIsForwarded()
    {
        var (api, del) = Build();
        var request = new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "en",
            data: new Dictionary<string, object> { ["name"] = "Jane" });

        api.GenerateDocument("acme", request);
        del.Received(1).GenerateDocument("acme", request);
    }

    [Fact]
    public void InvalidSingleRequestIsRejectedBeforeSending()
    {
        var (api, del) = Build();
        var request = new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "en",
            data: new Dictionary<string, object>());

        Assert.Throws<TemplateDataValidationException>(() => api.GenerateDocument("acme", request));
        del.DidNotReceive().GenerateDocument(Arg.Any<string>(), Arg.Any<GenerateDocumentRequest>());
    }

    [Fact]
    public void BatchCollectsErrorsWithItemIndexPrefix()
    {
        var (api, _) = Build();
        var request = new GenerateBatchRequest(items: new List<BatchGenerationItem>
        {
            new(catalogId: "default", templateId: "invoice", variantId: "en", data: new Dictionary<string, object> { ["name"] = "ok" }),
            new(catalogId: "default", templateId: "invoice", variantId: "en", data: new Dictionary<string, object>()),
        });

        var ex = Assert.Throws<TemplateDataValidationException>(() => api.GenerateDocumentBatch("acme", request));
        Assert.Contains(ex.Errors, e => e.Path.StartsWith("items[1]"));
    }
}
