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
  AttributesApi,
  CatalogsApi,
  ClientIdentity,
  CodeListsApi,
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
  TenantsApi,
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
  'list-documents': listDocuments,
  'list-images': listImages,
  'upload-image': uploadImage,
  'import-catalog': importCatalog,
  'install-catalog-release': installCatalogRelease,
  'list-available-catalog-releases': listAvailableCatalogReleases,
  'download-image': downloadImage,
  'delete-image': deleteImage,
  'list-catalog-dependencies': listCatalogDependencies,
  'update-catalog-dependencies': updateCatalogDependencies,
  'create-catalog-release': createCatalogRelease,
  'list-catalog-releases': listCatalogReleases,
  'get-catalog-release': getCatalogRelease,
  'update-catalog-release': updateCatalogRelease,
  'set-catalog-release-theme-settings': setCatalogReleaseThemeSettings,
  'preview-catalog-draft-release': previewCatalogDraftRelease,
  'delete-catalog-release': deleteCatalogRelease,
  'release-catalog-draft': releaseCatalogDraft,
  'preview-catalog-draft-merge': previewCatalogDraftMerge,
  'get-catalog-draft-merge-sides': getCatalogDraftMergeSides,
  'merge-into-catalog-draft': mergeIntoCatalogDraft,
  'list-release-registries': listReleaseRegistries,
  'list-registry-releases': listRegistryReleases,
  'install-registry-release': installRegistryRelease,
  'publish-release-to-registry': publishReleaseToRegistry,
  'deploy-catalog-release': deployCatalogRelease,
  'undeploy-catalog-release': undeployCatalogRelease,
  'create-environment': createEnvironment,
  'update-environment': updateEnvironment,
  'update-tenant': updateTenant,
  'update-code-list-entry-hidden': updateCodeListEntryHidden,
  'update-attribute': updateAttribute,
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
      release: config.release,
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
      release: config.release,
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
    releases: handled.map((r) => `${show(r.registry)}/${show(r.namespace)}/${show(r.catalogKey)}@${show(r.version)}`).join(','),
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
  if (config.variantKey !== undefined) request.variantKey = config.variantKey
  if (config.versionRange !== undefined) request.versionRange = config.versionRange
  if (config.environmentKey !== undefined) request.environmentKey = config.environmentKey
  // Attribute criteria pass through as the scenario writes them: each member it leaves out stays out.
  if (config.attributes !== undefined) request.attributes = config.attributes
  const response = await new GenerationApi(client(baseUrl, config)).generateDocumentRaw({
    tenantKey: config.tenantKey,
    generateDocumentRequest: request,
  })
  const job = await response.value()
  await report(baseUrl, {
    requestId: show(job.requestId),
    status: show(job.status),
    jobType: show(job.jobType),
    total: job.total ?? 'null',
    location: show(response.raw.headers.get('Location')),
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
 * Lists documents with the catalog and template filters the scenario sets, each sent only when set,
 * and reports what the client made of the page.
 */
async function listDocuments(baseUrl, config) {
  const request = { tenantKey: config.tenantKey }
  for (const name of ['templateKey', 'registry', 'namespace', 'catalogKey']) {
    if (config[name] !== undefined) request[name] = config[name]
  }
  const { items } = await new GenerationApi(client(baseUrl, config)).listDocuments(request)
  await report(baseUrl, {
    catalogs: items.map((d) => `${show(d.registry)}/${show(d.namespace)}/${show(d.catalogKey)}`).join(','),
    templateKeys: items.map((d) => d.templateKey).join(','),
    variantKeys: items.map((d) => d.variantKey).join(','),
  })
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
    release: config.release,
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
    release: config.release,
    file,
    name: config.name,
  })
  await report(baseUrl, { imageKey: image.key, imageName: image.name })
}

/**
 * Imports a catalog archive, passed as a File for its name and type like an image upload.
 * `registry` and `namespace` go out only when the scenario sets them. A refusal is reported by its
 * problem.
 */
async function importCatalog(baseUrl, config) {
  const file = new File([Buffer.from(config.fileBase64, 'base64')], config.filename, { type: config.fileContentType })
  const request = { tenantKey: config.tenantKey, file }
  for (const name of ['registry', 'namespace']) {
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
    version: show(imported.version),
    newlyInstalled: show(imported.newlyInstalled),
    total: show(imported.total),
  })
}

/**
 * Installs a release a catalog's source offers, named in the path. A refusal or a
 * failing source is reported by its problem, missing dependencies included.
 */
async function installCatalogRelease(baseUrl, config) {
  let installed
  try {
    installed = await new CatalogsApi(client(baseUrl, config)).installCatalogRelease({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      version: config.version,
    })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemMissingDependencies: error.missingDependencies
        .map((d) => `${d.registry}/${d.namespace}/${d.catalogKey}@${d.version}:${d.reason}`)
        .join(','),
    })
    return
  }
  await report(baseUrl, {
    registry: show(installed.registry),
    namespace: show(installed.namespace),
    catalogKey: show(installed.catalogKey),
    version: show(installed.version),
    newlyInstalled: show(installed.newlyInstalled),
    total: show(installed.total),
    installedDependencies: (installed.installedDependencies ?? []).map(show).join(','),
    presentDependencies: (installed.presentDependencies ?? []).map(show).join(','),
  })
}

/**
 * Lists the releases a catalog's source offers that the tenant lacks, reporting every one
 * in order. A failing source is reported by its problem.
 */
async function listAvailableCatalogReleases(baseUrl, config) {
  let releases
  try {
    releases = await new CatalogsApi(client(baseUrl, config)).listAvailableCatalogReleases({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
    })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, { problemTypeSlug: error.typeSlug ?? '<null>', problemStatus: error.statusCode })
    return
  }
  await report(baseUrl, {
    registry: show(releases.registry),
    namespace: show(releases.namespace),
    catalogKey: show(releases.catalogKey),
    availableComparedTo: releases.available.map((r) => `${show(r.version)}=${show(r.comparedTo)}`).join(';'),
    availableChanges: releases.available
      .map((r) => `${show(r.version)}=${r.changes.map((c) => `${show(c.type)}/${show(c.key)}:${show(c.change)}`).join('+')}`)
      .join(';'),
    availableDependencies: releases.available
      .map(
        (r) =>
          `${show(r.version)}=${r.dependencies
            .map((d) => `${show(d.registry)}/${show(d.namespace)}/${show(d.catalogKey)}@${show(d.version)}:${show(d.present)}`)
            .join('+')}`,
      )
      .join(';'),
  })
}

async function downloadImage(baseUrl, config) {
  const blob = await new ImagesApi(client(baseUrl, config)).downloadImageContent({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
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
    release: config.release,
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
    release: config.release,
  })
  await reportDependencies(baseUrl, response.items)
}

/**
 * Declares a catalog's dependencies, each pinning an exact release or, in a draft, naming a draft
 * (`releaseId`). An entry's `registry`, `version` and `releaseId` go out only when the scenario
 * gives them: omitted `registry` means the server's default registry. The response writes every
 * registry in full, and a null `version` or `releaseId` as null; both are reported exactly as
 * parsed.
 */
async function updateCatalogDependencies(baseUrl, config) {
  await conditionalUpdate(
    baseUrl,
    () => new CatalogsApi(client(baseUrl, config)).updateCatalogDependenciesRaw({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      release: config.release,
      // Each entry as given: a `registry`, `version` or `releaseId` the scenario leaves out stays
      // undefined, which the generated serializer keeps off the wire.
      updateCatalogDependenciesRequest: { dependencies: config.dependencies },
      ...ifMatch(config),
    }),
    (response) => dependencyFields(response.items),
  )
}

function reportDependencies(baseUrl, items) {
  return report(baseUrl, dependencyFields(items))
}

function dependencyFields(items) {
  return {
    aliases: items.map((d) => show(d.alias)).join(','),
    registries: items.map((d) => show(d.registry)).join(','),
    namespaces: items.map((d) => show(d.namespace)).join(','),
    catalogKeys: items.map((d) => show(d.catalogKey)).join(','),
    versions: items.map((d) => show(d.version)).join(','),
    releaseIds: items.map((d) => show(d.releaseId)).join(','),
    contentHashes: items.map((d) => show(d.contentHash)).join(','),
  }
}

/** Starts a draft release; `from` (a version or a draft id) goes out only when the scenario names one. */
async function createCatalogRelease(baseUrl, config) {
  const request = { name: config.name }
  if (config.from !== undefined) request.from = config.from
  const response = await new CatalogsApi(client(baseUrl, config)).createCatalogReleaseRaw({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    createCatalogReleaseRequest: request,
  })
  const release = await response.value()
  await report(baseUrl, {
    version: show(release.version),
    releaseId: show(release.releaseId),
    name: show(release.name),
    status: show(release.status),
    base: show(release.base),
    createdFrom: show(release.createdFrom),
    releasedAt: showInstant(release.releasedAt),
    location: show(response.raw.headers.get('Location')),
  })
}

/** Lists a catalog's releases, drafts included; the `status` filter goes out only when the scenario sets one. */
async function listCatalogReleases(baseUrl, config) {
  const request = { tenantKey: config.tenantKey, registry: config.registry, namespace: config.namespace, catalogKey: config.catalogKey }
  if (config.status !== undefined) request.status = config.status
  if (config.contentHash !== undefined) request.contentHash = config.contentHash
  const { items } = await new CatalogsApi(client(baseUrl, config)).listCatalogReleases(request)
  await report(baseUrl, {
    versions: items.map((v) => show(v.version)).join(','),
    releaseIds: items.map((v) => show(v.releaseId)).join(','),
    names: items.map((v) => show(v.name)).join('|'),
    statuses: items.map((v) => show(v.status)).join(','),
    bases: items.map((v) => show(v.base)).join(','),
    createdFrom: items.map((v) => show(v.createdFrom)).join(','),
    behindCounts: items.map((v) => show(v.behind.length)).join(','),
    contentHashes: items.map((v) => show(v.contentHash)).join(','),
  })
}

/** Reads one release, with the releases it renders with and the environments that serve it. */
async function getCatalogRelease(baseUrl, config) {
  const release = await new CatalogsApi(client(baseUrl, config)).getCatalogRelease({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
  })
  await report(baseUrl, {
    version: show(release.version),
    releaseId: show(release.releaseId),
    name: show(release.name),
    status: show(release.status),
    releasedAt: showInstant(release.releasedAt),
    fingerprint: show(release.fingerprint),
    contentHash: show(release.contentHash),
    dependencies: (release.dependencies ?? []).map((d) => `${show(d.registry)}/${show(d.namespace)}/${show(d.catalogKey)}@${show(d.version)}`).join(','),
    dependencyAliases: (release.dependencies ?? []).map((d) => show(d.alias)).join(','),
    dependencyContentHashes: (release.dependencies ?? []).map((d) => show(d.contentHash)).join(','),
    deployedTo: (release.deployedTo ?? []).map(show).join(','),
    behind: release.behind
      .map((r) => `${show(r.version)}=${r.changes.map((c) => `${show(c.type)}/${show(c.key)}:${show(c.change)}`).join('+')}`)
      .join(';'),
  })
}

/** Renames a draft (`updateCatalogRelease`); only the name goes out, to its unchanged address. */
async function updateCatalogRelease(baseUrl, config) {
  await conditionalUpdate(
    baseUrl,
    () => new CatalogsApi(client(baseUrl, config)).updateCatalogReleaseRaw({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      release: config.release,
      updateCatalogReleaseRequest: { name: config.name },
      ...ifMatch(config),
    }),
    (release) => ({
      version: show(release.version),
      releaseId: show(release.releaseId),
      name: show(release.name),
      status: show(release.status),
      base: show(release.base),
    }),
  )
}

/**
 * Sets or removes a draft's default theme and presets; no name goes out, and a member or qualifier
 * the scenario does not set stays off the wire. An empty preset list goes out as is.
 */
async function setCatalogReleaseThemeSettings(baseUrl, config) {
  const request = {}
  if (config.defaultThemeKey != null) {
    request.defaultTheme = { key: config.defaultThemeKey }
    if (config.defaultThemeCatalogKey != null) request.defaultTheme.catalogKey = config.defaultThemeCatalogKey
  }
  if (config.clearDefaultTheme != null) request.clearDefaultTheme = config.clearDefaultTheme
  if (config.presets != null) {
    request.presets = config.presets.map((entry) => {
      const preset = { key: entry.key, name: entry.name }
      if (entry.description != null) preset.description = entry.description
      return preset
    })
  }
  const release = await new CatalogsApi(client(baseUrl, config)).updateCatalogRelease({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
    updateCatalogReleaseRequest: request,
  })
  const parsed = release.presets ?? []
  await report(baseUrl, {
    releaseId: show(release.releaseId),
    name: show(release.name),
    defaultThemeKey: show(release.defaultTheme?.key),
    defaultThemeCatalogKey: show(release.defaultTheme?.catalogKey),
    presetKeys: parsed.map((preset) => preset.key).join(','),
    presetNames: parsed.map((preset) => preset.name).join('|'),
    presetDescriptions: parsed.map((preset) => show(preset.description)).join('|'),
  })
}

/** Previews the version a draft would be released as, with every reason, in order. */
async function previewCatalogDraftRelease(baseUrl, config) {
  const preview = await new CatalogsApi(client(baseUrl, config)).previewCatalogDraftRelease({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
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
async function deleteCatalogRelease(baseUrl, config) {
  await new CatalogsApi(client(baseUrl, config)).deleteCatalogRelease({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
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
      release: config.release,
      releaseCatalogDraftRequest: request,
    })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemUnreleasedDependencies: error.unreleasedDependencies
        .map((d) => `${d.registry}/${d.namespace}/${d.catalogKey}#${d.releaseId}=${d.releaseName}`)
        .join('|'),
    })
    return
  }
  await report(baseUrl, {
    version: show(response.release.version),
    status: show(response.release.status),
    releaseId: show(response.release.releaseId),
    contentHash: show(response.release.contentHash),
    unchangedContent: show(response.unchangedContent),
    draftsBehind: response.draftsBehind.map(show).join('|'),
    releasedDependencies: response.releasedDependencies
      .map((d) => `${show(d.registry)}/${show(d.namespace)}/${show(d.catalogKey)}@${show(d.version)}`)
      .join(','),
  })
}

/**
 * Previews merging the release `config.version` into the draft `config.release` (alpha), the
 * release as the `version` query parameter.
 */
async function previewCatalogDraftMerge(baseUrl, config) {
  const preview = await new CatalogsApi(client(baseUrl, config)).previewCatalogDraftMerge({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
    version: config.version,
  })
  await report(baseUrl, {
    draft: show(preview.draft),
    base: show(preview.base),
    release: show(preview.release),
    alreadyMerged: show(preview.alreadyMerged),
    resources: preview.resources
      .map((r) => `${show(r.resourceType)}/${show(r.resourceKey)}:${show(r.outcome)}:${show(r.draftChange)}:${show(r.releaseChange)}`)
      .join(','),
    resourceNames: preview.resources.map((r) => show(r.name)).join('|'),
    resourceNeeds: preview.resources
      .filter((r) => r.needs && r.needs.length > 0)
      .map((r) => `${show(r.resourceType)}/${show(r.resourceKey)}=${r.needs.join('+')}`)
      .join(';'),
    unchanged: show(preview.unchanged),
    token: show(preview.token),
    missingBase: preview.missingBase ? `${preview.missingBase.version}@${preview.missingBase.fingerprint}` : 'null',
    missingBaseContentHash: show(preview.missingBase?.contentHash),
  })
}

/** Reads the three sides of `config.entry` in merging `config.version` into the draft `config.release` (alpha). */
async function getCatalogDraftMergeSides(baseUrl, config) {
  const sides = await new CatalogsApi(client(baseUrl, config)).getCatalogDraftMergeSides({
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
    version: config.version,
    entry: config.entry,
  })
  const side = (content) => (content == null ? 'null' : `${show(content.type)}/${show(content.key)}:${show(content.name)}`)
  await report(baseUrl, {
    entry: show(sides.entry),
    mergeBase: show(sides.mergeBase),
    token: show(sides.token),
    base: side(sides.base),
    draft: side(sides.draft),
    release: side(sides.release),
    draftMembers: sides.draft == null ? 'null' : Object.keys(sides.draft).sort().join(','),
  })
}

/** Reports only a problem's slug and status. */
async function reportProblemOnly(baseUrl, error) {
  if (!(error instanceof ProblemDetailException)) throw error
  await report(baseUrl, { problemTypeSlug: error.typeSlug ?? '<null>', problemStatus: error.statusCode })
}

/** Lists the release registries (alpha), each as `name:kind`. */
async function listReleaseRegistries(baseUrl, config) {
  const registries = await new CatalogsApi(client(baseUrl, config)).listReleaseRegistries({ tenantKey: config.tenantKey })
  await report(baseUrl, { registries: registries.items.map((r) => `${show(r.name)}:${show(r.kind)}`).join(',') })
}

/**
 * Lists the releases of a catalog in a release registry (alpha), each as
 * `version:fingerprint:releaseId:base`, the base as `version@fingerprint`, a missing value as `null`.
 */
async function listRegistryReleases(baseUrl, config) {
  let releases
  try {
    releases = await new CatalogsApi(client(baseUrl, config)).listRegistryReleases({
      tenantKey: config.tenantKey,
      name: config.name,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
    })
  } catch (error) {
    await reportProblemOnly(baseUrl, error)
    return
  }
  await report(baseUrl, {
    registry: show(releases.registry),
    namespace: show(releases.namespace),
    catalogKey: show(releases.catalogKey),
    releases: releases.items
      .map((r) => `${show(r.version)}:${show(r.fingerprint)}:${show(r.releaseId)}:${r.base == null ? 'null' : `${show(r.base.version)}@${show(r.base.fingerprint)}`}`)
      .join(','),
    contentHashes: releases.items.map((r) => show(r.contentHash)).join(','),
    baseContentHashes: releases.items.map((r) => show(r.base?.contentHash)).join(','),
  })
}

/** Installs a release from a release registry (alpha). A refusal is reported by its problem. */
async function installRegistryRelease(baseUrl, config) {
  let installed
  try {
    installed = await new CatalogsApi(client(baseUrl, config)).installRegistryRelease({
      tenantKey: config.tenantKey,
      name: config.name,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      version: config.version,
    })
  } catch (error) {
    await reportProblemOnly(baseUrl, error)
    return
  }
  await report(baseUrl, {
    registry: show(installed.registry),
    namespace: show(installed.namespace),
    catalogKey: show(installed.catalogKey),
    version: show(installed.version),
    newlyInstalled: show(installed.newlyInstalled),
    total: show(installed.total),
  })
}

/** Publishes a released release to the release registry `config.to` (alpha). */
async function publishReleaseToRegistry(baseUrl, config) {
  let published
  try {
    published = await new CatalogsApi(client(baseUrl, config)).publishReleaseToRegistry({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      release: config.release,
      publishReleaseToRegistryRequest: { to: config.to },
    })
  } catch (error) {
    await reportProblemOnly(baseUrl, error)
    return
  }
  await report(baseUrl, {
    status: show(published.status),
    registry: show(published.registry),
    version: show(published.version),
    digest: show(published.digest),
  })
}

/**
 * Merges the release `config.version` into the draft `config.release` (alpha), with
 * `config.resolutions` when present. A refusal is reported by its problem, with the conflicts it
 * lists and the read-only reason it gives.
 */
async function mergeIntoCatalogDraft(baseUrl, config) {
  const request = { release: config.version }
  if (config.resolutions !== undefined) request.resolutions = config.resolutions
  if (config.mergedContent !== undefined) request.mergedContent = config.mergedContent
  if (config.expectedToken !== undefined) request.expectedToken = config.expectedToken
  let merged
  try {
    merged = await new CatalogsApi(client(baseUrl, config)).mergeIntoCatalogDraft({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      release: config.release,
      mergeIntoCatalogDraftRequest: request,
    })
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemUnresolvedConflicts: error.unresolvedConflicts.join(','),
      problemReadOnlyReason: show(error.readOnlyReason),
      problemMissingBase: error.missingBaseVersion === undefined ? 'null' : `${error.missingBaseVersion}@${error.missingBaseFingerprint}`,
    })
    return
  }
  await report(baseUrl, {
    release: show(merged.release),
    taken: merged.taken.map(show).join(','),
    removed: merged.removed.map(show).join(','),
    kept: merged.kept.map(show).join(','),
    merged: (merged.merged ?? []).map(show).join(','),
  })
}

/**
 * Deploys one release of a catalog, a released one by its version or a draft by its `releaseId`; the path
 * carries everything and there is no body.
 */
async function deployCatalogRelease(baseUrl, config) {
  const request = {
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
  }
  // Sent only when the scenario sets it, false included; otherwise the query parameter stays off.
  if (config.includeDependencies !== undefined) request.includeDependencies = config.includeDependencies
  if (config.replace !== undefined) request.replace = config.replace
  let deployment
  try {
    deployment = await new EnvironmentsApi(client(baseUrl, config)).deployCatalogRelease(request)
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, {
      problemTypeSlug: error.typeSlug ?? '<null>',
      problemStatus: error.statusCode,
      problemMissingDependencies: error.missingDependencies
        .map((d) => `${d.registry}/${d.namespace}/${d.catalogKey}@${d.version}:${d.reason}`)
        .join(','),
    })
    return
  }
  await report(baseUrl, {
    registry: show(deployment.registry),
    namespace: show(deployment.namespace),
    catalogKey: show(deployment.catalogKey),
    releaseId: show(deployment.releaseId),
    status: show(deployment.status),
    version: show(deployment.version),
    releaseName: show(deployment.releaseName),
    lastUsedAt: showInstant(deployment.lastUsedAt),
    documentsRequested: show(deployment.documentsRequested),
  })
}

/** Undeploys one release of a catalog; the 204 has no body to parse. */
async function undeployCatalogRelease(baseUrl, config) {
  await new EnvironmentsApi(client(baseUrl, config)).undeployCatalogRelease({
    tenantKey: config.tenantKey,
    environmentKey: config.environmentKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
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
      release: config.release,
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

/** The release a content action addresses, as the generated request objects name it. */
function releaseAddress(config) {
  return {
    tenantKey: config.tenantKey,
    registry: config.registry,
    namespace: config.namespace,
    catalogKey: config.catalogKey,
    release: config.release,
  }
}

/**
 * Reads a variant's content through the `Raw` method, which is how a caller reaches the response
 * headers: the `ETag` is reported exactly as received, quotes included.
 */
async function getVariantContent(baseUrl, config) {
  const response = await new VariantsApi(client(baseUrl, config)).getVariantContentRaw({
    ...releaseAddress(config),
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
    ...releaseAddress(config),
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
    ...releaseAddress(config),
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
    ...releaseAddress(config),
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
    ...releaseAddress(config),
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
    ...releaseAddress(config),
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
    ...releaseAddress(config),
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
    total: show(response.page.total),
  })
}

async function getCatalogChanges(baseUrl, config) {
  await reportChanges(baseUrl, await new CatalogsApi(client(baseUrl, config)).getCatalogChanges(releaseAddress(config)))
}

async function markCatalogReady(baseUrl, config) {
  await reportChanges(baseUrl, await new CatalogsApi(client(baseUrl, config)).markCatalogReady(releaseAddress(config)))
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
    releaseIds: items.map((d) => show(d.releaseId)).join(','),
    statuses: items.map((d) => show(d.status)).join(','),
    versions: items.map((d) => show(d.version)).join(','),
    releaseNames: items.map((d) => show(d.releaseName)).join('|'),
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
    releaseIds: items.map((e) => show(e.releaseId)).join(','),
    statuses: items.map((e) => show(e.status)).join(','),
    versions: items.map((e) => show(e.version)).join(','),
    releaseNames: items.map((e) => show(e.releaseName)).join('|'),
    changedAt: items.map((e) => showInstant(e.changedAt)).join(','),
    changedBy: items.map((e) => show(e.changedBy)).join('|'),
    total: show(response.page.total),
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

/** Creates an environment; `production` goes out only when the scenario sets it. */
async function createEnvironment(baseUrl, config) {
  const body = { key: config.key, name: config.name }
  if (config.production !== undefined) body.production = config.production
  const response = await new EnvironmentsApi(client(baseUrl, config)).createEnvironmentRaw({
    tenantKey: config.tenantKey,
    createEnvironmentRequest: body,
  })
  await report(baseUrl, { ...environmentFields(await response.value()), location: show(response.raw.headers.get('Location')) })
}

/** A partial update: each member goes out only when the scenario sets it. A refusal is reported by its problem. */
async function updateEnvironment(baseUrl, config) {
  const body = {}
  if (config.name !== undefined) body.name = config.name
  if (config.production !== undefined) body.production = config.production
  await conditionalUpdate(
    baseUrl,
    () => new EnvironmentsApi(client(baseUrl, config)).updateEnvironmentRaw({
      tenantKey: config.tenantKey,
      environmentKey: config.environmentKey,
      updateEnvironmentRequest: body,
      ...ifMatch(config),
    }),
    environmentFields,
  )
}

function environmentFields(environment) {
  return { key: show(environment.key), name: show(environment.name), production: show(environment.production) }
}

/** Renames a tenant, conditionally when the scenario gives an `ifMatch`. */
async function updateTenant(baseUrl, config) {
  await conditionalUpdate(
    baseUrl,
    () => new TenantsApi(client(baseUrl, config)).updateTenantRaw({
      tenantKey: config.tenantKey,
      updateTenantRequest: { name: config.name },
      ...ifMatch(config),
    }),
    (tenant) => ({ key: show(tenant.key), name: show(tenant.name) }),
  )
}

/** Hides or shows a code-list entry, conditional on the code list's `ETag` when the scenario gives one. */
async function updateCodeListEntryHidden(baseUrl, config) {
  await conditionalUpdate(
    baseUrl,
    () => new CodeListsApi(client(baseUrl, config)).updateCodeListEntryHiddenRaw({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      release: config.release,
      codeListKey: config.codeListKey,
      code: config.code,
      updateCodeListEntryHiddenRequest: { hidden: config.hidden },
      ...ifMatch(config),
    }),
    (entry) => ({ code: show(entry.code), hidden: show(entry.hidden) }),
  )
}

/** Changes an attribute's constraint with only the members the scenario names. */
async function updateAttribute(baseUrl, config) {
  await conditionalUpdate(
    baseUrl,
    () => new AttributesApi(client(baseUrl, config)).updateAttributeRaw({
      tenantKey: config.tenantKey,
      registry: config.registry,
      namespace: config.namespace,
      catalogKey: config.catalogKey,
      release: config.release,
      attributeKey: config.attributeKey,
      // A binding names its catalog by alias, or by nothing for the attribute's own.
      updateAttributeRequest: pick(config, ['allowedValues', 'codeListBinding', 'clearCodeListBinding']),
      ...ifMatch(config),
    }),
    (attribute) => {
      const binding = attribute.codeListBinding
      return {
        key: show(attribute.key),
        allowedValues: (attribute.allowedValues ?? []).join(','),
        codeListBinding: binding == null ? 'null' : `${show(binding.catalogKey)}/${binding.key}`,
      }
    },
  )
}

/** The members of `config` named in `keys` that the scenario sets, and no others. */
function pick(config, keys) {
  return Object.fromEntries(keys.filter((key) => config[key] != null).map((key) => [key, config[key]]))
}

/** `If-Match` only when the scenario gives one: an unset tag must stay off the wire. */
function ifMatch(config) {
  return config.ifMatch !== undefined ? { ifMatch: config.ifMatch } : {}
}

/**
 * Runs a conditional update through its `Raw` method and reports the fields it parsed plus the
 * `ETag` exactly as received, or, when the server refuses it, the problem's slug and status.
 */
async function conditionalUpdate(baseUrl, call, fields) {
  let response
  try {
    response = await call()
  } catch (error) {
    if (!(error instanceof ProblemDetailException)) throw error
    await report(baseUrl, { problemTypeSlug: error.typeSlug ?? '<null>', problemStatus: error.statusCode })
    return
  }
  await report(baseUrl, { ...fields(await response.value()), etag: show(response.raw.headers.get('ETag')) })
}

async function reportBytes(baseUrl, blob) {
  const bytes = Buffer.from(await blob.arrayBuffer())
  await report(baseUrl, { byteLength: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') })
}

/**
 * A partial update that sets only the members the scenario names. Everything the caller did not
 * name must stay off the wire: a server built to a contract before 2.0 reads a null on these as
 * "clear this". An empty `description` is a value, and goes out as is.
 */
async function updateConsumer(baseUrl, config) {
  await conditionalUpdate(
    baseUrl,
    () => new ConsumersApi(client(baseUrl, config)).updateConsumerRaw({
      tenantKey: config.tenantKey,
      consumerId: config.consumerId,
      updateConsumerRequest: pick(config, ['name', 'description', 'clearExpiresAt']),
      ...ifMatch(config),
    }),
    (consumer) => ({
      id: show(consumer.id),
      name: show(consumer.name),
      lastModified: showInstant(consumer.lastModified),
    }),
  )
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
