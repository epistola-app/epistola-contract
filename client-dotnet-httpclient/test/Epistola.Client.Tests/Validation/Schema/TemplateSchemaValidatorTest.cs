using System.Collections.Generic;
using Epistola.Client.Api;
using Epistola.Client.Model;
using Epistola.Client.Validation.Schema;
using NSubstitute;
using Xunit;

namespace Epistola.Client.Tests.Validation.Schema;

public class TemplateSchemaValidatorTest
{
    private static readonly Dictionary<string, object> Schema = new()
    {
        ["type"] = "object",
        ["required"] = new List<object> { "name" },
        ["properties"] = new Dictionary<string, object>
        {
            ["name"] = new Dictionary<string, object> { ["type"] = "string" },
            ["age"] = new Dictionary<string, object> { ["type"] = "integer" },
        },
    };

    private static ITemplatesApi ApiReturningSchema(object? schema)
    {
        var api = Substitute.For<ITemplatesApi>();
        api.GetTemplate("acme", "default", "person").Returns(new TemplateDto(id: "person", tenantId: "acme", name: "Person", schema: schema, variants: new List<VariantSummaryDto>()));
        return api;
    }

    [Fact]
    public void ValidDataPasses()
    {
        var validator = new TemplateSchemaValidator(ApiReturningSchema(Schema));
        validator.Validate("acme", "default", "person", new Dictionary<string, object> { ["name"] = "Jane", ["age"] = 30 });
    }

    [Fact]
    public void MissingRequiredFieldThrows()
    {
        var validator = new TemplateSchemaValidator(ApiReturningSchema(Schema));
        var ex = Assert.Throws<TemplateDataValidationException>(() =>
            validator.Validate("acme", "default", "person", new Dictionary<string, object> { ["age"] = 30 }));
        Assert.NotEmpty(ex.Errors);
    }

    [Fact]
    public void WrongTypeThrows()
    {
        var validator = new TemplateSchemaValidator(ApiReturningSchema(Schema));
        Assert.Throws<TemplateDataValidationException>(() =>
            validator.Validate("acme", "default", "person", new Dictionary<string, object> { ["name"] = 123 }));
    }

    [Fact]
    public void NoSchemaIsNoOp()
    {
        var validator = new TemplateSchemaValidator(ApiReturningSchema(null));
        validator.Validate("acme", "default", "person", new Dictionary<string, object> { ["anything"] = true });
    }

    [Fact]
    public void SchemaIsFetchedOnceAndCached()
    {
        var api = ApiReturningSchema(Schema);
        var validator = new TemplateSchemaValidator(api);
        validator.Validate("acme", "default", "person", new Dictionary<string, object> { ["name"] = "A" });
        validator.Validate("acme", "default", "person", new Dictionary<string, object> { ["name"] = "B" });
        api.Received(1).GetTemplate("acme", "default", "person");
    }
}
