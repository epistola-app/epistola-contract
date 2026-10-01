// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Collections.Generic;
using System.Linq;
using Epistola.Client.Api;
using Epistola.Client.Model;
using Epistola.Client.Validation.Schema;
using NSubstitute;
using Xunit;

namespace Epistola.Client.Tests.Validation.Schema;

/// <summary>
/// <see cref="TemplateSchemaValidator"/> over the shipped default,
/// <see cref="ServerTemplateDataValidator"/>.
///
/// The behaviour worth pinning is the mapping, not the HTTP: the server answers in three members of
/// one result and the client owes its callers a single shape, documented on
/// <see cref="ITemplateDataValidator"/>.
/// </summary>
public class TemplateSchemaValidatorTest
{
    private static ITemplatesApi ApiAnswering(TemplateDataValidationResult result)
    {
        var api = Substitute.For<ITemplatesApi>();
        api.ValidateTemplateData("acme", "default", "person", Arg.Any<ValidateTemplateDataRequest>()).Returns(result);
        return api;
    }

    private static TemplateDataValidationResult Result(
        bool valid,
        List<TemplateDataValidationError>? errors = null,
        List<MissingDataField>? missingFields = null,
        List<InvalidDataField>? invalidFields = null) =>
        new(valid: valid, errors: errors, missingFields: missingFields, invalidFields: invalidFields);

    [Fact]
    public void ValidDataPassesWithoutThrowing()
    {
        var api = ApiAnswering(Result(valid: true));

        new TemplateSchemaValidator(api).Validate("acme", "default", "person", new Dictionary<string, object> { ["name"] = "Jane" });

        api.Received(1).ValidateTemplateData("acme", "default", "person", Arg.Any<ValidateTemplateDataRequest>());
    }

    [Fact]
    public void TheDataIsSentAsTheRequestBody()
    {
        var api = ApiAnswering(Result(valid: true));
        var data = new Dictionary<string, object> { ["name"] = "Jane" };

        new TemplateSchemaValidator(api).Validate("acme", "default", "person", data);

        api.Received(1).ValidateTemplateData("acme", "default", "person", Arg.Is<ValidateTemplateDataRequest>(r => ReferenceEquals(r.Data, data)));
    }

    [Fact]
    public void InvalidFieldsBecomeErrorsKeyedByTheirJsonPointer()
    {
        var api = ApiAnswering(Result(valid: false, invalidFields: new List<InvalidDataField>
        {
            new(path: "/customer/email", keyword: "format", message: "must be a valid email address"),
            new(path: "/lineItems/0/quantity", keyword: "minimum", message: "must be at least 1"),
        }));

        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new TemplateSchemaValidator(api).Validate("acme", "default", "person", new Dictionary<string, object>()));

        Assert.Equal(new[] { "/customer/email", "/lineItems/0/quantity" }, thrown.Errors.Select(e => e.Path));
        Assert.Equal(new[] { "format", "minimum" }, thrown.Errors.Select(e => e.Keyword));
    }

    [Fact]
    public void AMissingRequiredFieldIsAnErrorAndAMissingOptionalFieldIsNot()
    {
        var api = ApiAnswering(Result(valid: false, missingFields: new List<MissingDataField>
        {
            new(path: "/customer/address", required: true, schema: new Dictionary<string, object> { ["type"] = "object" }),
            new(path: "/customer/phone", required: false, schema: new Dictionary<string, object> { ["type"] = "string" }),
        }));

        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new TemplateSchemaValidator(api).Validate("acme", "default", "person", new Dictionary<string, object>()));

        Assert.Equal(new[] { "/customer/address" }, thrown.Errors.Select(e => e.Path));
        Assert.Equal("required", thrown.Errors[0].Keyword);
    }

    [Fact]
    public void ErrorsIsUsedWhenTheServerSendsNoFieldMembers()
    {
        var api = ApiAnswering(Result(valid: false, errors: new List<TemplateDataValidationError>
        {
            new(path: "/invoiceNumber", message: "does not match the required format", keyword: "pattern"),
        }));

        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new TemplateSchemaValidator(api).Validate("acme", "default", "person", new Dictionary<string, object>()));

        Assert.Equal("/invoiceNumber", thrown.Errors[0].Path);
        Assert.Equal("pattern", thrown.Errors[0].Keyword);
    }

    [Fact]
    public void TheFieldMembersWinOverErrors()
    {
        // `errors[].path` documents itself as a JSON Pointer but is specified with a JSONPath
        // example, so preferring invalidFields keeps the interface's promise from depending on
        // which member the server happened to fill.
        var api = ApiAnswering(Result(
            valid: false,
            errors: new List<TemplateDataValidationError> { new(path: "$.customer.email", message: "must be a valid email address", keyword: "format") },
            invalidFields: new List<InvalidDataField> { new(path: "/customer/email", keyword: "format", message: "must be a valid email address") }));

        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new TemplateSchemaValidator(api).Validate("acme", "default", "person", new Dictionary<string, object>()));

        Assert.Equal(new[] { "/customer/email" }, thrown.Errors.Select(e => e.Path));
    }

    [Fact]
    public void AnInvalidResultWithNothingToReportStillThrows()
    {
        // `valid: false` is the verdict, and reporting no reason must not become "fine".
        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new TemplateSchemaValidator(ApiAnswering(Result(valid: false)))
                .Validate("acme", "default", "person", new Dictionary<string, object>()));

        Assert.Single(thrown.Errors);
        Assert.Equal(string.Empty, thrown.Errors[0].Path);
    }

    [Fact]
    public void TheVersionSelectorsArePassedThroughWhenConfigured()
    {
        var api = ApiAnswering(Result(valid: true));

        new TemplateSchemaValidator(new ServerTemplateDataValidator(api, variantId: "nl-nl", environmentId: "production"))
            .Validate("acme", "default", "person", new Dictionary<string, object>());

        api.Received(1).ValidateTemplateData("acme", "default", "person",
            Arg.Is<ValidateTemplateDataRequest>(r => r.VariantId == "nl-nl" && r.EnvironmentId == "production"));
    }

    [Fact]
    public void APluggedInValidatorReplacesTheServerEntirely()
    {
        var api = ApiAnswering(Result(valid: true));
        var findings = new List<TemplateDataValidationException.ValidationError>
        {
            new("/name", "is required but was not supplied", "required"),
        };

        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new TemplateSchemaValidator(new StubValidator(findings))
                .Validate("acme", "default", "person", new Dictionary<string, object>()));

        Assert.Equal(findings, thrown.Errors);
        api.DidNotReceive().ValidateTemplateData(Arg.Any<string>(), Arg.Any<string>(), Arg.Any<string>(), Arg.Any<ValidateTemplateDataRequest>());
    }

    [Fact]
    public void TheServerBackedValidatorDeclinesGenerationPreflight()
    {
        Assert.False(new ServerTemplateDataValidator(ApiAnswering(Result(valid: true))).PreflightsGeneration);
        // Through the interface, not the class: in C# a default interface member is not inherited
        // into the implementing type's surface, so an implementation that wants the default says
        // nothing and callers reach it via ITemplateDataValidator.
        ITemplateDataValidator local = new StubValidator(new List<TemplateDataValidationException.ValidationError>());
        Assert.True(local.PreflightsGeneration);
    }

    /// <summary>A validator that answers in-process, so it takes the pre-flight.</summary>
    internal sealed class StubValidator : ITemplateDataValidator
    {
        private readonly IReadOnlyList<TemplateDataValidationException.ValidationError> _findings;

        public StubValidator(IReadOnlyList<TemplateDataValidationException.ValidationError> findings)
        {
            _findings = findings;
        }

        public int Calls { get; private set; }

        public IReadOnlyList<TemplateDataValidationException.ValidationError> Validate(
            string tenantId, string catalogId, string templateId, object data)
        {
            Calls++;
            return _findings;
        }
    }
}
