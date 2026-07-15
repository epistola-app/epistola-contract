using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Epistola.Client.Collect;
using Xunit;

namespace Epistola.Client.Tests.Collect;

public class ResultCollectorLoopTest
{
    private static HttpClient StubClient(string ndjson)
    {
        var stub = new StubHttpMessageHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(ndjson, Encoding.UTF8),
        });
        return new HttpClient(stub) { BaseAddress = new Uri("http://localhost/") };
    }

    [Fact]
    public async Task StartPollsUntilStopped()
    {
        var ndjson =
            "{\"sequence\":1,\"requestId\":\"r1\",\"status\":\"COMPLETED\"}\n" +
            "{\"_meta\":true,\"hasMore\":false}\n";

        var received = new List<ResultCollector.GenerationResult>();
        var gotOne = new SemaphoreSlim(0, 1);

        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantId("acme")
            .RegisterShutdownHook(false)
            .MinInterval(TimeSpan.FromMilliseconds(20))
            .MaxInterval(TimeSpan.FromMilliseconds(50))
            .Handler(r =>
            {
                received.Add(r);
                if (received.Count == 1)
                {
                    gotOne.Release();
                }
            })
            .Build();

        var loop = collector.StartAsync();

        Assert.True(await gotOne.WaitAsync(TimeSpan.FromSeconds(5)), "collector never polled");
        collector.Stop();
        await loop; // Stop() must break the loop promptly

        Assert.True(received.Count >= 1);
        Assert.Equal("COMPLETED", received[0].Status);
    }

    [Fact]
    public async Task StartAsyncHonorsCancellation()
    {
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient("{\"_meta\":true,\"hasMore\":false}\n"))
            .TenantId("acme")
            .RegisterShutdownHook(false)
            .MinInterval(TimeSpan.FromMilliseconds(20))
            .Handler(_ => { })
            .Build();

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));
        await collector.StartAsync(cts.Token); // returns when cancelled, does not throw
    }
}
