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
  StencilsApi,
  SystemApi,
  TemplateDataValidationException,
  TemplateSchemaValidator,
  TemplatesApi,
  VariantsApi,
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
  'import-catalog': importCatalog,
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
  'deploy-release': deployRelease,
  'undeploy-release': undeployRelease,
  'set-environment-override': setEnvironmentOverride,
  'list-environment-overrides': listEnvironmentOverrides,
  'clear-environment-override': clearEnvironmentOverride,
  'create-environment': createEnvironment,
  'update-environment': updateEnvironment,
  'update-variant-content': updateVariantContent,
  'get-variant-content': getVariantContent,
  'mark-variant-ready': markVariantReady,
  'mark-template-ready': markTemplateReady,
  'get-stencil-content': getStencilContent,
  'update-stencil-content': updateStencilContent,
  'mark-stencil-ready': markStencilReady,
  'list-stencil-usage': listStencilUsage,
  'get-catalog-changes': getCatalogChanges,
  'mark-catalog-ready': markCatalogReady,
  'list-environment-deployments': listEnvironmentDeployments,
  'list-deployment-history': listDeploymentHistory,
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

/**
 * Imports a catalog archive, passed as a File for its name and type like an image upload.
 * `registry`, `namespace` and `catalogType` go out only when the scenario sets them. A refusal is
 * reported by its problem.
 */
async function importCatalog(baseUrl, config) {
  const file = new File([Buffer.from(config.fileBase64, 'base64')], config.filename, { type: config.fileContentType })
  const request = { tenantKey: config.tenantKey, file }
  for (const name of ['registry', 'namespace', 'catalogType']) {
    if (config[name] !== undefined) request[name] = config[name]
  }
  let imported
  try {
    imported = await new CatalogsApi(client(baseUrl, config)).importCatalog(request)
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, { problemTypeSlug: error.typeSlug ?? '<null>', problemStatus: error.statusCode })
    return
  }
  await report(baseUrl, {
    registry: show(imported.registry),
    namespace: show(imported.namespace),
    catalogKey: show(imported.catalogKey),
    releaseVersion: show(imported.releaseVersion),
    newlyInstalled: show(imported.newlyInstalled),
    total: show(imported.total),
  })
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
 * Declares a catalog's dependencies, each pinning an exact release or, in a draft, naming a draft
 * (`versionId`). An entry's `registry`, `releaseVersion` and `versionId` go out only when the scenario
 * gives them: omitted `registry` means the server's default registry. The response writes every
 * registry in full, and a null `releaseVersion` or `versionId` as null; both are reported exactly as
 * parsed.
 */
async function updateCatalogDependencies(baseUrl, config) {
  const response = await new CatalogsApi(client(baseUrl, config)).updateCatalogDependencies({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
    // Each entry as given: a `registry`, `releaseVersion` or `versionId` the scenario leaves out stays
    // undefined, which the generated serializer keeps off the wire.
    updateCatalogDependenciesRequest: { dependencies: config.dependencies },
  })
  await reportDependencies(baseUrl, response.items)
}

function reportDependencies(baseUrl, items) {
  return report(baseUrl, {
    registries: items.map((d) => show(d.registry)).join(','),
    namespaces: items.map((d) => show(d.namespace)).join(','),
    catalogKeys: items.map((d) => show(d.catalogKey)).join(','),
    releaseVersions: items.map((d) => show(d.releaseVersion)).join(','),
    versionIds: items.map((d) => show(d.versionId)).join(','),
  })
}

/** Starts a draft; `from` (a release number or a draft id) goes out only when the scenario names one. */
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
    versionId: show(version.versionId),
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
    versionIds: items.map((v) => show(v.versionId)).join(','),
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
    versionId: show(version.versionId),
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

/** Renames a draft; only the name goes out, to its unchanged address. */
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
    versionId: show(version.versionId),
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
    base: show(preview.base),
    changeKind: show(preview.changeKind),
    reasons: preview.reasons.map(show).join('|'),
    needed: show(preview.needed),
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
 * Releases a draft; `version`, `notes`, `acknowledgedBehind` and `releaseDependencies` go out only
 * when the scenario sets them. A problem answer is reported by its slug, status and the dependency
 * drafts it lists.
 */
async function releaseCatalogDraft(baseUrl, config) {
  const request = {}
  if (config.version !== undefined) request.version = config.version
  if (config.notes !== undefined) request.notes = config.notes
  if (config.acknowledgedBehind !== undefined) request.acknowledgedBehind = config.acknowledgedBehind
  if (config.releaseDependencies !== undefined) request.releaseDependencies = config.releaseDependencies
  let response
  try {
    response = await new CatalogsApi(client(baseUrl, config)).releaseCatalogDraft({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      catalogVersion: config.catalogVersion,
      releaseCatalogDraftRequest: request,
    })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemUnreleasedDependencies: error.unreleasedDependencies
        .map((d) => `${d.registry}/${d.namespace}/${d.catalogKey}#${d.versionId}=${d.draftName}`)
        .join('|'),
    })
    return
  }
  await report(baseUrl, {
    version: show(response.catalogVersion.version),
    status: show(response.catalogVersion.status),
    versionId: show(response.catalogVersion.versionId),
    draftsBehind: response.draftsBehind.map(show).join('|'),
    releasedDependencies: response.releasedDependencies
      .map((d) => `${show(d.registry)}/${show(d.namespace)}/${show(d.catalogKey)}@${show(d.releaseVersion)}`)
      .join(','),
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
  if (config.replace !== undefined) request.replace = config.replace
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
    lastUsedAt: showInstant(deployment.lastUsedAt),
    documentsRequested: show(deployment.documentsRequested),
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

/**
 * Overrides the version of a catalog an environment uses; `releaseVersion` and `versionId` go
 * out only when the scenario sets them. A refusal is reported by its problem.
 */
async function setEnvironmentOverride(baseUrl, config) {
  const body = {}
  if (config.releaseVersion !== undefined) body.releaseVersion = config.releaseVersion
  if (config.versionId !== undefined) body.versionId = config.versionId
  let override
  try {
    override = await new EnvironmentsApi(client(baseUrl, config)).setEnvironmentOverride({
      tenantKey: config.tenantKey,
      environmentKey: config.environmentKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      setEnvironmentOverrideRequest: body,
    })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, { problemTypeSlug: error.typeSlug ?? '<null>', problemStatus: error.statusCode })
    return
  }
  await report(baseUrl, {
    environmentKey: show(override.environmentKey),
    registry: show(override.registry),
    namespace: show(override.namespace),
    catalogKey: show(override.catalogKey),
    releaseVersion: show(override.releaseVersion),
    versionId: show(override.versionId),
    draftLabel: show(override.draftLabel),
    setAt: showInstant(override.setAt),
  })
}

/**
 * Replaces a variant's content with a one-node template model, sending `config.ifMatch` as `If-Match`
 * when present and no `If-Match` otherwise.
 */
async function updateVariantContent(baseUrl, config) {
  let content
  try {
    content = await new VariantsApi(client(baseUrl, config)).updateVariantContent({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      catalogVersion: config.catalogVersion,
      templateKey: config.templateKey,
      variantKey: config.variantKey,
      updateVariantContentRequest: {
        templateModel: {
          modelVersion: 1,
          root: 'root',
          nodes: { root: { id: 'root', type: 'root', slots: [] } },
          slots: {},
          themeRef: { type: 'inherit' },
        },
      },
      ...(config.ifMatch !== undefined ? { ifMatch: config.ifMatch } : {}),
    })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, { problemTypeSlug: error.typeSlug ?? '<null>', problemStatus: error.statusCode })
    return
  }
  await report(baseUrl, { variantKey: show(content.variantKey) })
}

// --- Content, ready marks and changes ---

/** The catalog version a content action addresses, as the generated request objects name it. */
function versionAddress(config) {
  return {
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    catalogVersion: config.catalogVersion,
  }
}

/**
 * Reads a variant's content through the `Raw` method, which is how a caller reaches the response
 * headers: the `ETag` is reported exactly as received, quotes included.
 */
async function getVariantContent(baseUrl, config) {
  const response = await new VariantsApi(client(baseUrl, config)).getVariantContentRaw({
    ...versionAddress(config),
    templateKey: config.templateKey,
    variantKey: config.variantKey,
  })
  const content = await response.value()
  await report(baseUrl, {
    variantKey: show(content.variantKey),
    templateModelRoot: show(content.templateModel.root),
    status: show(content.status),
    lastModified: showInstant(content.lastModified),
    readyAt: showInstant(content.readyAt),
    etag: show(response.raw.headers.get('ETag')),
  })
}

async function markVariantReady(baseUrl, config) {
  const content = await new VariantsApi(client(baseUrl, config)).markVariantReady({
    ...versionAddress(config),
    templateKey: config.templateKey,
    variantKey: config.variantKey,
  })
  await report(baseUrl, {
    variantKey: show(content.variantKey),
    status: show(content.status),
    lastModified: showInstant(content.lastModified),
    readyAt: showInstant(content.readyAt),
  })
}

async function markTemplateReady(baseUrl, config) {
  const template = await new TemplatesApi(client(baseUrl, config)).markTemplateReady({
    ...versionAddress(config),
    templateKey: config.templateKey,
  })
  await report(baseUrl, {
    key: show(template.key),
    name: show(template.name),
    variantKeys: template.variants.map((v) => show(v.key)).join(','),
    variantStatuses: template.variants.map((v) => show(v.status)).join(','),
  })
}

/** Reads a stencil's content through the `Raw` method, reporting the `ETag` exactly as received. */
async function getStencilContent(baseUrl, config) {
  const response = await new StencilsApi(client(baseUrl, config)).getStencilContentRaw({
    ...versionAddress(config),
    stencilKey: config.stencilKey,
  })
  const content = await response.value()
  await report(baseUrl, {
    stencilKey: show(content.stencilKey),
    contentRoot: show(content.content.root),
    parameterSchemaType: show(content.parameterSchema?.type),
    status: show(content.status),
    lastModified: showInstant(content.lastModified),
    readyAt: showInstant(content.readyAt),
    etag: show(response.raw.headers.get('ETag')),
  })
}

/**
 * Replaces a stencil's content with a one-node template model and `config.parameterSchema`, sending
 * `config.ifMatch` as `If-Match`, and reports the new `ETag` the update answered with.
 */
async function updateStencilContent(baseUrl, config) {
  const response = await new StencilsApi(client(baseUrl, config)).updateStencilContentRaw({
    ...versionAddress(config),
    stencilKey: config.stencilKey,
    updateStencilContentRequest: { content: oneNodeModel(), parameterSchema: config.parameterSchema },
    ...(config.ifMatch !== undefined ? { ifMatch: config.ifMatch } : {}),
  })
  const content = await response.value()
  await report(baseUrl, {
    stencilKey: show(content.stencilKey),
    status: show(content.status),
    readyAt: showInstant(content.readyAt),
    etag: show(response.raw.headers.get('ETag')),
  })
}

async function markStencilReady(baseUrl, config) {
  const response = await new StencilsApi(client(baseUrl, config)).markStencilReady({
    ...versionAddress(config),
    stencilKey: config.stencilKey,
  })
  await report(baseUrl, {
    stencilKey: show(response.stencil.stencilKey),
    status: show(response.stencil.status),
    readyAt: showInstant(response.stencil.readyAt),
    parameterSchemaType: show(response.stencil.parameterSchema?.type),
    notFitting: response.notFitting.map((u) => `${show(u.templateKey)}/${show(u.variantKey)}:${show(u.fits)}`).join(','),
    notFittingProblems: response.notFitting.map((u) => u.problems.join('|')).join(';'),
  })
}

async function listStencilUsage(baseUrl, config) {
  const response = await new StencilsApi(client(baseUrl, config)).listStencilUsage({
    ...versionAddress(config),
    stencilKey: config.stencilKey,
    page: config.page,
    size: config.size,
  })
  await report(baseUrl, {
    templateKeys: response.items.map((u) => show(u.templateKey)).join(','),
    templateNames: response.items.map((u) => show(u.templateName)).join('|'),
    variantKeys: response.items.map((u) => show(u.variantKey)).join(','),
    fits: response.items.map((u) => show(u.fits)).join(','),
    problems: response.items.map((u) => u.problems.join('|')).join(';'),
    pageNumber: show(response.page.number),
    totalElements: show(response.page.totalElements),
  })
}

async function getCatalogChanges(baseUrl, config) {
  await reportChanges(baseUrl, await new CatalogsApi(client(baseUrl, config)).getCatalogChanges(versionAddress(config)))
}

async function markCatalogReady(baseUrl, config) {
  await reportChanges(baseUrl, await new CatalogsApi(client(baseUrl, config)).markCatalogReady(versionAddress(config)))
}

function reportChanges(baseUrl, changes) {
  return report(baseUrl, {
    base: show(changes.base),
    releasable: show(changes.releasable),
    dependenciesChanged: show(changes.dependenciesChanged),
    changes: changes.changes.map((c) => `${show(c.type)}/${show(c.key)}:${show(c.change)}`).join(','),
  })
}

async function listEnvironmentDeployments(baseUrl, config) {
  const { items } = await new EnvironmentsApi(client(baseUrl, config)).listEnvironmentDeployments({
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
  })
  await report(baseUrl, {
    registries: items.map((d) => show(d.registry)).join(','),
    catalogKeys: items.map((d) => show(d.catalogKey)).join(','),
    releaseVersions: items.map((d) => show(d.releaseVersion)).join(','),
    deployedAt: items.map((d) => showInstant(d.deployedAt)).join(','),
    lastUsedAt: items.map((d) => showInstant(d.lastUsedAt)).join(','),
    documentsRequested: items.map((d) => show(d.documentsRequested)).join(','),
  })
}

async function listDeploymentHistory(baseUrl, config) {
  const response = await new EnvironmentsApi(client(baseUrl, config)).listDeploymentHistory({
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
    page: config.page,
    size: config.size,
  })
  const items = response.items
  await report(baseUrl, {
    catalogKeys: items.map((e) => show(e.catalogKey)).join(','),
    actions: items.map((e) => show(e.action)).join(','),
    releaseVersions: items.map((e) => show(e.releaseVersion)).join(','),
    draftLabels: items.map((e) => show(e.draftLabel)).join('|'),
    changedAt: items.map((e) => showInstant(e.changedAt)).join(','),
    changedBy: items.map((e) => show(e.changedBy)).join('|'),
    totalElements: show(response.page.totalElements),
  })
}

/** The one-node template model the content updates send. */
function oneNodeModel() {
  return {
    modelVersion: 1,
    root: 'root',
    nodes: { root: { id: 'root', type: 'root', slots: [] } },
    slots: {},
    themeRef: { type: 'inherit' },
  }
}

async function listEnvironmentOverrides(baseUrl, config) {
  const { items } = await new EnvironmentsApi(client(baseUrl, config)).listEnvironmentOverrides({
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
  })
  await report(baseUrl, {
    catalogKeys: items.map((o) => show(o.catalogKey)).join(','),
    releaseVersions: items.map((o) => show(o.releaseVersion)).join(','),
    versionIds: items.map((o) => show(o.versionId)).join(','),
    draftLabels: items.map((o) => show(o.draftLabel)).join('|'),
  })
}

async function clearEnvironmentOverride(baseUrl, config) {
  await new EnvironmentsApi(client(baseUrl, config)).clearEnvironmentOverride({
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
  })
}

/** Creates an environment; `production` goes out only when the scenario sets it. */
async function createEnvironment(baseUrl, config) {
  const body = { key: config.key, name: config.name }
  if (config.production !== undefined) body.production = config.production
  const environment = await new EnvironmentsApi(client(baseUrl, config)).createEnvironment({
    tenantKey: config.tenantKey,
    createEnvironmentRequest: body,
  })
  await reportEnvironment(baseUrl, environment)
}

/** A partial update: each member goes out only when the scenario sets it. */
async function updateEnvironment(baseUrl, config) {
  const body = {}
  if (config.name !== undefined) body.name = config.name
  if (config.production !== undefined) body.production = config.production
  const environment = await new EnvironmentsApi(client(baseUrl, config)).updateEnvironment({
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
    updateEnvironmentRequest: body,
  })
  await reportEnvironment(baseUrl, environment)
}

async function reportEnvironment(baseUrl, environment) {
  await report(baseUrl, { key: show(environment.key), name: show(environment.name), production: show(environment.production) })
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
