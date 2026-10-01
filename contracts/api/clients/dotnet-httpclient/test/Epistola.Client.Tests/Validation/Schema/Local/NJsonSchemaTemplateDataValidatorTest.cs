// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Collections.Generic;
using System.Linq;
using Epistola.Client.Api;
using Epistola.Client.Model;
using Epistola.Client.Validation.Schema;
using NJsonSchema.Validation;
using NSubstitute;
using Xunit;

namespace Epistola.Client.Tests.Validation.Schema.Local;

/// <summary>
/// The reference local adapter, held to the contract <see cref="ITemplateDataValidator"/> states —
/// above all that <c>Path</c> is a JSON Pointer and <c>Keyword</c> a JSON Schema keyword.
///
/// This is what makes the interface's promise testable rather than aspirational: a real library,
/// with its own vocabulary for both, converted to the one shape callers read.
/// </summary>
public class NJsonSchemaTemplateDataValidatorTest
{
    private static readonly Dictionary<string, object> InvoiceSchema = new()
    {
        ["$schema"] = "https://json-schema.org/draft/2020-12/schema",
        ["type"] = "object",
        ["required"] = new List<object> { "customer", "invoiceNumber" },
        ["properties"] = new Dictionary<string, object>
        {
            ["customer"] = new Dictionary<string, object>
            {
                ["type"] = "object",
                ["required"] = new List<object> { "name" },
                ["properties"] = new Dictionary<string, object>
                {
                    ["name"] = new Dictionary<string, object> { ["type"] = "string", ["minLength"] = 1 },
                },
            },
            ["invoiceNumber"] = new Dictionary<string, object> { ["type"] = "string" },
            ["lineItems"] = new Dictionary<string, object>
            {
                ["type"] = "array",
                ["items"] = new Dictionary<string, object>
                {
                    ["type"] = "object",
                    ["properties"] = new Dictionary<string, object>
                    {
                        ["quantity"] = new Dictionary<string, object> { ["type"] = "integer", ["minimum"] = 1 },
                    },
                },
            },
        },
    };

    private static readonly Dictionary<string, object> ValidData = new()
    {
        ["customer"] = new Dictionary<string, object> { ["name"] = "Jane Smith" },
        ["invoiceNumber"] = "INV-2026-001",
        ["lineItems"] = new List<object> { new Dictionary<string, object> { ["quantity"] = 3 } },
    };

    private static ITemplatesApi ApiReturningSchema(object? schema)
    {
        var api = Substitute.For<ITemplatesApi>();
        api.GetTemplate("acme", "default", "invoice").Returns(new TemplateDto(
            slug: "invoice", id: "invoice", tenantId: "acme", name: "Invoice",
            schema: schema, variants: new List<VariantSummaryDto>()));
        return api;
    }

    [Fact]
    public void ValidDataYieldsNoFindings()
    {
        var validator = new NJsonSchemaTemplateDataValidator(ApiReturningSchema(InvoiceSchema));

        Assert.Empty(validator.Validate("acme", "default", "invoice", ValidData));
    }

    [Fact]
    public void PathsAreJsonPointersNotNJsonSchemaFragments()
    {
        var validator = new NJsonSchemaTemplateDataValidator(ApiReturningSchema(InvoiceSchema));
        var data = new Dictionary<string, object>(ValidData)
        {
            ["customer"] = new Dictionary<string, object> { ["name"] = "" },
        };

        var findings = validator.Validate("acme", "default", "invoice", data);

        // The regression this guards: NJsonSchema reports `#/customer/name`.
        Assert.Equal(new[] { "/customer/name" }, findings.Select(f => f.Path));
        Assert.DoesNotContain(findings, f => f.Path.StartsWith("#"));
    }

    [Fact]
    public void KeywordsAreJsonSchemaKeywordsNotTheLibrarysErrorKinds()
    {
        var validator = new NJsonSchemaTemplateDataValidator(ApiReturningSchema(InvoiceSchema));
        var data = new Dictionary<string, object>(ValidData)
        {
            ["invoiceNumber"] = 42,
        };

        var findings = validator.Validate("acme", "default", "invoice", data);

        // NJsonSchema calls this `StringExpected`; the contract's vocabulary is `type`.
        Assert.Equal(new[] { "type" }, findings.Select(f => f.Keyword));
    }

    [Fact]
    public void AMissingRequiredFieldUsesTheRequiredKeyword()
    {
        var validator = new NJsonSchemaTemplateDataValidator(ApiReturningSchema(InvoiceSchema));

        var findings = validator.Validate("acme", "default", "invoice",
            new Dictionary<string, object> { ["invoiceNumber"] = "INV-2026-001" });

        Assert.Equal(new[] { "required" }, findings.Select(f => f.Keyword));
    }

    [Fact]
    public void ATemplateWithoutASchemaClaimsNothingAboutTheData()
    {
        var validator = new NJsonSchemaTemplateDataValidator(ApiReturningSchema(null));

        Assert.Empty(validator.Validate("acme", "default", "invoice", new Dictionary<string, object> { ["anything"] = 1 }));
    }

    [Fact]
    public void TheSchemaIsFetchedOnceAndReused()
    {
        var api = ApiReturningSchema(InvoiceSchema);
        var validator = new NJsonSchemaTemplateDataValidator(api);

        validator.Validate("acme", "default", "invoice", ValidData);
        validator.Validate("acme", "default", "invoice", ValidData);

        // Without the cache a "local" validator costs a round trip per call, which is worse than
        // asking the server to validate outright.
        api.Received(1).GetTemplate("acme", "default", "invoice");
    }

    [Fact]
    public void ItTakesTheGenerationPreflight()
    {
        ITemplateDataValidator validator = new NJsonSchemaTemplateDataValidator(ApiReturningSchema(InvoiceSchema));

        Assert.True(validator.PreflightsGeneration);
    }

    [Fact]
    public void APointerIntoAnArrayUsesTheIndexAsASegment()
    {
        var validator = new NJsonSchemaTemplateDataValidator(ApiReturningSchema(InvoiceSchema));
        var data = new Dictionary<string, object>(ValidData)
        {
            ["lineItems"] = new List<object> { new Dictionary<string, object> { ["quantity"] = 0 } },
        };

        var findings = validator.Validate("acme", "default", "invoice", data);

        // NJsonSchema reports `#/lineItems[0].quantity`.
        Assert.Equal(new[] { "/lineItems/0/quantity" }, findings.Select(f => f.Path));
        Assert.Equal(new[] { "minimum" }, findings.Select(f => f.Keyword));
    }

    [Fact]
    public void TheRootPointerIsTheEmptyString()
    {
        Assert.Equal(string.Empty, NJsonSchemaTemplateDataValidator.ToPointer("#"));
        Assert.Equal(string.Empty, NJsonSchemaTemplateDataValidator.ToPointer("#/"));
        Assert.Equal(string.Empty, NJsonSchemaTemplateDataValidator.ToPointer(null));
    }

    [Fact]
    public void SegmentsAreRejoinedAsPointerSegments()
    {
        Assert.Equal("/customer/name", NJsonSchemaTemplateDataValidator.ToPointer("#/customer.name"));
        Assert.Equal("/lineItems/0/quantity", NJsonSchemaTemplateDataValidator.ToPointer("#/lineItems[0].quantity"));
        Assert.Equal("/matrix/0/1", NJsonSchemaTemplateDataValidator.ToPointer("#/matrix[0][1]"));
    }

    [Fact]
    public void AnUnmappedKindFallsBackToItsOwnName()
    {
        // More useful than nothing, and loud enough to notice if a contract starts producing it.
        Assert.Equal("required", NJsonSchemaTemplateDataValidator.ToKeyword(ValidationErrorKind.PropertyRequired));
        Assert.Equal("type", NJsonSchemaTemplateDataValidator.ToKeyword(ValidationErrorKind.StringExpected));
        Assert.Equal(
            ValidationErrorKind.NotAllOf.ToString(),
            NJsonSchemaTemplateDataValidator.ToKeyword(ValidationErrorKind.NotAllOf));
    }
}
