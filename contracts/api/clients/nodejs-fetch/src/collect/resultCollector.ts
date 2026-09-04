// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { isProblemJson, parseProblem } from '../error/problemDetailParser.js'
import { GenerationResultFromJSON, type GenerationResult, type PartitionAssignment } from '../generated/api/models/index.js'
import { ContractMediaTypes } from '../generated/contractMediaTypes.js'
import type { EpistolaClient } from '../http/epistolaClient.js'
import { acceptEncoding, decompress } from '../protocol/compression.js'
import { lines } from '../protocol/ndjson.js'
import { PartitionRouting } from '../protocol/partitionRouting.js'
import { PollBackoff } from '../protocol/pollBackoff.js'

/** The outcome of one collection call. */
export interface CollectResult {
  /** How many results the handler was given. */
  readonly count: number
  /** Whether the server reported more results waiting, in which case the loop polls again at once. */
  readonly hasMore: boolean
}

/** Handles one result as it streams in. A rejected promise or a throw leaves the batch unacknowledged. */
export type ResultHandler = (result: GenerationResult) => void | Promise<void>

/** Told about collection failures (a failed request, a handler that threw). */
export type CollectErrorHandler = (error: unknown) => void

/** Callbacks for observability. */
export interface MetricsListener {
  /** Called after each poll completes, with the error when it failed. */
  onPoll?(count: number, hasMore: boolean, durationMs: number, error: unknown): void
  /** Called when the partition assignment changes. */
  onPartitionChange?(previous: PartitionAssignment | undefined, current: PartitionAssignment): void
}

interface CollectorSettings {
  readonly client: EpistolaClient
  readonly tenantId: string
  readonly batchSize: number
  readonly backoff: PollBackoff
  readonly kickIntervalMs: number
  readonly handler: ResultHandler
  readonly errorHandler: CollectErrorHandler | undefined
  readonly metricsListener: MetricsListener | undefined
  readonly registerShutdownHook: boolean
}

/**
 * Collects generation results via the `/generation/collect` endpoint with NDJSON streaming,
 * compression, and adaptive polling.
 *
 * Results are processed one at a time — the response is never loaded into memory. Results from
 * your node are returned first; orphaned results from dead nodes follow.
 *
 * Features: NDJSON streaming (constant memory); compression (gzip always, zstd when this Node
 * runtime can decode it, chosen by sniffing the stream rather than trusting `Content-Encoding`);
 * adaptive polling (immediate on `hasMore`, exponential backoff when idle, floored at the minimum
 * interval); sequence-based acknowledgement; partition-aware routing-key helpers; metrics via
 * {@link MetricsListener}; serialized {@link collectOnce}; and an optional SIGTERM/SIGINT hook.
 *
 * The collector is driven by an {@link EpistolaClient} so base URL, identity and authentication
 * are reused from the same client passed to the generated API classes.
 *
 * ```ts
 * const collector = ResultCollector.builder()
 *   .client(client)
 *   .tenantId('acme')
 *   .handler(async (result) => console.log(result.requestId, result.status))
 *   .build()
 *
 * await collector.start()   // resolves after stop()
 * ```
 */
export class ResultCollector {
  private running = false
  private currentIntervalMs: number
  private lastAcknowledged: number | undefined
  private assignment: PartitionAssignment | undefined
  private routing: PartitionRouting | undefined
  private pollChain: Promise<unknown> = Promise.resolve()
  private wake: (() => void) | undefined
  private shutdownHook: (() => void) | undefined

  private constructor(private readonly settings: CollectorSettings) {
    this.currentIntervalMs = settings.backoff.initial()
  }

  /** Creates a new {@link ResultCollectorBuilder}. */
  static builder(): ResultCollectorBuilder {
    return new ResultCollectorBuilder()
  }

  /** @internal */
  static assemble(settings: CollectorSettings): ResultCollector {
    return new ResultCollector(settings)
  }

  /** Current partition assignment, updated on each poll from the `_meta` line. */
  get currentPartitionAssignment(): PartitionAssignment | undefined {
    return this.assignment
  }

  /** The highest sequence acknowledged so far — what the next poll sends as `acknowledgeUpTo`. */
  get lastAcknowledgedSequence(): number | undefined {
    return this.lastAcknowledged
  }

  /**
   * The partition a routing key lands on, using the server's hash (murmur3 x86 32-bit, seed 0), or
   * undefined while the assignment is not yet known.
   */
  partitionFor(routingKey: string): number | undefined {
    return this.routing?.partitionFor(routingKey)
  }

  /** Whether a routing key would land on one of this node's partitions. */
  isMyPartition(routingKey: string): boolean {
    return this.routing?.isMine(routingKey) ?? false
  }

  /**
   * A routing key that targets one of this node's partitions: `key` unchanged when it already
   * routes here, otherwise a numbered prefix (`"0:key"`, `"1:key"`, …) that does. Undefined while
   * the assignment is not yet known. See {@link PartitionRouting.routingKeyToMe}.
   */
  routingKeyToMe(key: string): string | undefined {
    return this.routing?.routingKeyToMe(key)
  }

  /**
   * Runs the adaptive poll loop until {@link stop} is called; the returned promise resolves once
   * the loop has ended. A poll that fails is reported to the error handler and backed off from
   * (with jitter); the loop itself does not stop on failure.
   */
  async start(): Promise<void> {
    if (this.running) {
      throw new Error('ResultCollector is already running')
    }
    const { backoff, errorHandler } = this.settings
    this.running = true
    this.currentIntervalMs = backoff.initial()
    this.installShutdownHook()

    try {
      while (this.running) {
        try {
          const result = await this.collectOnce()
          if (!this.running) {
            break
          }
          if (result.hasMore) {
            this.currentIntervalMs = backoff.afterHasMore()
          } else if (result.count > 0) {
            this.currentIntervalMs = backoff.afterResults()
          } else {
            this.currentIntervalMs = backoff.afterIdlePoll(this.currentIntervalMs)
          }
          await this.sleep(this.currentIntervalMs)
        } catch (error) {
          errorHandler?.(error)
          const jitter = Math.random() * (this.currentIntervalMs / 2 + 1)
          this.currentIntervalMs = backoff.afterIdlePoll(this.currentIntervalMs)
          await this.sleep(this.currentIntervalMs + jitter)
        }
      }
    } finally {
      this.running = false
      this.removeShutdownHook()
    }
  }

  /** Signals the poll loop to stop after the current collection completes. Safe to call from anywhere. */
  stop(): void {
    this.running = false
    this.wake?.()
  }

  /**
   * Hints that a result is expected soon: shortens the current backoff to the kick interval and
   * wakes the poll loop. Does nothing when the loop is already polling at least that often.
   */
  kick(): void {
    const shortened = this.settings.backoff.afterKick(this.currentIntervalMs, this.settings.kickIntervalMs)
    if (shortened !== this.currentIntervalMs) {
      this.currentIntervalMs = shortened
      this.wake?.()
    }
  }

  /**
   * Performs a single collection call. Concurrent calls are serialized to prevent duplicate
   * delivery. Streams the NDJSON response line by line, awaiting the handler per result. If the
   * handler throws, the sequence is not advanced and the batch is redelivered next call.
   */
  collectOnce(): Promise<CollectResult> {
    const run = this.pollChain.then(() => this.timedCollect())
    this.pollChain = run.catch(() => undefined)
    return run
  }

  private async timedCollect(): Promise<CollectResult> {
    const { metricsListener } = this.settings
    const startedAt = performance.now()
    try {
      const result = await this.doCollect()
      metricsListener?.onPoll?.(result.count, result.hasMore, performance.now() - startedAt, undefined)
      return result
    } catch (error) {
      metricsListener?.onPoll?.(0, false, performance.now() - startedAt, error)
      throw error
    }
  }

  private async doCollect(): Promise<CollectResult> {
    const { client, tenantId, batchSize, handler } = this.settings
    // No acknowledgeUpTo at all until something has been processed: sending 0 would acknowledge
    // sequence 0 rather than mean "nothing".
    const body = this.lastAcknowledged === undefined
      ? { limit: batchSize }
      : { acknowledgeUpTo: this.lastAcknowledged, limit: batchSize }

    const response = await client.fetch(`${client.baseUrl}/tenants/${encodeURIComponent(tenantId)}/generation/collect`, {
      method: 'POST',
      headers: {
        ...client.requestHeaders(),
        'Content-Type': ContractMediaTypes.VENDOR_JSON,
        Accept: ContractMediaTypes.VENDOR_NDJSON,
        'Accept-Encoding': acceptEncoding(),
      },
      body: JSON.stringify(body),
    })

    if (!response.ok) {
      const text = await response.text()
      const problem = isProblemJson(response.headers.get('content-type')) ? parseProblem(text, response.status, response) : undefined
      throw problem ?? new Error(`collect failed: HTTP ${response.status}${text ? ` ${text.slice(0, 200)}` : ''}`)
    }
    if (response.body === null) {
      return { count: 0, hasMore: false }
    }

    let count = 0
    let hasMore = false
    let lastSequenceInBatch: number | undefined

    for await (const line of lines(decompress(response.body))) {
      const trimmed = line.trim()
      if (trimmed === '') continue
      const node = JSON.parse(trimmed) as Record<string, unknown>
      if (node._meta === true) {
        hasMore = node.hasMore === true
        this.updatePartitionAssignment(node)
        break
      }
      const result = GenerationResultFromJSON(node)
      await handler(result)
      lastSequenceInBatch = result.sequence
      count++
    }

    // Deliberately after the loop: a handler that threw skipped this, so the whole batch comes
    // back. Acknowledging "what we managed" would strand the rest behind a cursor that cannot
    // retreat.
    if (lastSequenceInBatch !== undefined) {
      this.lastAcknowledged = lastSequenceInBatch
    }
    return { count, hasMore }
  }

  private updatePartitionAssignment(meta: Record<string, unknown>): void {
    const partitions = meta.partitions
    if (partitions === null || typeof partitions !== 'object') return
    const { total, mine, hash } = partitions as { total?: unknown; mine?: unknown; hash?: unknown }
    if (typeof total !== 'number' || !Array.isArray(mine)) return
    const current: PartitionAssignment = {
      total,
      mine: mine.map((partition) => Number(partition)),
      hash: (typeof hash === 'string' ? hash : 'murmur3') as PartitionAssignment['hash'],
    }
    const previous = this.assignment
    if (previous && previous.total === current.total && previous.hash === current.hash && sameNumbers(previous.mine, current.mine)) {
      return
    }
    this.assignment = current
    this.routing = PartitionRouting.of(current.total, current.mine)
    this.settings.metricsListener?.onPartitionChange?.(previous, current)
  }

  private sleep(durationMs: number): Promise<void> {
    if (durationMs <= 0 || !this.running) {
      return Promise.resolve()
    }
    return new Promise((resolve) => {
      const finish = () => {
        clearTimeout(timer)
        this.wake = undefined
        resolve()
      }
      const timer = setTimeout(finish, durationMs)
      this.wake = finish
    })
  }

  private installShutdownHook(): void {
    if (!this.settings.registerShutdownHook || this.shutdownHook !== undefined) return
    this.shutdownHook = () => this.stop()
    process.once('SIGTERM', this.shutdownHook)
    process.once('SIGINT', this.shutdownHook)
  }

  private removeShutdownHook(): void {
    if (this.shutdownHook === undefined) return
    process.removeListener('SIGTERM', this.shutdownHook)
    process.removeListener('SIGINT', this.shutdownHook)
    this.shutdownHook = undefined
  }
}

/** Fluent builder for {@link ResultCollector}. */
export class ResultCollectorBuilder {
  private clientValue: EpistolaClient | undefined
  private tenantIdValue: string | undefined
  private batchSizeValue = 100
  private minIntervalMsValue = 1_000
  private maxIntervalMsValue = 30_000
  private kickIntervalMsValue = 3_000
  private backoffMultiplierValue = 3.0
  private handlerValue: ResultHandler | undefined
  private errorHandlerValue: CollectErrorHandler | undefined
  private metricsListenerValue: MetricsListener | undefined
  private registerShutdownHookValue = false

  /** The {@link EpistolaClient} to poll with (reuses its base URL, identity and authentication). */
  client(client: EpistolaClient): this {
    this.clientValue = client
    return this
  }

  /** The tenant whose results to collect. */
  tenantId(tenantId: string): this {
    this.tenantIdValue = tenantId
    return this
  }

  /** Maximum results per collection (default: 100). */
  batchSize(size: number): this {
    if (!Number.isInteger(size) || size < 1 || size > 10_000) {
      throw new RangeError('batchSize must be between 1 and 10000')
    }
    this.batchSizeValue = size
    return this
  }

  /** Minimum poll interval when results are flowing, in milliseconds (default: 1000). */
  minIntervalMs(ms: number): this {
    requirePositive(ms, 'minIntervalMs')
    this.minIntervalMsValue = ms
    return this
  }

  /** Maximum poll interval when idle, in milliseconds (default: 30000). */
  maxIntervalMs(ms: number): this {
    requirePositive(ms, 'maxIntervalMs')
    this.maxIntervalMsValue = ms
    return this
  }

  /** Wait used by {@link ResultCollector.kick} to override the backoff, in milliseconds (default: 3000). */
  kickIntervalMs(ms: number): this {
    requirePositive(ms, 'kickIntervalMs')
    this.kickIntervalMsValue = ms
    return this
  }

  /** Exponential backoff multiplier applied on each empty poll (default: 3.0). */
  backoffMultiplier(multiplier: number): this {
    if (!(multiplier > 1.0)) {
      throw new RangeError('backoffMultiplier must be > 1.0')
    }
    this.backoffMultiplierValue = multiplier
    return this
  }

  /** Handler awaited for each result as it streams in. */
  handler(handler: ResultHandler): this {
    this.handlerValue = handler
    return this
  }

  /** Optional handler for collection failures; without one they are backed off from silently. */
  errorHandler(handler: CollectErrorHandler): this {
    this.errorHandlerValue = handler
    return this
  }

  /** Optional metrics listener for observability. */
  metricsListener(listener: MetricsListener): this {
    this.metricsListenerValue = listener
    return this
  }

  /**
   * Whether {@link ResultCollector.start} listens for SIGTERM and SIGINT and stops the loop on
   * either (default: false). Off by default because a library that installs signal handlers changes
   * what those signals do to the host process: with a listener present, Node no longer exits on
   * SIGINT by itself. Turn it on when the collector is the process's reason to be running.
   */
  registerShutdownHook(register: boolean): this {
    this.registerShutdownHookValue = register
    return this
  }

  /** Builds the {@link ResultCollector}. */
  build(): ResultCollector {
    if (this.clientValue === undefined) {
      throw new RangeError('client is required')
    }
    if (this.tenantIdValue === undefined) {
      throw new RangeError('tenantId is required')
    }
    if (this.handlerValue === undefined) {
      throw new RangeError('handler is required')
    }
    return ResultCollector.assemble({
      client: this.clientValue,
      tenantId: this.tenantIdValue,
      batchSize: this.batchSizeValue,
      backoff: PollBackoff.of(this.minIntervalMsValue, this.maxIntervalMsValue, this.backoffMultiplierValue),
      kickIntervalMs: this.kickIntervalMsValue,
      handler: this.handlerValue,
      errorHandler: this.errorHandlerValue,
      metricsListener: this.metricsListenerValue,
      registerShutdownHook: this.registerShutdownHookValue,
    })
  }
}

function requirePositive(value: number, name: string): void {
  if (!(value > 0)) {
    throw new RangeError(`${name} must be positive`)
  }
}

function sameNumbers(a: readonly number[], b: readonly number[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index])
}
