using System;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Epistola.Client.Http;
using Xunit;

namespace Epistola.Client.Tests.Http;

public class EpistolaMediaTypeHandlerTest
{
    private static (HttpClient Client, StubHttpMessageHandler Stub) NewClient()
    {
        var stub = new StubHttpMessageHandler(_ => new HttpResponseMessage(HttpStatusCode.OK));
        var client = new HttpClient(new EpistolaMediaTypeHandler(stub)) { BaseAddress = new Uri("http://localhost/") };
        return (client, stub);
    }

    [Fact]
    public async Task RewritesApplicationJsonToVendorType()
    {
        var (client, stub) = NewClient();
        await client.PostAsync("thing", new StringContent("{}", Encoding.UTF8, "application/json"));

        var contentType = Assert.Single(stub.Requests).Content!.Headers.ContentType;
        Assert.Equal(EpistolaMediaTypeHandler.VendorJson, contentType!.MediaType);
        Assert.Equal("utf-8", contentType.CharSet);
    }

    [Fact]
    public async Task LeavesOtherContentTypesUntouched()
    {
        var (client, stub) = NewClient();
        await client.PostAsync("thing", new StringContent("data", Encoding.UTF8, "application/pdf"));

        Assert.Equal("application/pdf", Assert.Single(stub.Requests).Content!.Headers.ContentType!.MediaType);
    }
}
