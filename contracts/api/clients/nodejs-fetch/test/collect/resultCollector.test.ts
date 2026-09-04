// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * The collector's read loop against a real HTTP server, plain and gzipped: every line of a batch is
 * read, the meta line updates the assignment, the batch is acknowledged up to the last result
 * handled — and not at all when the handler throws.
 */

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { gzipSync } from 'node:zlib'
import type { ServerResponse } from 'node:http'
import { EpistolaClient, ResultCollector, type GenerationResult, type PartitionAssignment } from '../../src/index.js'
import { sleep, startServer, waitFor, type RecordedRequest, type TestServer } from '../helpers/server.js'

const NDJSON = 'application/vnd.epistola.v1+ndjson'
const RESULTS = [
  { sequence: 501, requestId: '11111111-1111-4111-8111-000000000501', templateId: 'invoice', status: 'COMPLETED', correlationId: 'inv-1', completedAt: '2026-02-01T08:05:00Z' },
  { sequence: 502, requestId: '11111111-1111-4111-8111-000000000502', templateId: 'invoice', status: 'FAILED', error: 'render failed', completedAt: '2026-02-01T08:05:00Z' },
  { sequence: 503, requestId: '11111111-1111-4111-8111-000000000503', templateId: 'invoice', status: 'COMPLETED', completedAt: '2026-02-01T08:05:00Z' },
]
const META = { _meta: true, hasMore: true, count: 3, lastSequence: 503, partitions: { total: 8, mine: [0, 3], hash: 'murmur3' } }
const EMPTY_META = { _meta: true, hasMore: false, count: 0, lastSequence: 503, partitions: { total: 8, mine: [0, 3], hash: 'murmur3' } }

function ndjson(lines: unknown[]): Buffer {
  return Buffer.from(lines.map((line) => JSON.stringify(line)).join('\n') + '\n', 'utf8')
}

function respond(res: ServerResponse, lines: unknown[], gzip = false): void {
  const body = gzip ? gzipSync(ndjson(lines)) : ndjson(lines)
  res.writeHead(200, { 'Content-Type': NDJSON, 'Content-Length': String(body.length), ...(gzip ? { 'Content-Encoding': 'gzip' } : {}) })
  res.end(body)
}

function collector(server: TestServer, handled: GenerationResult[], configure: (b: ReturnType<typeof ResultCollector.builder>) => void = () => undefined): ResultCollector {
  const builder = ResultCollector.builder()
    .client(EpistolaClient.builder(`${server.url}/api`, 'epk_test').build())
    .tenantId('acme-corp')
    .batchSize(5)
    .handler((result) => {
      handled.push(result)
    })
  configure(builder)
  return builder.build()
}

function sentBody(request: RecordedRequest): Record<string, unknown> {
  return JSON.parse(request.body.toString('utf8')) as Record<string, unknown>
}

for (const gzip of [false, true]) {
  test(`every line of a ${gzip ? 'gzipped' : 'plain'} batch is read, the meta line is reached, and the batch is acknowledged`, async () => {
    const server = await startServer((_, res, index) => respond(res, index === 0 ? [...RESULTS, META] : [EMPTY_META], gzip))
    try {
      const handled: GenerationResult[] = []
      const subject = collector(server, handled)

      const first = await subject.collectOnce()
      assert.deepEqual(handled.map((r) => r.sequence), [501, 502, 503])
      assert.deepEqual(handled.map((r) => r.status), ['COMPLETED', 'FAILED', 'COMPLETED'])
      assert.ok(handled[0].completedAt instanceof Date)
      assert.equal(first.count, 3)
      // The meta line comes last, so reaching it at all is the proof the stream was read to the end.
      assert.equal(first.hasMore, true)
      assert.deepEqual(subject.currentPartitionAssignment, { total: 8, mine: [0, 3], hash: 'murmur3' })
      assert.equal(subject.lastAcknowledgedSequence, 503)

      const second = await subject.collectOnce()
      assert.deepEqual(second, { count: 0, hasMore: false })

      const [firstRequest, secondRequest] = server.requests
      assert.equal(firstRequest.method, 'POST')
      assert.equal(firstRequest.path, '/api/tenants/acme-corp/generation/collect')
      assert.match(firstRequest.headers['content-type'], /^application\/vnd\.epistola\.v1\+json/)
      assert.equal(firstRequest.headers.accept, NDJSON)
      assert.match(firstRequest.headers['accept-encoding'], /(^|[ ,])gzip($|[ ,;])/)
      assert.equal(firstRequest.headers.authorization, 'ApiKey epk_test')
      // Nothing has been processed yet, so there is nothing to acknowledge — and not 0 either.
      assert.deepEqual(sentBody(firstRequest), { limit: 5 })
      assert.deepEqual(sentBody(secondRequest), { acknowledgeUpTo: 503, limit: 5 })
    } finally {
      await server.close()
    }
  })
}

test('a handler that throws leaves the batch unacknowledged, so it is redelivered', async () => {
  const server = await startServer((_, res) => respond(res, [...RESULTS, META]))
  try {
    const handled: GenerationResult[] = []
    const errors: unknown[] = []
    const subject = collector(server, handled, (b) =>
      b
        .handler((result) => {
          handled.push(result)
          if (result.sequence === 502) throw new Error('deliberate handler failure')
        })
        .errorHandler((error) => errors.push(error)),
    )

    await assert.rejects(subject.collectOnce(), /deliberate handler failure/)
    assert.equal(subject.lastAcknowledgedSequence, undefined)
    await assert.rejects(subject.collectOnce(), /deliberate handler failure/)
    // 501 then 502 on each poll — never 503, which the handler never saw and so must come back.
    assert.deepEqual(handled.map((r) => r.sequence), [501, 502, 501, 502])
    for (const request of server.requests) {
      assert.equal(sentBody(request).acknowledgeUpTo, undefined)
    }
    assert.equal(errors.length, 0, 'collectOnce reports to its caller, not to the error handler')
  } finally {
    await server.close()
  }
})

test('the poll loop goes again at once on hasMore, then backs off and never faster than its floor', async () => {
  const arrivals: number[] = []
  const server = await startServer((_, res, index) => {
    arrivals.push(performance.now())
    respond(res, index === 0 ? [RESULTS[0], META] : [EMPTY_META])
  })
  try {
    const handled: GenerationResult[] = []
    const subject = collector(server, handled, (b) => b.minIntervalMs(60).maxIntervalMs(2_000).backoffMultiplier(3))
    const loop = subject.start()
    try {
      await sleep(500)
    } finally {
      subject.stop()
      await loop
    }

    assert.equal(handled.length, 1)
    // 0 → 60 → 180 → 540: four polls in 500ms, the first gap immediate, the rest growing.
    assert.ok(arrivals.length >= 3 && arrivals.length <= 6, `unexpected poll count ${arrivals.length}`)
    const gaps = arrivals.slice(1).map((t, i) => t - arrivals[i])
    for (const gap of gaps.slice(1)) {
      assert.ok(gap >= 50, `a gap of ${gap}ms is below the floor`)
    }
    for (let i = 2; i < gaps.length; i++) {
      assert.ok(gaps[i] > gaps[i - 1], `backoff did not grow: ${gaps.join(', ')}`)
    }
  } finally {
    await server.close()
  }
})

test('a failing poll is reported and backed off from; stop() interrupts the wait', async () => {
  const server = await startServer((_, res) => {
    res.writeHead(503, { 'Content-Type': 'application/problem+json' })
    res.end(JSON.stringify({ type: 'about:blank', title: 'Service Unavailable', status: 503 }))
  })
  try {
    const errors: unknown[] = []
    const subject = collector(server, [], (b) => b.minIntervalMs(50).maxIntervalMs(60_000).backoffMultiplier(100).errorHandler((e) => errors.push(e)))
    const loop = subject.start()
    let stoppedAt = performance.now()
    try {
      await waitFor(() => errors.length >= 1)
    } finally {
      // The loop is now waiting out a long backoff; stop must not wait it out.
      stoppedAt = performance.now()
      subject.stop()
      await loop
    }
    assert.ok(performance.now() - stoppedAt < 1_000)
    assert.match(String(errors[0]), /503/)
  } finally {
    await server.close()
  }
})

test('kick() wakes an idle loop early, and the assignment change is reported once', async () => {
  const changes: Array<[PartitionAssignment | undefined, PartitionAssignment]> = []
  const polls: number[] = []
  const server = await startServer((_, res) => respond(res, [EMPTY_META]))
  try {
    // Empty polls back off 50 → 500 → 5000ms: two quick polls, then a wait a kick can be seen to cut short.
    const subject = collector(server, [], (b) =>
      b
        .minIntervalMs(50)
        .maxIntervalMs(10_000)
        .kickIntervalMs(50)
        .backoffMultiplier(10)
        .metricsListener({
          onPoll: (count) => {
            polls.push(count)
          },
          onPartitionChange: (previous, current) => {
            changes.push([previous, current])
          },
        }),
    )
    const loop = subject.start()
    try {
      await waitFor(() => polls.length >= 2) // now waiting 5000ms
      subject.kick()
      await waitFor(() => polls.length >= 3, 1_000)
    } finally {
      subject.stop()
      await loop
    }
    assert.deepEqual(changes, [[undefined, { total: 8, mine: [0, 3], hash: 'murmur3' }]])
    assert.equal(subject.partitionFor('order-42'), 6)
    assert.equal(subject.isMyPartition('invoice-2026-001'), true)
    assert.equal(subject.routingKeyToMe('order-42'), '0:order-42')
  } finally {
    await server.close()
  }
})

test('routing helpers are safe before an assignment is known', async () => {
  const server = await startServer((_, res) => respond(res, [EMPTY_META]))
  try {
    const subject = collector(server, [])
    assert.equal(subject.partitionFor('anything'), undefined)
    assert.equal(subject.isMyPartition('anything'), false)
    assert.equal(subject.routingKeyToMe('anything'), undefined)
  } finally {
    await server.close()
  }
})

test('the builder guards its inputs', () => {
  const client = EpistolaClient.builder('https://x.example/api').build()
  assert.throws(() => ResultCollector.builder().tenantId('t').handler(() => undefined).build(), /client is required/)
  assert.throws(() => ResultCollector.builder().client(client).handler(() => undefined).build(), /tenantId is required/)
  assert.throws(() => ResultCollector.builder().client(client).tenantId('t').build(), /handler is required/)
  assert.throws(() => ResultCollector.builder().batchSize(0), RangeError)
  assert.throws(() => ResultCollector.builder().batchSize(10_001), RangeError)
  assert.throws(() => ResultCollector.builder().backoffMultiplier(1), RangeError)
  assert.throws(() => ResultCollector.builder().minIntervalMs(0), RangeError)
  assert.throws(() => ResultCollector.builder().client(client).tenantId('t').handler(() => undefined).minIntervalMs(500).maxIntervalMs(100).build(), RangeError)
})
