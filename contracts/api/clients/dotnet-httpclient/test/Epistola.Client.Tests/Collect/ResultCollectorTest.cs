// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text;
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
            "{\"sequence\":1,\"requestId\":\"r1\",\"status\":\"COMPLETED\",\"documentId\":\"d1\"}\n" +
            "{\"sequence\":2,\"requestId\":\"r2\",\"status\":\"FAILED\",\"error\":\"boom\"}\n" +
            "{\"_meta\":true,\"hasMore\":false,\"partitions\":{\"total\":4,\"mine\":[0,2],\"hash\":\"murmur3\"}}\n";

        var received = new List<ResultCollector.GenerationResult>();
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantId("acme")
            .RegisterShutdownHook(false)
            .Handler(received.Add)
            .Build();

        var result = collector.CollectOnce();

        Assert.Equal(2, result.Count);
        Assert.False(result.HasMore);
        Assert.Equal(2, received.Count);
        Assert.Equal("COMPLETED", received[0].Status);
        Assert.Equal("d1", received[0].DocumentId);
        Assert.Equal("boom", received[1].Error);

        Assert.NotNull(collector.CurrentPartitionAssignment);
        Assert.Equal(4, collector.CurrentPartitionAssignment!.Total);
        Assert.Equal(new[] { 0, 2 }, collector.CurrentPartitionAssignment.Mine);
    }

    [Fact]
    public void PartitionHelpersRequireAssignment()
    {
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(""))
            .TenantId("acme")
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
        var ndjson = "{\"_meta\":true,\"hasMore\":false,\"partitions\":{\"total\":8,\"mine\":[0,1,2,3,4,5,6,7],\"hash\":\"murmur3\"}}\n";
        var collector = ResultCollector.Builder()
            .HttpClient(StubClient(ndjson))
            .TenantId("acme")
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
