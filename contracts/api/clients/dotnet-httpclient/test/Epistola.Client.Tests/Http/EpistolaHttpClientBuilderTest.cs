using System;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Threading.Tasks;
using Epistola.Client.Auth;
using Epistola.Client.Error;
using Epistola.Client.Http;
using Epistola.Client.Identity;
using Xunit;

namespace Epistola.Client.Tests.Http;

public class EpistolaHttpClientBuilderTest
{
    [Fact]
    public async Task ComposesTheFullHandlerChain()
    {
        HttpRequestMessage? captured = null;
        var primary = new StubHttpMessageHandler(req =>
        {
            captured = req;
            return new HttpResponseMessage(HttpStatusCode.OK);
        });

        var identity = ClientIdentity.Builder().NodeId("pod-1").Product("app", "1.0.0").Build();
        var signer = JwtSigner.Builder().ConsumerId("c").PrivateKey(RSA.Create(2048)).Build();

        var http = new EpistolaHttpClientBuilder()
            .BaseUrl("http://localhost/")
            .Identity(identity)
            .JwtSigner(signer)
            .InstallProblemDetailHandler()
            .PrimaryHandler(primary)
            .Build();

        await http.PostAsync("thing", new StringContent("{}", System.Text.Encoding.UTF8, "application/json"));

        Assert.NotNull(captured);
        // identity headers
        Assert.Equal(identity.UserAgent, captured!.Headers.UserAgent.ToString());
        Assert.Equal("pod-1", captured.Headers.GetValues(ClientIdentity.HeaderNodeId).Single());
        // jwt auth
        Assert.Equal("Bearer", captured.Headers.Authorization!.Scheme);
        // vendor media type applied to the body
        Assert.Equal(EpistolaMediaTypeHandler.VendorJson, captured.Content!.Headers.ContentType!.MediaType);
    }

    [Fact]
    public async Task ProblemDetailHandlerIsInstalledWhenRequested()
    {
        var primary = new StubHttpMessageHandler(_ =>
        {
            var r = new HttpResponseMessage(HttpStatusCode.NotFound)
            {
                Content = new StringContent("{\"type\":\"https://epistola.app/errors/not-found\",\"title\":\"Not Found\",\"status\":404}"),
            };
            r.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/problem+json");
            return r;
        });

        var http = new EpistolaHttpClientBuilder()
            .BaseUrl("http://localhost/")
            .InstallProblemDetailHandler()
            .PrimaryHandler(primary)
            .Build();

        var ex = await Assert.ThrowsAsync<ProblemDetailException>(() => http.GetAsync("missing"));
        Assert.Equal("not-found", ex.TypeSlug);
    }

    [Fact]
    public async Task ApiKeyAddsAuthorizationHeader()
    {
        HttpRequestMessage? captured = null;
        var primary = new StubHttpMessageHandler(req =>
        {
            captured = req;
            return new HttpResponseMessage(HttpStatusCode.OK);
        });

        var http = new EpistolaHttpClientBuilder()
            .BaseUrl("http://localhost/")
            .ApiKey("epk_test")
            .PrimaryHandler(primary)
            .Build();

        await http.GetAsync("thing");

        Assert.NotNull(captured);
        Assert.Equal("ApiKey", captured!.Headers.Authorization!.Scheme);
        Assert.Equal("epk_test", captured.Headers.Authorization.Parameter);
    }

    [Fact]
    public async Task JwtSignerWinsWhenJwtAndApiKeyAreBothConfigured()
    {
        HttpRequestMessage? captured = null;
        var primary = new StubHttpMessageHandler(req =>
        {
            captured = req;
            return new HttpResponseMessage(HttpStatusCode.OK);
        });
        var signer = JwtSigner.Builder().ConsumerId("c").PrivateKey(RSA.Create(2048)).Build();

        var http = new EpistolaHttpClientBuilder()
            .BaseUrl("http://localhost/")
            .ApiKey("epk_test")
            .JwtSigner(signer)
            .PrimaryHandler(primary)
            .Build();

        await http.GetAsync("thing");

        Assert.NotNull(captured);
        Assert.Equal("Bearer", captured!.Headers.Authorization!.Scheme);
    }

    [Fact]
    public async Task WithoutProblemHandlerErrorsPassThrough()
    {
        var primary = new StubHttpMessageHandler(_ =>
        {
            var r = new HttpResponseMessage(HttpStatusCode.NotFound)
            {
                Content = new StringContent("{\"type\":\"https://epistola.app/errors/not-found\",\"status\":404}"),
            };
            r.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/problem+json");
            return r;
        });

        var http = new EpistolaHttpClientBuilder().BaseUrl("http://localhost/").PrimaryHandler(primary).Build();

        var response = await http.GetAsync("missing");
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }
}
