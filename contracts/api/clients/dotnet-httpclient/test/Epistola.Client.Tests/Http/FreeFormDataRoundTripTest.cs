// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Net;
using System.Net.Http;
using System.Text;
using Epistola.Client.Api;
using Epistola.Client.Http;
using Epistola.Client.Model;
using Xunit;

namespace Epistola.Client.Tests.Http;

/// <summary>
/// An item's <c>data</c> goes back into <c>GenerateDocument</c> unchanged (contract ADR 0011): read
/// by the generated models, sent by them, through the handlers the builder installs.
/// </summary>
public class FreeFormDataRoundTripTest
{
    private const string Data =
        """{"caseNumber":"Z-2026-0412","amountDue":19.99,"lineCount":3,"paid":false,"reference":null,"signedAt":"2026-11-19T09:12:00+02:00","decisionDate":"2026-11-19","citizen":{"name":"J. Jansen","address":{"street":"Kerkstraat 11","notes":null}},"tags":["objection",1,true,null,{"a":[]}]}""";

    private static readonly string Items =
        $$$"""{"items":[{"itemId":"660e8400-e29b-41d4-a716-446655440001","registry":"exchange.epistola.app","namespace":"acme-corp","catalogKey":"letters","templateKey":"objection-decision","variantKey":"objection-decision-en","status":"completed","createdAt":"2026-11-18T09:12:00Z","completedAt":"2026-11-18T09:13:00+01:00","cancelReason":null,"data":{{{Data}}},"editedFields":["/citizen/address/street"]}],"page":{"number":0,"size":20,"total":1,"totalPages":1}}""";

    private const string Job =
        """{"requestId":"550e8400-e29b-41d4-a716-446655440009","status":"pending","jobType":"single","total":1,"createdAt":"2026-11-25T10:00:00Z"}""";

    [Fact]
    public void AnItemsDataIsSentBackExactlyAsItWasRead()
    {
        string? sent = null;
        var stub = new StubHttpMessageHandler(request =>
        {
            if (request.Method == HttpMethod.Get)
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(Items, Encoding.UTF8, "application/vnd.epistola.v2+json"),
                };
            }

            sent = request.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
            return new HttpResponseMessage(HttpStatusCode.Accepted)
            {
                Content = new StringContent(Job, Encoding.UTF8, "application/vnd.epistola.v2+json"),
            };
        });
        var http = new EpistolaHttpClientBuilder().BaseUrl("http://localhost/api").PrimaryHandler(stub).Build();
        var generation = new GenerationApi(http, "http://localhost/api");

        var item = generation.ListGenerationJobItems("acme-corp", Guid.Parse("550e8400-e29b-41d4-a716-446655440000")).Items[0];
        generation.GenerateDocument("acme-corp", new GenerateDocumentRequest(
            @namespace: "acme-corp",
            catalogKey: "letters",
            templateKey: "objection-decision",
            variantKey: item.VariantKey,
            data: item.Data));

        Assert.Equal(new[] { "/citizen/address/street" }, item.EditedFields);
        // Typed members still parse the date-times the generator now leaves as strings in free-form JSON.
        Assert.Equal(new DateTimeOffset(2026, 11, 18, 9, 13, 0, TimeSpan.FromHours(1)), item.CompletedAt);
        Assert.Equal(
            $$$"""{"namespace":"acme-corp","catalogKey":"letters","templateKey":"objection-decision","variantKey":"objection-decision-en","data":{{{Data}}}}""",
            sent);
    }
}
