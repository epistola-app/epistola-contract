using System;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Epistola.Client.Identity;
using Xunit;

namespace Epistola.Client.Tests.Identity;

public class ClientIdentityTest
{
    [Fact]
    public void BuilderWithNoProductsProducesOnlyContractToken()
    {
        var identity = ClientIdentity.Builder().NodeId("test-pod").Build();
        Assert.StartsWith("epistola-contract/", identity.UserAgent);
        Assert.Equal("test-pod", identity.NodeId);
    }

    [Fact]
    public void BuilderWithProductsAppendsThemInOrder()
    {
        var identity = ClientIdentity.Builder()
            .NodeId("test-pod")
            .Product("valtimo-epistola-plugin", "1.2.0")
            .Product("gzac", "5.0.0")
            .Build();

        var tokens = identity.UserAgent.Split(' ');
        Assert.Equal(3, tokens.Length);
        Assert.StartsWith("epistola-contract/", tokens[0]);
        Assert.Equal("valtimo-epistola-plugin/1.2.0", tokens[1]);
        Assert.Equal("gzac/5.0.0", tokens[2]);
    }

    [Fact]
    public void BuilderDefaultsNodeIdToHostname()
    {
        var identity = ClientIdentity.Builder().Build();
        Assert.Equal(Dns.GetHostName(), identity.NodeId);
    }

    [Theory]
    [InlineData("", "1.0.0")]
    [InlineData("my-app", "")]
    [InlineData("my/app", "1.0.0")]
    [InlineData("my app", "1.0.0")]
    public void BuilderRejectsInvalidProducts(string name, string version)
    {
        Assert.Throws<ArgumentException>(() => ClientIdentity.Builder().Product(name, version));
    }

    [Fact]
    public async Task HandlerSetsBothHeadersOnRequest()
    {
        var identity = ClientIdentity.Builder().NodeId("pod-123").Product("test-app", "2.0.0").Build();
        var stub = new StubHttpMessageHandler(_ => new HttpResponseMessage(HttpStatusCode.OK));
        var handler = identity.Handler();
        handler.InnerHandler = stub;
        var client = new HttpClient(handler) { BaseAddress = new Uri("http://localhost/") };

        await client.PostAsync("thing", new StringContent("{}", Encoding.UTF8, "application/json"));

        var request = Assert.Single(stub.Requests);
        // User-Agent is a structured header; the tokens re-serialize to the same string.
        Assert.Equal(identity.UserAgent, request.Headers.UserAgent.ToString());
        Assert.Equal("pod-123", request.Headers.GetValues(ClientIdentity.HeaderNodeId).Single());
    }
}
