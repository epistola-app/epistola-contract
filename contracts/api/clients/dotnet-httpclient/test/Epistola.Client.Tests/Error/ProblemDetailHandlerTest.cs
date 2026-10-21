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

    [Theory]
    [InlineData(503)]
    [InlineData(502)]
    public async Task ThrowsTypedExceptionForACatalogSourceProblemAsA503OrA502(int status)
    {
        var body = "{\"type\":\"https://epistola.app/errors/catalog-source-unavailable\"," +
                   "\"title\":\"Catalog Source Unavailable\",\"status\":" + status + "," +
                   "\"detail\":\"registry.gemeente-x.nl could not be used\"}";
        var client = ClientReturning((HttpStatusCode)status, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.GetAsync("thing"));
        Assert.Equal(KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE, ex.TypeSlug);
        Assert.Equal(status, ex.ProblemStatus);
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
        var client = ClientReturning(HttpStatusCode.OK, "application/vnd.epistola.v2+json", "{\"ok\":true}");
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
    [Fact]
    public async Task ThrowsWithTheMissingDependenciesOfAReleaseDependenciesMissingProblem()
    {
        const string body = """
            {
              "type": "https://epistola.app/errors/release-dependencies-missing",
              "title": "Release dependencies missing",
              "status": 409,
              "detail": "letters 1.4.0 renders with releases production does not serve",
              "missingDependencies": [
                {"registry": "exchange.epistola.app", "namespace": "epistola", "catalogKey": "system",
                 "version": "3.2.0", "reason": "unavailable"},
                {"registry": "registry.gemeente-x.nl", "namespace": "huisstijl", "catalogKey": "branding",
                 "version": "1.4.2", "reason": "unavailable"}
              ]
            }
            """;
        var client = ClientReturning(HttpStatusCode.Conflict, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.PutAsync("deploy", null));
        Assert.Equal(KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING, ex.TypeSlug);
        Assert.Equal(409, ex.ProblemStatus);
        Assert.True(ex.IsReleaseDependenciesMissingProblem);
        Assert.Equal(2, ex.MissingDependencies.Count);
        var first = ex.MissingDependencies[0];
        Assert.Equal("exchange.epistola.app", first.Registry);
        Assert.Equal("epistola", first.Namespace);
        Assert.Equal("system", first.CatalogKey);
        Assert.Equal("3.2.0", first.Version);
        Assert.Equal(MissingDependency.Reasons.Unavailable, first.Reason);
        Assert.Equal("registry.gemeente-x.nl/huisstijl/branding@1.4.2:unavailable", ex.MissingDependencies[1].ToString());
    }

    [Fact]
    public void ParseProblemReportsNoMissingDependenciesWhenTheMemberIsAbsent()
    {
        var parsed = ProblemDetailHandler.ParseProblem(
            """{"type":"https://epistola.app/errors/release-dependencies-missing","title":"Release dependencies missing","status":409}""");

        Assert.NotNull(parsed);
        Assert.Empty(parsed!.MissingDependencies);
    }

    [Fact]
    public void ParseProblemKeepsAReasonTheContractDoesNotNameYet()
    {
        // The generated enum would reject this, losing the entry; the reason must survive as sent.
        var parsed = ProblemDetailHandler.ParseProblem("""
            {
              "type": "https://epistola.app/errors/release-dependencies-missing",
              "title": "Release dependencies missing",
              "status": 409,
              "missingDependencies": [
                {"registry": "r.example", "namespace": "n", "catalogKey": "k", "version": "1.0.0", "reason": "some-future-reason"}
              ]
            }
            """);

        Assert.NotNull(parsed);
        Assert.Single(parsed!.MissingDependencies);
        Assert.Equal("some-future-reason", parsed.MissingDependencies[0].Reason);
    }

    [Fact]
    public void ParseProblemSkipsMalformedMissingDependencyEntriesOneByOne()
    {
        // A malformed entry is dropped on its own; it must not hide the well-formed ones, nor the problem.
        var parsed = ProblemDetailHandler.ParseProblem("""
            {
              "type": "https://epistola.app/errors/release-dependencies-missing",
              "title": "Release dependencies missing",
              "status": 409,
              "missingDependencies": [
                "not an object",
                {"registry": "r.example", "namespace": "n", "catalogKey": "k", "version": "1.0.0"},
                {"registry": "r.example", "namespace": "n", "catalogKey": "k", "version": 1, "reason": "unavailable"},
                {"registry": "r.example", "namespace": "n", "catalogKey": "k", "version": "2.0.0", "reason": "unavailable"}
              ]
            }
            """);

        Assert.NotNull(parsed);
        Assert.Equal("Release dependencies missing", parsed!.Problem.Title);
        Assert.Single(parsed.MissingDependencies);
        Assert.Equal("2.0.0", parsed.MissingDependencies[0].Version);
        Assert.Equal(MissingDependency.Reasons.Unavailable, parsed.MissingDependencies[0].Reason);
    }

    [Fact]
    public void ParseProblemReportsNoMissingDependenciesWhenTheMemberIsNotAnArray()
    {
        var parsed = ProblemDetailHandler.ParseProblem("""
            {
              "type": "https://epistola.app/errors/release-dependencies-missing",
              "title": "Release dependencies missing",
              "status": 409,
              "missingDependencies": {"registry": "r.example"}
            }
            """);

        Assert.NotNull(parsed);
        Assert.Empty(parsed!.MissingDependencies);
    }

    [Fact]
    public async Task ThrowsWithTheDraftsOfAReleaseDependenciesUnreleasedProblem()
    {
        const string body = """
            {
              "type": "https://epistola.app/errors/release-dependencies-unreleased",
              "title": "Release dependencies unreleased",
              "status": 409,
              "detail": "letters uses drafts of other catalogs",
              "unreleasedDependencies": [
                {"registry": "exchange.epistola.app", "namespace": "acme-corp", "catalogKey": "branding",
                 "releaseId": "r4t8w2nc", "releaseName": "House style 2027"},
                {"registry": "registry.gemeente-x.nl", "namespace": "huisstijl", "catalogKey": "fonts",
                 "releaseId": "h3v9p6db", "releaseName": "New typefaces, round 2"}
              ]
            }
            """;
        var client = ClientReturning(HttpStatusCode.Conflict, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.PostAsync("release", null));
        Assert.Equal(KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED, ex.TypeSlug);
        Assert.Equal(409, ex.ProblemStatus);
        Assert.True(ex.IsReleaseDependenciesUnreleasedProblem);
        Assert.Empty(ex.MissingDependencies);
        Assert.Equal(2, ex.UnreleasedDependencies.Count);
        var first = ex.UnreleasedDependencies[0];
        Assert.Equal("exchange.epistola.app", first.Registry);
        Assert.Equal("acme-corp", first.Namespace);
        Assert.Equal("branding", first.CatalogKey);
        Assert.Equal("r4t8w2nc", first.ReleaseId);
        Assert.Equal("House style 2027", first.ReleaseName);
        Assert.Equal(
            "registry.gemeente-x.nl/huisstijl/fonts#h3v9p6db=New typefaces, round 2",
            ex.UnreleasedDependencies[1].ToString());
    }

    [Fact]
    public async Task ThrowsWithTheReasonOfACatalogReadOnlyProblem()
    {
        const string body = """
            {"type": "https://epistola.app/errors/catalog-read-only", "title": "Catalog Read Only",
             "status": 409, "readOnlyReason": "editing-disabled"}
            """;
        var client = ClientReturning(HttpStatusCode.Conflict, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.PostAsync("release", null));
        Assert.Equal(KnownProblemSlugs.CATALOG_READ_ONLY, ex.TypeSlug);
        Assert.True(ex.IsCatalogReadOnlyProblem);
        Assert.Equal("editing-disabled", ex.ReadOnlyReason);
    }

    [Fact]
    public async Task ThrowsWithTheConflictsOfAMergeConflictsUnresolvedProblem()
    {
        const string body = """
            {"type": "https://epistola.app/errors/merge-conflicts-unresolved", "title": "Merge Conflicts Unresolved",
             "status": 409, "unresolvedConflicts": ["stencil/footer", 7, "template/invoice"]}
            """;
        var client = ClientReturning(HttpStatusCode.Conflict, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.PostAsync("merge", null));
        Assert.Equal(KnownProblemSlugs.MERGE_CONFLICTS_UNRESOLVED, ex.TypeSlug);
        Assert.True(ex.IsMergeConflictsUnresolvedProblem);
        Assert.Equal(new[] { "stencil/footer", "template/invoice" }, ex.UnresolvedConflicts);

        var plain = ProblemDetailHandler.ParseProblem("""{"type": "https://epistola.app/errors/conflict", "title": "Conflict", "status": 409}""");
        Assert.Empty(plain!.UnresolvedConflicts);
    }

    [Fact]
    public async Task ThrowsWithTheReleaseAMergeBaseMissingProblemAsksFor()
    {
        const string body = """
            {"type": "https://epistola.app/errors/merge-base-missing", "title": "Merge Base Missing",
             "status": 409, "version": "1.0.0", "fingerprint": "v5:9999999999999999999999999999999999999999999999999999999999999999"}
            """;
        var client = ClientReturning(HttpStatusCode.Conflict, "application/problem+json", body);

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => client.PostAsync("merge", null));
        Assert.Equal(KnownProblemSlugs.MERGE_BASE_MISSING, ex.TypeSlug);
        Assert.True(ex.IsMergeBaseMissingProblem);
        Assert.Equal("1.0.0", ex.MissingBaseVersion);
        Assert.Equal("v5:9999999999999999999999999999999999999999999999999999999999999999", ex.MissingBaseFingerprint);

        var other = ProblemDetailHandler.ParseProblem("""{"type": "https://epistola.app/errors/catalog-schema-too-old", "title": "Too old", "status": 409, "version": "4"}""");
        Assert.Null(other!.MissingBaseVersion);
        Assert.Null(other.MissingBaseFingerprint);
    }

    [Fact]
    public void ParseProblemReadsNoReadOnlyReasonThatIsNotAString()
    {
        var parsed = ProblemDetailHandler.ParseProblem(
            """{"type": "https://epistola.app/errors/catalog-read-only", "title": "Catalog Read Only", "status": 409, "readOnlyReason": 7}""");
        Assert.NotNull(parsed);
        Assert.Null(parsed!.ReadOnlyReason);

        var plain = ProblemDetailHandler.ParseProblem("""{"type": "https://epistola.app/errors/conflict", "title": "Conflict", "status": 409}""");
        Assert.Null(plain!.ReadOnlyReason);
    }

    [Fact]
    public void ParseProblemSkipsMalformedUnreleasedDependencyEntriesOneByOne()
    {
        var parsed = ProblemDetailHandler.ParseProblem("""
            {
              "type": "https://epistola.app/errors/release-dependencies-unreleased",
              "title": "Release dependencies unreleased",
              "status": 409,
              "unreleasedDependencies": [
                "not an object",
                {"registry": "r.example", "namespace": "n", "catalogKey": "k", "releaseId": "d"},
                {"registry": "r.example", "namespace": "n", "catalogKey": "k", "releaseId": 1, "releaseName": "x"},
                {"registry": "r.example", "namespace": "n", "catalogKey": "k", "releaseId": "d2", "releaseName": "Kept"}
              ]
            }
            """);

        Assert.NotNull(parsed);
        Assert.Single(parsed!.UnreleasedDependencies);
        Assert.Equal("Kept", parsed.UnreleasedDependencies[0].ReleaseName);
    }

    [Fact]
    public void ParseProblemReportsNoUnreleasedDependenciesWhenTheMemberIsNotAnArray()
    {
        var parsed = ProblemDetailHandler.ParseProblem("""
            {
              "type": "https://epistola.app/errors/release-dependencies-unreleased",
              "title": "Release dependencies unreleased",
              "status": 409,
              "unreleasedDependencies": {"registry": "r.example"}
            }
            """);

        Assert.NotNull(parsed);
        Assert.Empty(parsed!.UnreleasedDependencies);
    }
}
