// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Epistola.Client.Http;
using Xunit;

namespace Epistola.Client.Tests.Http;

public class EpistolaUnsetPropertyHandlerTest
{
    private static (HttpClient Http, StubHttpMessageHandler Stub, HttpRequestMessage[] Captured) Build()
    {
        var captured = new HttpRequestMessage[1];
        var stub = new StubHttpMessageHandler(req =>
        {
            captured[0] = req;
            return new HttpResponseMessage(HttpStatusCode.OK);
        });
        var http = new EpistolaHttpClientBuilder().BaseUrl("http://localhost/").PrimaryHandler(stub).Build();
        return (http, stub, captured);
    }

    private static async Task<string> Send(string body)
    {
        var (http, _, captured) = Build();
        await http.PatchAsync("thing", new StringContent(body, Encoding.UTF8, "application/vnd.epistola.v1+json"));
        return await captured[0]!.Content!.ReadAsStringAsync();
    }

    [Fact]
    public async Task DropsPropertiesTheCallerNeverSet()
    {
        // description, contact and expiresAt are documented "null to clear" on the API's PATCH
        // operations, so sending them turns a rename into a rename plus an erase.
        var sent = await Send("""{"name":"Billing Service","description":null,"contact":null,"expiresAt":null}""");

        Assert.Equal("""{"name":"Billing Service"}""", sent);
    }

    [Fact]
    public async Task LeavesNullsInsideCallerSuppliedDataAlone()
    {
        // A generation request's `data` is free-form and belongs to the caller; a null in there is
        // theirs and means what they meant by it.
        var sent = await Send("""{"templateId":"invoice","data":{"notes":null},"filename":null}""");

        Assert.Equal("""{"templateId":"invoice","data":{"notes":null}}""", sent);
    }

    [Fact]
    public async Task LeavesABodyWithNothingToStripByteIdentical()
    {
        var body = """{"catalogId":"default","templateId":"invoice"}""";

        Assert.Equal(body, await Send(body));
    }

    [Fact]
    public async Task LeavesANonJsonBodyAlone()
    {
        var (http, _, captured) = Build();

        await http.PostAsync("thing", new StringContent("not json at all", Encoding.UTF8, "text/plain"));

        Assert.Equal("not json at all", await captured[0]!.Content!.ReadAsStringAsync());
    }
}
