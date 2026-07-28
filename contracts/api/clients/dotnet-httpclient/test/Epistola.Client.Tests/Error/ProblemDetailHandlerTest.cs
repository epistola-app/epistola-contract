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
}
