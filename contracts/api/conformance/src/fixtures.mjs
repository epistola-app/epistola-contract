// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Checks a scenario's scripted responses against the contract before anything runs.
 *
 * The scripted backend answers from fixtures somebody typed, and a wrong fixture does not fail
 * honestly: it surfaces as four different clients failing to deserialize, in four different
 * dialects, several minutes into a run. That happened three times while these scenarios were being
 * written — a `requestId` that was not a UUID, a `GenerationJobResponse` with an invented status, a
 * `ConsumerDto` missing required members and carrying an array where the schema says object.
 *
 * So the fixtures are held to the same schemas the clients generate from, at load time, with one
 * message naming the scenario and the offending member.
 */

import { readFileSync } from 'node:fs'

import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import { parse as parseYaml } from 'yaml'

const SPEC_ID = 'https://epistola.local/openapi'

/**
 * Which operation each driver action performs. The harness needs this only to find the response
 * schema a scenario's fixtures have to satisfy; the drivers know it from their generated clients.
 */
const ACTION_OPERATIONS = {
  ping: { method: 'post', path: '/ping' },
  'list-templates': { method: 'get', path: '/tenants/{tenantId}/catalogs/{catalogId}/templates' },
  problem: { method: 'get', path: '/tenants/{tenantId}/catalogs/{catalogId}/templates' },
  'generate-document': { method: 'post', path: '/tenants/{tenantId}/documents/generate' },
  'update-consumer': { method: 'patch', path: '/tenants/{tenantId}/consumers/{consumerId}' },
  'download-document': { method: 'get', path: '/tenants/{tenantId}/documents/{documentId}' },
  collect: { method: 'post', path: '/tenants/{tenantId}/generation/collect' },
  routing: { method: 'post', path: '/tenants/{tenantId}/generation/collect' },
}

let cached = null

function spec(specPath) {
  if (!cached) {
    const document = parseYaml(readFileSync(specPath, 'utf8'))
    const ajv = new Ajv2020({ strict: false, allErrors: true, validateFormats: true })
    addFormats(ajv)
    ajv.addSchema(document, SPEC_ID)
    cached = { document, ajv, validators: new Map() }
  }
  return cached
}

/**
 * Compiles a response schema. Internal `$ref`s are rewritten to absolute ones so they resolve
 * against the registered document rather than against the fragment being compiled.
 */
function validatorFor(schema, key) {
  const { ajv, validators } = cached
  if (!validators.has(key)) {
    validators.set(key, ajv.compile(absolutise(schema)))
  }
  return validators.get(key)
}

function absolutise(node) {
  if (Array.isArray(node)) {
    return node.map(absolutise)
  }
  if (node === null || typeof node !== 'object') {
    return node
  }
  const result = {}
  for (const [key, value] of Object.entries(node)) {
    result[key] = key === '$ref' && typeof value === 'string' && value.startsWith('#/')
      ? `${SPEC_ID}${value}`
      : absolutise(value)
  }
  return result
}

/**
 * @returns an array of human-readable problems; empty means every fixture matches the contract
 */
export function checkFixtures(scenario, specPath) {
  const { document } = spec(specPath)
  const problems = []
  const operation = ACTION_OPERATIONS[scenario.action?.name]

  if (!operation) {
    // A new action without an entry above is not an error in the scenario; it means this check
    // silently stops covering it, which is worth saying out loud.
    return [`${scenario.id}: action "${scenario.action?.name}" has no operation mapping, fixtures unchecked`]
  }

  const responses = document.paths?.[operation.path]?.[operation.method]?.responses
  if (!responses) {
    return [`${scenario.id}: ${operation.method.toUpperCase()} ${operation.path} is not in the spec`]
  }

  for (const [index, entry] of (scenario.script ?? []).entries()) {
    const where = `${scenario.id}: script entry #${index + 1}`
    const status = String(entry.status ?? 200)
    const declared = responses[status]

    if (!declared) {
      problems.push(`${where} answers ${status}, which ${operation.path} does not declare`)
      continue
    }
    if (entry.contentType && declared.content && !declared.content[entry.contentType.split(';')[0]]) {
      problems.push(
        `${where} answers ${entry.contentType} — ${operation.path} declares ` +
          `${Object.keys(declared.content).join(', ')} for ${status}`,
      )
      continue
    }

    const schema = declared.content?.[entry.contentType?.split(';')[0]]?.schema
    if (!schema || schema.format === 'binary') {
      continue
    }

    if (entry.body !== undefined) {
      report(problems, where, validatorFor(schema, `${operation.path}|${operation.method}|${status}`), entry.body)
    }
    for (const [line, value] of (entry.ndjson ?? []).entries()) {
      // The collect stream is one schema per line rather than one for the body: result lines, then
      // a trailing meta line. Getting a result line wrong is exactly the mistake this catches.
      const name = value._meta ? 'CollectMeta' : 'GenerationResult'
      const lineSchema = document.components.schemas[name]
      report(problems, `${where} ndjson line ${line + 1} (${name})`, validatorFor(lineSchema, name), value)
    }
  }

  return problems
}

function report(problems, where, validate, value) {
  if (!validate(value)) {
    for (const error of validate.errors ?? []) {
      problems.push(`${where}${error.instancePath || ''} ${error.message}`)
    }
  }
}
