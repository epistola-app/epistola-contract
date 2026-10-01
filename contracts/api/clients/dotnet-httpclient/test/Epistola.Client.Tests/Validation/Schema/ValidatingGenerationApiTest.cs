// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Collections.Generic;
using System.Linq;
using System.Net;
using Epistola.Client.Api;
using Epistola.Client.Error;
using Epistola.Client.Model;
using Epistola.Client.Validation.Schema;
using NSubstitute;
using NSubstitute.ExceptionExtensions;
using Xunit;

namespace Epistola.Client.Tests.Validation.Schema;

/// <summary>
/// <see cref="ValidatingGenerationApi"/> reaches one exception type by two routes, and which route
/// applies is the validator's call. Both are pinned here, including that the server-backed default
/// spends no extra request to reach it.
/// </summary>
public class ValidatingGenerationApiTest
{
    private static readonly GenerateDocumentRequest Request =
        new(catalogId: "default", templateId: "invoice", data: new Dictionary<string, object>());

    private static readonly GenerateBatchRequest Batch = new(items: new List<BatchGenerationItem>
    {
        new(catalogId: "default", templateId: "invoice", data: new Dictionary<string, object>()),
        new(catalogId: "default", templateId: "reminder", data: new Dictionary<string, object>()),
    });

    private static ITemplatesApi RefusingTemplatesApi()
    {
        var api = Substitute.For<ITemplatesApi>();
        api.ValidateTemplateData(Arg.Any<string>(), Arg.Any<string>(), Arg.Any<string>(), Arg.Any<ValidateTemplateDataRequest>())
            .Returns(new TemplateDataValidationResult(valid: true));
        return api;
    }

    private static ProblemDetailException TemplateDataInvalid(
        List<MissingDataField>? missingFields = null,
        List<InvalidDataField>? invalidFields = null) =>
        new(
            new ProblemDetail(
                type: "https://epistola.app/errors/template-data-invalid",
                title: "Template data invalid",
                status: 400,
                detail: "The supplied data does not fit the template's data contract"),
            new List<ValidationError>(),
            new Dictionary<string, List<DataModelValidationError>>(),
            HttpStatusCode.BadRequest,
            "{}",
            headers: null,
            missingFields: missingFields ?? new List<MissingDataField>(),
            invalidFields: invalidFields ?? new List<InvalidDataField>());

    [Fact]
    public void TheDefaultValidatorSubmitsWithoutAPreflightRequest()
    {
        var templates = RefusingTemplatesApi();
        var generation = Substitute.For<IGenerationApi>();

        new ValidatingGenerationApi(generation, templates).GenerateDocument("acme", Request);

        // The server validates what it is given, so asking it first would be a second round trip
        // for the same verdict.
        templates.DidNotReceive().ValidateTemplateData(Arg.Any<string>(), Arg.Any<string>(), Arg.Any<string>(), Arg.Any<ValidateTemplateDataRequest>());
        generation.Received(1).GenerateDocument("acme", Request);
    }

    [Fact]
    public void ARejectedSubmissionBecomesATemplateDataValidationException()
    {
        var generation = Substitute.For<IGenerationApi>();
        generation.GenerateDocument("acme", Request).Throws(TemplateDataInvalid(
            missingFields: new List<MissingDataField>
            {
                new(path: "/invoiceNumber", required: true, schema: new Dictionary<string, object>()),
                new(path: "/customer/phone", required: false, schema: new Dictionary<string, object>()),
            },
            invalidFields: new List<InvalidDataField>
            {
                new(path: "/customer/email", keyword: "format", message: "must be a valid email address"),
            }));

        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new ValidatingGenerationApi(generation, RefusingTemplatesApi()).GenerateDocument("acme", Request));

        // The optional missing field is not a finding; the required one is.
        Assert.Equal(new[] { "/customer/email", "/invoiceNumber" }, thrown.Errors.Select(e => e.Path));
        Assert.Equal(new[] { "format", "required" }, thrown.Errors.Select(e => e.Keyword));
    }

    [Fact]
    public void AnyOtherProblemPropagatesUntouched()
    {
        var notFound = new ProblemDetailException(
            new ProblemDetail(type: "https://epistola.app/errors/not-found", title: "Not Found", status: 404),
            new List<ValidationError>(),
            new Dictionary<string, List<DataModelValidationError>>(),
            HttpStatusCode.NotFound,
            "{}",
            headers: null);
        var generation = Substitute.For<IGenerationApi>();
        generation.GenerateDocument("acme", Request).Throws(notFound);

        var thrown = Assert.Throws<ProblemDetailException>(
            () => new ValidatingGenerationApi(generation, RefusingTemplatesApi()).GenerateDocument("acme", Request));

        Assert.Same(notFound, thrown);
    }

    [Fact]
    public void ALocalValidatorThrowsBeforeTheRequestIsSent()
    {
        var generation = Substitute.For<IGenerationApi>();
        var local = new TemplateSchemaValidatorTest.StubValidator(new List<TemplateDataValidationException.ValidationError>
        {
            new("/name", "is required but was not supplied", "required"),
        });

        Assert.Throws<TemplateDataValidationException>(
            () => new ValidatingGenerationApi(generation, local).GenerateDocument("acme", Request));

        generation.DidNotReceive().GenerateDocument(Arg.Any<string>(), Arg.Any<GenerateDocumentRequest>());
    }

    [Fact]
    public void ALocalValidatorReportsEveryItemOfABatchAtOncePrefixedByItsIndex()
    {
        var generation = Substitute.For<IGenerationApi>();
        var local = new TemplateSchemaValidatorTest.StubValidator(new List<TemplateDataValidationException.ValidationError>
        {
            new("/name", "is required but was not supplied", "required"),
        });

        var thrown = Assert.Throws<TemplateDataValidationException>(
            () => new ValidatingGenerationApi(generation, local).GenerateDocumentBatch("acme", Batch));

        Assert.Equal(new[] { "items[0]/name", "items[1]/name" }, thrown.Errors.Select(e => e.Path));
        generation.DidNotReceive().GenerateDocumentBatch(Arg.Any<string>(), Arg.Any<GenerateBatchRequest>());
    }

    [Fact]
    public void ALocalValidatorThatFindsNothingLetsTheRequestThrough()
    {
        var generation = Substitute.For<IGenerationApi>();
        var local = new TemplateSchemaValidatorTest.StubValidator(new List<TemplateDataValidationException.ValidationError>());

        new ValidatingGenerationApi(generation, local).GenerateDocumentBatch("acme", Batch);

        generation.Received(1).GenerateDocumentBatch("acme", Batch);
        Assert.Equal(2, local.Calls);
    }

    [Fact]
    public void TheDefaultValidatorPreflightsNothingPerBatchItemEither()
    {
        var templates = RefusingTemplatesApi();
        var generation = Substitute.For<IGenerationApi>();

        new ValidatingGenerationApi(generation, templates).GenerateDocumentBatch("acme", Batch);

        templates.DidNotReceive().ValidateTemplateData(Arg.Any<string>(), Arg.Any<string>(), Arg.Any<string>(), Arg.Any<ValidateTemplateDataRequest>());
        generation.Received(1).GenerateDocumentBatch("acme", Batch);
    }
}
