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
  CatalogsApi,
  ClientIdentity,
  ConsumersApi,
  EnvironmentsApi,
  EpistolaClient,
  GenerationApi,
  ImagesApi,
  JwtSigner,
  ProblemDetailException,
  ResultCollector,
  SystemApi,
  TemplateDataValidationException,
  TemplateSchemaValidator,
  TemplatesApi,
} = await import(CLIENT)

const ACTIONS = {
  ping,
  'list-templates': listTemplates,
  'validate-template-data': validateTemplateData,
  collect,
  problem,
  routing,
  'generate-document': generateDocument,
  'update-consumer': updateConsumer,
  'download-document': downloadDocument,
  'list-images': listImages,
  'upload-image': uploadImage,
  'download-image': downloadImage,
  'delete-image': deleteImage,
  'list-catalog-dependencies': listCatalogDependencies,
  'update-catalog-dependencies': updateCatalogDependencies,
  'create-catalog-draft': createCatalogDraft,
  'list-catalog-versions': listCatalogVersions,
  'get-catalog-version': getCatalogVersion,
  'rename-catalog-draft': renameCatalogDraft,
  'preview-catalog-draft-release': previewCatalogDraftRelease,
  'delete-catalog-draft': deleteCatalogDraft,
  'release-catalog-draft': releaseCatalogDraft,
  'promote-catalog-draft': promoteCatalogDraft,
  'deploy-release': deployRelease,
  'undeploy-release': undeployRelease,
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

/** Reports what the client made of the last listing, `id` included while it is still served. */
async function listTemplates(baseUrl, config) {
  const api = new TemplatesApi(client(baseUrl, config))
  let response
  for (let i = 0; i < (config.repeat ?? 1); i++) {
    response = await api.listTemplates({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      catalogVersion: config.catalogVersion,
    })
  }
  await report(baseUrl, {
    templateKeys: response.items.map((template) => show(template.key)).join(','),
    templateDescriptions: response.items.map((template) => show(template.description)).join('|'),
  })
}

async function problem(baseUrl, config) {
  try {
    await new TemplatesApi(client(baseUrl, config)).listTemplates({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      catalogVersion: config.catalogVersion,
    })
    await report(baseUrl, { problemTypeSlug: '<no exception was thrown>' })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemTitle: error.title ?? '<null>',
      problemFieldErrors: error.errors.map((e) => `${e.field}:${e.message}`).join(','),
      problemMissingFields: error.missingFields.map((f) => `${f.path}:${f.required}`).join(','),
      problemInvalidFields: error.invalidFields.map((f) => `${f.path}:${f.keyword}`).join(','),
    })
  }
}

async function validateTemplateData(baseUrl, config) {
  const validator = new TemplateSchemaValidator(new TemplatesApi(client(baseUrl, config)))
  try {
    await validator.validate(config.tenantKey, config.registry, config.namespace, config.catalogKey, config.templateKey, config.data ?? {})
    await report(baseUrl, { validationFailurePaths: '<accepted>', validationFailureKeywords: '<accepted>' })
  } catch (error) {
    if (!(error instanceof TemplateDataValidationException)) throw error
    await report(baseUrl, {
      validationFailurePaths: error.errors.map((failure) => failure.path).join(','),
      validationFailureKeywords: error.errors.map((failure) => show(failure.keyword)).join(','),
    })
  }
}

async function collect(baseUrl, config) {
  const handled = []
  const collector = ResultCollector.builder()
    .client(client(baseUrl, config))
    .tenantKey(config.tenantKey)
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
    releases: handled.map((r) => `${show(r.registry)}/${show(r.namespace)}/${show(r.catalogKey)}@${show(r.releaseVersion)}`).join(','),
  })
}

/**
 * A request body with something in it: required fields, two of the optional ones set, the rest
 * left alone, and a free-form `data` object carrying every JSON type. What the server receives is
 * the generator's serialization, which is the part no client hand-writes and no client's own tests
 * inspect.
 */
async function generateDocument(baseUrl, config) {
  const request = {
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    templateKey: config.templateKey,
    data: config.data,
    correlationId: config.correlationId,
    routingKey: config.routingKey,
  }
  // Only what the scenario sets: an unset optional must stay off the wire, not go out as null. An
  // unset registry means the server's default, which only the server knows.
  if (config.registry !== undefined) request.registry = config.registry
  if (config.version !== undefined) request.version = config.version
  if (config.environmentKey !== undefined) request.environmentKey = config.environmentKey
  await new GenerationApi(client(baseUrl, config)).generateDocument({
    tenantKey: config.tenantKey,
    generateDocumentRequest: request,
  })
}

/**
 * Downloads a document and reports what arrived, byte for byte. The client hands back a Blob; the
 * only thing that has to be identical across clients is the content, and the fixture is
 * deliberately not valid UTF-8 so a stack that decodes it as text fails on the digest.
 */
async function downloadDocument(baseUrl, config) {
  const blob = await new GenerationApi(client(baseUrl, config)).downloadDocument({ tenantKey: config.tenantKey, documentId: config.documentId })
  await reportBytes(baseUrl, blob)
}

/**
 * Lists images and reports what the client made of them. Keys may be UUIDs or readable names, and an
 * SVG's dimensions are null, which must stay null rather than become 0.
 */
async function listImages(baseUrl, config) {
  const { items } = await new ImagesApi(client(baseUrl, config)).listImages({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    search: config.search,
  })
  await report(baseUrl, {
    imageKeys: items.map((image) => image.key).join(','),
    widths: items.map((image) => show(image.width)).join(','),
    heights: items.map((image) => show(image.height)).join(','),
    mediaTypes: items.map((image) => image.mediaType).join(','),
  })
}

/**
 * Uploads an image from bytes. The generated method takes a Blob, but a plain Blob always goes out
 * with the filename `blob`, and as `application/octet-stream` unless it was given a type. So the
 * driver passes a File, which carries both a name and a type.
 */
async function uploadImage(baseUrl, config) {
  const file = new File([Buffer.from(config.fileBase64, 'base64')], config.filename, { type: config.fileContentType })
  const image = await new ImagesApi(client(baseUrl, config)).uploadImage({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    file,
    name: config.name,
  })
  await report(baseUrl, { imageKey: image.key, imageName: image.name })
}

async function downloadImage(baseUrl, config) {
  const blob = await new ImagesApi(client(baseUrl, config)).downloadImageContent({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    imageKey: config.imageKey,
  })
  await reportBytes(baseUrl, blob)
}

async function deleteImage(baseUrl, config) {
  await new ImagesApi(client(baseUrl, config)).deleteImage({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    imageKey: config.imageKey,
    force: config.force,
  })
}

/** Lists a catalog's declared dependencies, reporting them the way the update action does. */
async function listCatalogDependencies(baseUrl, config) {
  const response = await new CatalogsApi(client(baseUrl, config)).listCatalogDependencies({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
  })
  await reportDependencies(baseUrl, response.items)
}

/**
 * Declares a catalog's dependencies. An entry's `registry` goes out only when the scenario gives
 * one: omitted means the server's default registry. The response writes every registry in full and
 * a `resolvedVersion` that nothing satisfies as null; both are reported exactly as parsed.
 */
async function updateCatalogDependencies(baseUrl, config) {
  const response = await new CatalogsApi(client(baseUrl, config)).updateCatalogDependencies({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    // Each entry as given: a `registry` the scenario leaves out stays undefined, which the
    // generated serializer keeps off the wire.
    updateCatalogDependenciesRequest: { dependencies: config.dependencies },
  })
  await reportDependencies(baseUrl, response.items)
}

function reportDependencies(baseUrl, items) {
  return report(baseUrl, {
    registries: items.map((d) => show(d.registry)).join(','),
    namespaces: items.map((d) => show(d.namespace)).join(','),
    catalogKeys: items.map((d) => show(d.catalogKey)).join(','),
    versions: items.map((d) => show(d.version)).join(','),
    resolvedVersions: items.map((d) => show(d.resolvedVersion)).join(','),
  })
}

/** Starts long-term work; `from` goes out only when the scenario names one. */
async function createCatalogDraft(baseUrl, config) {
  const request = { name: config.name }
  if (config.from !== undefined) request.from = config.from
  const version = await new CatalogsApi(client(baseUrl, config)).createCatalogDraft({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    createCatalogDraftRequest: request,
  })
  await report(baseUrl, {
    version: show(version.version),
    draftId: show(version.draftId),
    isNext: show(version.isNext),
    name: show(version.name),
    status: show(version.status),
    base: show(version.base),
    createdFrom: show(version.createdFrom),
    releasedAt: showInstant(version.releasedAt),
  })
}

/** Lists a catalog's versions; the `status` filter goes out only when the scenario sets one. */
async function listCatalogVersions(baseUrl, config) {
  const request = { tenantKey: config.tenantKey, registry: config.registry, namespace: config.namespace, catalogKey: config.catalogKey }
  if (config.status !== undefined) request.status = config.status
  const { items } = await new CatalogsApi(client(baseUrl, config)).listCatalogVersions(request)
  await report(baseUrl, {
    versions: items.map((v) => show(v.version)).join(','),
    draftIds: items.map((v) => show(v.draftId)).join(','),
    isNext: items.map((v) => show(v.isNext)).join(','),
    names: items.map((v) => show(v.name)).join('|'),
    statuses: items.map((v) => show(v.status)).join(','),
    bases: items.map((v) => show(v.base)).join(','),
    createdFrom: items.map((v) => show(v.createdFrom)).join(','),
    behindCounts: items.map((v) => show(v.behind.length)).join(','),
  })
}

/** Reads one version, with the releases it renders with and the environments that serve it. */
async function getCatalogVersion(baseUrl, config) {
  const version = await new CatalogsApi(client(baseUrl, config)).getCatalogVersion({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
  })
  await report(baseUrl, {
    version: show(version.version),
    draftId: show(version.draftId),
    isNext: show(version.isNext),
    preRelease: show(version.preRelease),
    name: show(version.name),
    status: show(version.status),
    releasedAt: showInstant(version.releasedAt),
    fingerprint: show(version.fingerprint),
    dependencies: (version.dependencies ?? []).map((d) => `${show(d.registry)}/${show(d.namespace)}/${show(d.catalogKey)}@${show(d.releaseVersion)}`).join(','),
    deployedTo: (version.deployedTo ?? []).map(show).join(','),
    behind: version.behind
      .map((r) => `${show(r.version)}=${r.changes.map((c) => `${show(c.type)}/${show(c.key)}:${show(c.change)}`).join('+')}`)
      .join(';'),
  })
}

/** Renames long-term work; only the name goes out, to its unchanged address. */
async function renameCatalogDraft(baseUrl, config) {
  const version = await new CatalogsApi(client(baseUrl, config)).renameCatalogDraft({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    renameCatalogDraftRequest: { name: config.name },
  })
  await report(baseUrl, {
    version: show(version.version),
    draftId: show(version.draftId),
    isNext: show(version.isNext),
    name: show(version.name),
    status: show(version.status),
    base: show(version.base),
  })
}

/** Previews the number a draft would be released as, with every reason, in order. */
async function previewCatalogDraftRelease(baseUrl, config) {
  const preview = await new CatalogsApi(client(baseUrl, config)).previewCatalogDraftRelease({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
  })
  await report(baseUrl, {
    latest: show(preview.latest),
    changeKind: show(preview.changeKind),
    reasons: preview.reasons.map(show).join('|'),
    suggested: show(preview.suggested),
  })
}

/** Discards a draft; the 204 has no body to parse. */
async function deleteCatalogDraft(baseUrl, config) {
  await new CatalogsApi(client(baseUrl, config)).deleteCatalogDraft({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
  })
}

/**
 * Releases a draft (the next version, or long-term work as a pre-release); `version`, `notes` and
 * `acknowledgedBehind` go out only when the scenario sets them.
 */
async function releaseCatalogDraft(baseUrl, config) {
  const request = {}
  if (config.version !== undefined) request.version = config.version
  if (config.notes !== undefined) request.notes = config.notes
  if (config.acknowledgedBehind !== undefined) request.acknowledgedBehind = config.acknowledgedBehind
  const response = await new CatalogsApi(client(baseUrl, config)).releaseCatalogDraft({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    releaseCatalogDraftRequest: request,
  })
  await report(baseUrl, {
    version: show(response.catalogVersion.version),
    status: show(response.catalogVersion.status),
    draftId: show(response.catalogVersion.draftId),
    isNext: show(response.catalogVersion.isNext),
    preRelease: show(response.catalogVersion.preRelease),
    draftsBehind: response.draftsBehind.map(show).join('|'),
  })
}

/**
 * Makes long-term work the next version; `keepPreviousAs` and `acknowledgedBehind` go out only when
 * the scenario sets them.
 */
async function promoteCatalogDraft(baseUrl, config) {
  const request = {}
  if (config.keepPreviousAs !== undefined) request.keepPreviousAs = config.keepPreviousAs
  if (config.acknowledgedBehind !== undefined) request.acknowledgedBehind = config.acknowledgedBehind
  const version = await new CatalogsApi(client(baseUrl, config)).promoteCatalogDraft({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    promoteCatalogDraftRequest: request,
  })
  await report(baseUrl, {
    version: show(version.version),
    draftId: show(version.draftId),
    isNext: show(version.isNext),
    preRelease: show(version.preRelease),
    name: show(version.name),
    status: show(version.status),
    base: show(version.base),
    behind: show(version.behind.length),
  })
}

/** Deploys one release; the path carries everything and there is no body. */
async function deployRelease(baseUrl, config) {
  const request = {
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    releaseVersion: config.releaseVersion,
  }
  // Sent only when the scenario sets it, false included; otherwise the query parameter stays off.
  if (config.includeDependencies !== undefined) request.includeDependencies = config.includeDependencies
  let deployment
  try {
    deployment = await new EnvironmentsApi(client(baseUrl, config)).deployRelease(request)
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemMissingDependencies: error.missingDependencies
        .map((d) => `${d.registry}/${d.namespace}/${d.catalogKey}@${d.releaseVersion}:${d.reason}`)
        .join(','),
    })
    return
  }
  await report(baseUrl, {
    registry: show(deployment.registry),
    namespace: show(deployment.namespace),
    catalogKey: show(deployment.catalogKey),
    releaseVersion: show(deployment.releaseVersion),
  })
}

/** Undeploys one release; the 204 has no body to parse. */
async function undeployRelease(baseUrl, config) {
  await new EnvironmentsApi(client(baseUrl, config)).undeployRelease({
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    releaseVersion: config.releaseVersion,
  })
}

async function reportBytes(baseUrl, blob) {
  const bytes = Buffer.from(await blob.arrayBuffer())
  await report(baseUrl, { byteLength: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') })
}

/**
 * A partial update that sets exactly one field. Everything the caller did not name must stay off
 * the wire: the contract reads a null on these as "clear this".
 */
async function updateConsumer(baseUrl, config) {
  await new ConsumersApi(client(baseUrl, config)).updateConsumer({
    tenantKey: config.tenantKey,
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
    .tenantKey(config.tenantKey)
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

/**
 * Renders a timestamp as the instant it was sent: the generated models parse `date-time` into a
 * Date, whose ISO form always carries milliseconds, so whole seconds drop the `.000` again.
 */
function showInstant(value) {
  return value === undefined || value === null ? 'null' : value.toISOString().replace(/\.000Z$/, 'Z')
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
