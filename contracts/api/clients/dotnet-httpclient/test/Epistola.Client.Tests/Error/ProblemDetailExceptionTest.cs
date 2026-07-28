using System.Collections.Generic;
using System.Net;
using Epistola.Client.Client;
using Epistola.Client.Error;
using Epistola.Client.Model;
using Xunit;

namespace Epistola.Client.Tests.Error;

public class ProblemDetailExceptionTest
{
    private static ProblemDetailException Exception(
        ProblemDetail problem,
        IReadOnlyList<ValidationError>? errors = null,
        IReadOnlyDictionary<string, List<DataModelValidationError>>? validationErrors = null,
        string body = "{}")
        => new(
            problem,
            errors ?? new List<ValidationError>(),
            validationErrors ?? new Dictionary<string, List<DataModelValidationError>>(),
            (HttpStatusCode)problem.Status,
            body,
            headers: null);

    [Fact]
    public void TypeSlugStripsTheEpistolaTypeBase()
    {
        var ex = Exception(new ProblemDetail(
            type: "https://epistola.app/errors/not-found",
            title: "Not Found",
            status: 404,
            detail: "Tenant 'acme' was not found"));

        Assert.Equal("not-found", ex.TypeSlug);
        Assert.Equal("Not Found", ex.Title);
        Assert.Equal(404, ex.ProblemStatus);
        Assert.Equal("Tenant 'acme' was not found", ex.Detail);
        Assert.Equal("https://epistola.app/errors/not-found", ex.Type);
    }

    [Fact]
    public void TypeSlugIsNullForAboutBlankAndNonEpistolaTypes()
    {
        Assert.Null(Exception(new ProblemDetail(title: "Bad Request", status: 400)).TypeSlug);
        Assert.Null(Exception(new ProblemDetail(type: "https://example.com/oops", title: "X", status: 400)).TypeSlug);
    }

    [Fact]
    public void PlainProblemHasNoValidationErrors()
    {
        var ex = Exception(new ProblemDetail(title: "Conflict", status: 409));
        Assert.Empty(ex.Errors);
        Assert.False(ex.IsValidationProblem);
        Assert.Empty(ex.ValidationErrors);
        Assert.False(ex.IsDataModelValidationProblem);
    }

    [Fact]
    public void DataModelValidationProblemExposesPerExampleFailures()
    {
        var ex = Exception(
            new ProblemDetail(type: "https://epistola.app/errors/data-model-validation-error", title: "Data Model Validation Error", status: 422),
            validationErrors: new Dictionary<string, List<DataModelValidationError>>
            {
                ["Example 1"] = new() { new DataModelValidationError(path: "/name", message: "required property 'name' not found") },
            });

        Assert.True(ex.IsDataModelValidationProblem);
        Assert.False(ex.IsValidationProblem);
        Assert.Equal(KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR, ex.TypeSlug);
        Assert.Equal("/name", ex.ValidationErrors["Example 1"][0].Path);
    }

    [Fact]
    public void ValidationProblemExposesFieldErrors()
    {
        var ex = Exception(
            new ProblemDetail(type: "https://epistola.app/errors/validation-error", title: "Bad Request", status: 400),
            errors: new List<ValidationError>
            {
                new(field: "name", message: "must not be blank"),
                new(field: "slug", message: "invalid format", rejectedValue: "A B"),
            });

        Assert.True(ex.IsValidationProblem);
        Assert.Equal(2, ex.Errors.Count);
        Assert.Equal("name", ex.Errors[0].Field);
        Assert.Equal("validation-error", ex.TypeSlug);
    }

    [Fact]
    public void IsApiExceptionCarryingTheOriginalBodyAndStatus()
    {
        const string body = "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409}";
        var ex = Exception(new ProblemDetail(title: "Conflict", status: 409), body: body);

        // Assignable to the generated ApiException so existing catch sites keep working.
        ApiException asParent = ex;
        Assert.Equal(body, asParent.ErrorContent);
        Assert.Equal(409, asParent.ErrorCode);
    }
}
