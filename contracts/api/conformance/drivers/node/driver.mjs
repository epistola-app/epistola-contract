// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * The Node.js client's conformance driver.
 *
 * Asks the conformance server what to do, does it with the built client, and reports back. It
 * asserts nothing — the server judges the requests, so the five clients are held to one set of
 * expectations rather than five that drift. See ../../README.md for the driver contract.
 */

import { createHash } from 'node:crypto'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const CLIENT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../clients/nodejs-fetch/dist/index.js')
const {
  ClientIdentity,
  ConsumersApi,
  EpistolaClient,
  GenerationApi,
  JwtSigner,
  ProblemDetailException,
  ResultCollector,
  SystemApi,
  TemplatesApi,
} = await import(CLIENT)

const ACTIONS = {
  ping,
  'list-templates': listTemplates,
  collect,
  problem,
  routing,
  'generate-document': generateDocument,
  'update-consumer': updateConsumer,
  'download-document': downloadDocument,
}

process.exit(await main())

async function main() {
  const baseUrl = process.argv[2]
  if (!baseUrl) {
    console.error('usage: driver.mjs <conformance server base url>')
    return 2
  }
  const instruction = await get(`${baseUrl}/__conformance/action`)
  const action = ACTIONS[instruction.action]
  if (!action) {
    console.error(`unknown action ${instruction.action}`)
    return 2
  }
  try {
    await action(baseUrl, instruction.config)
    await done(baseUrl, null)
    return 0
  } catch (error) {
    await done(baseUrl, `${error?.constructor?.name ?? 'Error'}: ${error?.message ?? error}`)
    console.error(error)
    return 1
  }
}

// --- Actions ---

async function ping(baseUrl, config) {
  await new SystemApi(client(baseUrl, config)).ping({
    pingRequest: {
      name: 'Conformance Driver',
      description: 'Drives the Node.js client through one conformance scenario',
      contact: 'conformance@epistola.app',
    },
  })
}

async function listTemplates(baseUrl, config) {
  const api = new TemplatesApi(client(baseUrl, config))
  for (let i = 0; i < (config.repeat ?? 1); i++) {
    await api.listTemplates({ tenantId: config.tenantId, catalogId: config.catalogId })
  }
}

async function problem(baseUrl, config) {
  try {
    await new TemplatesApi(client(baseUrl, config)).listTemplates({ tenantId: config.tenantId, catalogId: config.catalogId })
    await report(baseUrl, { problemTypeSlug: '<no exception was thrown>' })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemTitle: error.title ?? '<null>',
      problemFieldErrors: error.errors.map((e) => `${e.field}:${e.message}`).join(','),
    })
  }
}

async function collect(baseUrl, config) {
  const handled = []
  const collector = ResultCollector.builder()
    .client(client(baseUrl, config))
    .tenantId(config.tenantId)
    .batchSize(config.batchSize)
    .minIntervalMs(config.minIntervalMs)
    .maxIntervalMs(config.maxIntervalMs)
    .backoffMultiplier(config.multiplier)
    .handler((result) => {
      handled.push(result)
      if (result.sequence === config.failHandlerOnSequence) {
        throw new Error('conformance: deliberate handler failure')
      }
    })
    // Without this the loop swallows collection failures and simply backs off, which reaches the
    // harness as "the client chose not to poll" rather than as the cause.
    .errorHandler((error) => console.error(error))
    .build()

  const loop = collector.start()
  await new Promise((r) => setTimeout(r, config.runForMs))
  collector.stop()
  await Promise.race([loop, new Promise((r) => setTimeout(r, 5_000))])

  const assignment = collector.currentPartitionAssignment
  await report(baseUrl, {
    resultsHandled: handled.length,
    statuses: handled.map((r) => r.status).join(','),
    correlationIds: handled.map((r) => r.correlationId ?? '').join(','),
    handledSequences: handled.map((r) => String(r.sequence)).join(','),
    partitionTotal: assignment ? assignment.total : -1,
  })
}

/**
 * A request body with something in it: required fields, two of the optional ones set, the rest
 * left alone, and a free-form `data` object carrying every JSON type. What the server receives is
 * the generator's serialization, which is the part no client hand-writes and no client's own tests
 * inspect.
 */
async function generateDocument(baseUrl, config) {
  await new GenerationApi(client(baseUrl, config)).generateDocument({
    tenantId: config.tenantId,
    generateDocumentRequest: {
      catalogId: config.catalogId,
      templateId: config.templateId,
      data: config.data,
      correlationId: config.correlationId,
      routingKey: config.routingKey,
    },
  })
}

/**
 * Downloads a document and reports what arrived, byte for byte. The client hands back a Blob; the
 * only thing that has to be identical across clients is the content, and the fixture is
 * deliberately not valid UTF-8 so a stack that decodes it as text fails on the digest.
 */
async function downloadDocument(baseUrl, config) {
  const blob = await new GenerationApi(client(baseUrl, config)).downloadDocument({ tenantId: config.tenantId, documentId: config.documentId })
  const bytes = Buffer.from(await blob.arrayBuffer())
  await report(baseUrl, { byteLength: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') })
}

/**
 * A partial update that sets exactly one field. Everything the caller did not name must stay off
 * the wire: the contract reads a null on these as "clear this".
 */
async function updateConsumer(baseUrl, config) {
  await new ConsumersApi(client(baseUrl, config)).updateConsumer({
    tenantId: config.tenantId,
    consumerId: config.consumerId,
    updateConsumerRequest: { name: config.name },
  })
}

/**
 * One poll to learn the partition assignment from the `_meta` line, then the routing helpers. The
 * values are reported rather than asserted here: the harness holds all five clients to the same
 * answers, which is the only way five independent murmur3 implementations stay in step.
 */
async function routing(baseUrl, config) {
  const collector = ResultCollector.builder()
    .client(client(baseUrl, config))
    .tenantId(config.tenantId)
    .handler(() => undefined)
    .build()

  await collector.collectOnce()

  const keys = config.keys
  const assignment = collector.currentPartitionAssignment
  await report(baseUrl, {
    partitionTotal: assignment ? assignment.total : -1,
    partitions: keys.map((k) => `${k}:${show(collector.partitionFor(k))}`).join(','),
    routed: keys.map((k) => `${k}=${show(collector.routingKeyToMe(k))}`).join(','),
    routedPartitions: keys.map((k) => show(collector.partitionFor(collector.routingKeyToMe(k)))).join(','),
    mineFlags: keys.map((k) => (collector.isMyPartition(k) ? 'true' : 'false')).join(','),
  })
}

/** Renders a missing value the way the other drivers' languages print theirs. */
function show(value) {
  return value === undefined || value === null ? 'null' : String(value)
}

// --- Client assembly ---

/**
 * Builds the client the way the README tells consumers to. The API base path is part of the
 * contract's `servers` entry, so the driver appends it rather than the harness serving the API at
 * the root.
 */
function client(baseUrl, config) {
  const identity = ClientIdentity.builder().nodeId(config.nodeId)
  for (const product of config.products ?? []) {
    identity.product(product.name, product.version)
  }

  const builder = EpistolaClient.builder(`${baseUrl}/api`).identity(identity.build())

  const auth = config.auth ?? 'none'
  if (auth === 'api-key') {
    builder.apiKey(config.apiKey)
  } else if (auth === 'jwt') {
    builder.jwtSigner(
      JwtSigner.builder()
        .consumerId(config.consumerId)
        .privateKey(JwtSigner.parsePrivateKeyPem(config.privateKeyPem))
        .tokenLifetimeSeconds(config.tokenLifetimeSeconds)
        .build(),
    )
  }

  return builder.build()
}

// --- Control plane ---

async function get(url) {
  const response = await fetch(url)
  return response.json()
}

async function post(url, payload) {
  await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
}

function report(baseUrl, values) {
  return post(`${baseUrl}/__conformance/report`, values)
}

function done(baseUrl, error) {
  return post(`${baseUrl}/__conformance/done`, error === null ? {} : { error })
}
