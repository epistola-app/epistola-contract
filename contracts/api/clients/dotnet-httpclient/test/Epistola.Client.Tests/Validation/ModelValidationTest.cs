using System;
using System.Collections.Generic;
using System.Linq;
using Epistola.Client.Model;
using Epistola.Client.Validation;
using Xunit;

namespace Epistola.Client.Tests.Validation;

/// <summary>
/// Exercises the build-time generated <c>Validate()</c> extension methods (ModelValidation.g.cs),
/// mirroring the Kotlin ModelValidationTest.
/// </summary>
public class ModelValidationTest
{
    [Theory]
    [InlineData("abc")]
    [InlineData("a1b")]
    [InlineData("test-env")]
    [InlineData("my-long-slug-123")]
    public void SlugPatternAcceptsValidSlugs(string slug)
    {
        var request = new CreateTenantRequest(id: slug, name: "Test");
        Assert.Same(request, request.Validate());
    }

    [Theory]
    [InlineData("ABC")]
    [InlineData("1abc")]
    [InlineData("-abc")]
    [InlineData("abc-")]
    [InlineData("ab--c")]
    [InlineData("ab c")]
    [InlineData("ab.c")]
    public void SlugPatternRejectsInvalidSlugs(string slug)
    {
        Assert.Throws<ArgumentException>(() => new CreateTenantRequest(id: slug, name: "Test").Validate());
    }

    [Fact]
    public void IdAtExactMinimumLengthPasses()
    {
        var request = new CreateTenantRequest(id: "abc", name: "Tenant");
        Assert.Same(request, request.Validate());
    }

    [Fact]
    public void IdOneBelowMinimumLengthFails()
    {
        var ex = Assert.Throws<ArgumentException>(() => new CreateTenantRequest(id: "ab", name: "Tenant").Validate());
        Assert.Contains("id: length must be between 3 and 63", ex.Message);
    }

    [Fact]
    public void IdAtExactMaximumLengthPasses()
    {
        var id = "a" + new string('b', 62);
        var request = new CreateTenantRequest(id: id, name: "Tenant");
        Assert.Same(request, request.Validate());
    }

    [Fact]
    public void NameBoundaryValues()
    {
        new CreateTenantRequest(id: "abc", name: "x").Validate();
        new CreateTenantRequest(id: "abc", name: new string('x', 255)).Validate();
        Assert.Throws<ArgumentException>(() => new CreateTenantRequest(id: "abc", name: "").Validate());
        Assert.Throws<ArgumentException>(() => new CreateTenantRequest(id: "abc", name: new string('x', 256)).Validate());
    }

    [Fact]
    public void UpdateTenantRequestWithNullNameSkipsValidation()
    {
        var request = new UpdateTenantRequest(name: null);
        Assert.Same(request, request.Validate());
    }

    [Fact]
    public void UpdateTenantRequestWithEmptyNameFails()
    {
        Assert.Throws<ArgumentException>(() => new UpdateTenantRequest(name: "").Validate());
    }

    [Fact]
    public void CreateEnvironmentRequestEnforcesIdMaxLengthOf30()
    {
        var valid = new CreateEnvironmentRequest(id: "a" + new string('b', 29), name: "Env");
        Assert.Same(valid, valid.Validate());
        var ex = Assert.Throws<ArgumentException>(() => new CreateEnvironmentRequest(id: new string('a', 31), name: "Env").Validate());
        Assert.Contains("id: length must be between 3 and 30", ex.Message);
    }

    [Fact]
    public void GenerateDocumentRequestValidatesNullableFieldsIndependently()
    {
        new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "english", data: new Dictionary<string, object>(), versionId: null).Validate();
        Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "english", data: new Dictionary<string, object>(), versionId: 300).Validate());

        new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "english", data: new Dictionary<string, object>(), environmentId: null).Validate();
        Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "english", data: new Dictionary<string, object>(), environmentId: "PROD").Validate());

        new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "english", data: new Dictionary<string, object>(), filename: new string('x', 255)).Validate();
        var ex = Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(catalogId: "default", templateId: "invoice", variantId: "english", data: new Dictionary<string, object>(), filename: new string('x', 256)).Validate());
        Assert.Contains("filename: length must be at most 255", ex.Message);
    }

    [Fact]
    public void GenerateBatchRequestWithEmptyItemsFails()
    {
        var ex = Assert.Throws<ArgumentException>(() => new GenerateBatchRequest(items: new List<BatchGenerationItem>()).Validate());
        Assert.Contains("items: must have at least 1 item(s)", ex.Message);
    }

    [Fact]
    public void GenerateBatchRequestWithItemsPasses()
    {
        var items = Enumerable.Range(1, 5).Select(i => new BatchGenerationItem(
            catalogId: "default", templateId: "invoice", variantId: "english",
            data: new Dictionary<string, object> { ["n"] = i })).ToList();
        var request = new GenerateBatchRequest(items: items);
        Assert.Same(request, request.Validate());
    }

    [Fact]
    public void ValidateReturnsSameInstanceForFluentChaining()
    {
        var request = new CreateTenantRequest(id: "acme-corp", name: "Acme Corporation");
        Assert.Same(request, request.Validate());
    }
}
