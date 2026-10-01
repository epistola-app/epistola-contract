// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Epistola.Client.Error;
using Xunit;

namespace Epistola.Client.Tests.Error;

public class ProblemDetailHandlerTest
{
    private static HttpClient ClientReturning(HttpStatusCode status, string? contentType, string body)
    {
        var stub = new StubHttpMessageHandler(_ =>
        {
            var response = new HttpResponseMessage(status)
            {
                Content = new StringContent(body, Encoding.UTF8),
            };
            if (contentType != null)
            {
                response.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(contentType);
            }
            return response;
        });
        return new HttpClient(new ProblemDetailHandler(stub)) { BaseAddress = new System.Uri("http://localhost/") };
    }

    [Fact]
    public async Task ThrowsTypedExceptionForProblemJson()
    {
        var body = "{\"type\":\"https://epistola.app/errors/not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"gone\"}";
        var client = ClientReturning(HttpStatusCode.NotFound, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.GetAsync("thing"));
        Assert.Equal("not-found", ex.TypeSlug);
        Assert.Equal(404, ex.ProblemStatus);
        Assert.Equal("gone", ex.Detail);
    }

    [Fact]
    public async Task ParsesValidationErrorsArray()
    {
        var body = "{\"type\":\"https://epistola.app/errors/validation-error\",\"title\":\"Bad Request\",\"status\":400," +
                   "\"errors\":[{\"field\":\"name\",\"message\":\"must not be blank\"}]}";
        var client = ClientReturning((HttpStatusCode)400, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.GetAsync("thing"));
        Assert.True(ex.IsValidationProblem);
        Assert.Single(ex.Errors);
        Assert.Equal("name", ex.Errors[0].Field);
    }

    [Fact]
    public async Task PassesThroughNonProblemErrors()
    {
        var client = ClientReturning(HttpStatusCode.InternalServerError, "text/plain", "boom");
        var response = await client.GetAsync("thing");
        Assert.Equal(HttpStatusCode.InternalServerError, response.StatusCode);
        Assert.Equal("boom", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task DoesNotTouchSuccessResponses()
    {
        var client = ClientReturning(HttpStatusCode.OK, "application/vnd.epistola.v1+json", "{\"ok\":true}");
        var response = await client.GetAsync("thing");
        Assert.True(response.IsSuccessStatusCode);
        Assert.Equal("{\"ok\":true}", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public void ParseProblemReturnsNullOnMalformedJson()
    {
        Assert.Null(ProblemDetailHandler.ParseProblem("{not json"));
    }

    [Fact]
    public void ParseProblemReadsTemplateDataFieldPointers()
    {
        // The members contract 1.4.0 added. They were specified and generated, but nothing here
        // read them, so a `template-data-invalid` response arrived with its pointers discarded.
        var parsed = ProblemDetailHandler.ParseProblem("""
            {
              "type": "https://epistola.app/errors/template-data-invalid",
              "title": "Template data invalid",
              "status": 400,
              "errors": [],
              "missingFields": [
                {"path": "/customer/address", "required": true, "schema": {"type": "object"}},
                {"path": "/customer/phone", "required": false, "schema": {"type": "string"}}
              ],
              "invalidFields": [
                {"path": "/customer/age", "keyword": "type",
                 "message": "string found, integer expected", "schema": {"type": "integer"}}
              ]
            }
            """);

        Assert.NotNull(parsed);
        Assert.Equal(2, parsed!.MissingFields.Count);
        Assert.Equal("/customer/address", parsed.MissingFields[0].Path);
        Assert.True(parsed.MissingFields[0].Required);
        Assert.False(parsed.MissingFields[1].Required);
        Assert.Single(parsed.InvalidFields);
        Assert.Equal("/customer/age", parsed.InvalidFields[0].Path);
        Assert.Equal("type", parsed.InvalidFields[0].Keyword);
    }

    [Fact]
    public void ParseProblemReportsNoTemplateDataFieldsForAnyOtherProblem()
    {
        var parsed = ProblemDetailHandler.ParseProblem(
            """{"type":"https://epistola.app/errors/not-found","title":"Not Found","status":404}""");

        Assert.NotNull(parsed);
        Assert.Empty(parsed!.MissingFields);
        Assert.Empty(parsed.InvalidFields);
    }

    [Fact]
    public void ParseProblemSkipsAMalformedTemplateDataMember()
    {
        // A bad problem body must not hide the problem it decorates, nor become a confident claim
        // about a particular field.
        var parsed = ProblemDetailHandler.ParseProblem("""
            {
              "type": "https://epistola.app/errors/template-data-invalid",
              "title": "Template data invalid",
              "status": 400,
              "missingFields": "not an array"
            }
            """);

        Assert.NotNull(parsed);
        Assert.Equal("Template data invalid", parsed!.Problem.Title);
        Assert.Empty(parsed.MissingFields);
    }
}
