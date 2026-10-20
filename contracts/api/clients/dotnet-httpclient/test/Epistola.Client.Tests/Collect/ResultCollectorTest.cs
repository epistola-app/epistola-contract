// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

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

public class ResultCollectorTest
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
    public void CollectOnceStreamsResultsAndReadsMeta()
    {
        var ndjson =
            "{\"sequence\":1,\"requestId\":\"r1\",\"status\":\"completed\",\"registry\":\"exchange.epistola.app\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\",\"documentId\":\"d1\"}\n" +
            "{\"sequence\":2,\"requestId\":\"r2\",\"status\":\"failed\",\"registry\":\"exchange.epistola.app\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\",\"error\":\"boom\"}\n" +
            "{\"_meta\":true,\"hasMore\":false,\"resultCount\":2,\"partitions\":{\"total\":4,\"mine\":[0,2],\"hash\":\"murmur3\"}}\n";

        var received = new List<ResultCollector.GenerationResult>();
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .Handler(received.Add)
            .Build();

        var result = collector.CollectOnce();

        Assert.Equal(2, result.Count);
        Assert.False(result.HasMore);
        Assert.Equal(2, received.Count);
        Assert.Equal("completed", received[0].Status);
        Assert.Equal("d1", received[0].DocumentId);
        Assert.Equal("boom", received[1].Error);

        Assert.NotNull(collector.CurrentPartitionAssignment);
        Assert.Equal(4, collector.CurrentPartitionAssignment!.Total);
        Assert.Equal(new[] { 0, 2 }, collector.CurrentPartitionAssignment.Mine);
    }

    [Fact]
    public void AResultNamesTheCatalogAndReleaseItRendered()
    {
        var ndjson =
            "{\"sequence\":1,\"requestId\":\"r1\",\"status\":\"completed\",\"registry\":\"exchange.epistola.app\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\",\"version\":\"1.4.0\"}\n" +
            "{\"sequence\":2,\"requestId\":\"r2\",\"status\":\"completed\",\"registry\":\"exchange.epistola.app\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\",\"version\":null}\n" +
            "{\"_meta\":true,\"hasMore\":false}\n";

        var received = new List<ResultCollector.GenerationResult>();
        ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .Handler(received.Add)
            .Build()
            .CollectOnce();

        Assert.Equal("exchange.epistola.app", received[0].Registry);
        Assert.Equal("acme-corp", received[0].Namespace);
        Assert.Equal("letters", received[0].CatalogKey);
        Assert.Equal("1.4.0", received[0].Version);
        // Rendered from a draft: the catalog is named, the release version is null.
        Assert.Equal("letters", received[1].CatalogKey);
        Assert.Null(received[1].Version);
    }

    [Fact]
    public void AResultLineWithoutItsCatalogIsMalformed()
    {
        // The contract requires registry, namespace and catalogKey on every result line.
        var ndjson =
            "{\"sequence\":1,\"requestId\":\"r1\",\"status\":\"completed\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\"}\n" +
            "{\"_meta\":true,\"hasMore\":false}\n";

        var received = new List<ResultCollector.GenerationResult>();
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .Handler(received.Add)
            .Build();

        var e = Assert.Throws<FormatException>(() => collector.CollectOnce());
        Assert.Contains("registry", e.Message);
        Assert.Empty(received);
    }

    [Fact]
    public void AResultNamesItsTemplateAndVariantByKey()
    {
        // Contract 2.0 names the template and variant by their keys; the old *Id names are not read.
        var ndjson =
            "{\"sequence\":1,\"requestId\":\"r1\",\"status\":\"completed\",\"registry\":\"exchange.epistola.app\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\",\"templateKey\":\"invoice\",\"variantKey\":\"nl-nl\"}\n" +
            "{\"sequence\":2,\"requestId\":\"r2\",\"status\":\"completed\",\"registry\":\"exchange.epistola.app\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\",\"templateId\":\"invoice\",\"variantId\":\"nl-nl\"}\n" +
            "{\"_meta\":true,\"hasMore\":false}\n";

        var received = new List<ResultCollector.GenerationResult>();
        ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .Handler(received.Add)
            .Build()
            .CollectOnce();

        Assert.Equal("invoice", received[0].TemplateKey);
        Assert.Equal("nl-nl", received[0].VariantKey);
        Assert.Null(received[1].TemplateKey);
        Assert.Null(received[1].VariantKey);
    }

    [Fact]
    public void PartitionHelpersRequireAssignment()
    {
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(""))
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .Handler(_ => { })
            .Build();

        Assert.Null(collector.PartitionFor("key"));
        Assert.False(collector.IsMyPartition("key"));
        Assert.Null(collector.RoutingKeyToMe("key"));
    }

    [Fact]
    public void PartitionForIsDeterministicAndInRange()
    {
        var ndjson = "{\"_meta\":true,\"hasMore\":false,\"resultCount\":2,\"partitions\":{\"total\":8,\"mine\":[0,1,2,3,4,5,6,7],\"hash\":\"murmur3\"}}\n";
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .Handler(_ => { })
            .Build();
        collector.CollectOnce();

        var a = collector.PartitionFor("order-123");
        var b = collector.PartitionFor("order-123");
        Assert.Equal(a, b);
        Assert.InRange(a!.Value, 0, 7);
        // All partitions are mine, so any key routes to me.
        Assert.True(collector.IsMyPartition("order-123"));
        Assert.Equal("order-123", collector.RoutingKeyToMe("order-123"));
    }

    [Fact]
    public void RoutingKeyToMeAlwaysProducesAKeyThatLandsHere()
    {
        // Trying only the partition numbers this node owns is not enough: "3:key" hashes to
        // wherever it hashes, not to partition 3. With 2 of 8 partitions the old fallback returned
        // a foreign key more often than not, which sends the result to another node.
        var ndjson = "{\"_meta\":true,\"hasMore\":false,\"resultCount\":2,\"partitions\":{\"total\":8,\"mine\":[0,3],\"hash\":\"murmur3\"}}\n";
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .Handler(_ => { })
            .Build();
        collector.CollectOnce();

        var rewritten = 0;
        for (var i = 0; i < 100; i++)
        {
            var key = $"order-{i}";
            var routed = collector.RoutingKeyToMe(key);
            Assert.NotNull(routed);
            Assert.True(collector.IsMyPartition(routed!), $"RoutingKeyToMe produced a foreign key: {routed}");
            if (routed != key) rewritten++;
        }

        Assert.True(rewritten > 0, "with 2 of 8 partitions, most keys should need rewriting");
    }

    [Fact]
    public void BackoffRecoversFromAHasMoreBurstInsteadOfReturningZero()
    {
        // HasMore sets the interval to 0 so the next poll is immediate, and 0 * multiplier is
        // still 0 — without a floor, the next request goes out with zero delay, forever. The count
        // below is high because this stub answers from memory with no socket; over a real
        // connection the rate is bounded by round-trip time, but the loop is just as endless.
        var polls = 0;
        var drained = false;
        var burst = "{\"sequence\":1,\"requestId\":\"r1\",\"status\":\"completed\",\"registry\":\"exchange.epistola.app\",\"namespace\":\"acme-corp\",\"catalogKey\":\"letters\"}\n"
                    + "{\"_meta\":true,\"hasMore\":true}\n";
        var empty = "{\"_meta\":true,\"hasMore\":false}\n";

        var handler = new StubHttpMessageHandler(_ =>
        {
            Interlocked.Increment(ref polls);
            // One burst, then nothing: hasMore first, empty from then on.
            var body = drained ? empty : burst;
            drained = true;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/vnd.epistola.v2+ndjson"),
            };
        });

        var collector = ResultCollector.Builder()
            .HttpClient(new HttpClient(handler) { BaseAddress = new Uri("https://epistola.test/api") })
            .TenantKey("acme")
            .RegisterShutdownHook(false)
            .MinInterval(TimeSpan.FromMilliseconds(200))
            .MaxInterval(TimeSpan.FromSeconds(5))
            .Handler(_ => { })
            .Build();

        var loop = Task.Run(() => collector.Start());
        Thread.Sleep(600);
        collector.Stop();
        loop.Wait(TimeSpan.FromSeconds(5));

        // With a 200ms floor and a 3x multiplier: burst, then ~200ms, ~600ms — a handful of polls.
        // Without the floor this runs into the thousands.
        Assert.True(polls < 20, $"expected the loop to back off, but it polled {polls} times");
    }

    [Fact]
    public void Murmur3OfEmptyInputWithSeedZeroIsZero()
    {
        Assert.Equal(0, ResultCollector.Murmur3X86_32(Array.Empty<byte>(), 0));
    }

    [Fact]
    public void Murmur3IsDeterministic()
    {
        var data = Encoding.UTF8.GetBytes("the quick brown fox");
        Assert.Equal(ResultCollector.Murmur3X86_32(data, 0), ResultCollector.Murmur3X86_32(data, 0));
    }
}
