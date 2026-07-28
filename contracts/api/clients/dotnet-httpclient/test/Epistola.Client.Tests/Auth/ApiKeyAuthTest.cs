using System;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Epistola.Client.Auth;
using Xunit;

namespace Epistola.Client.Tests.Auth;

public class ApiKeyAuthTest
{
    [Fact]
    public async Task HandlerSetsAuthorizationApiKeyHeader()
    {
        var auth = ApiKeyAuth.Of("epk_test");
        var stub = new StubHandler();
        var handler = auth.Handler();
        handler.InnerHandler = stub;

        using var client = new HttpClient(handler);
        await client.GetAsync("https://example.test");

        Assert.Equal("ApiKey", stub.Request!.Headers.Authorization!.Scheme);
        Assert.Equal("epk_test", stub.Request.Headers.Authorization.Parameter);
    }

    [Fact]
    public void BlankApiKeyIsRejected()
    {
        Assert.Throws<ArgumentException>(() => ApiKeyAuth.Of(" "));
    }

    private sealed class StubHandler : HttpMessageHandler
    {
        public HttpRequestMessage? Request { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Request = request;
            return Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.OK));
        }
    }
}
