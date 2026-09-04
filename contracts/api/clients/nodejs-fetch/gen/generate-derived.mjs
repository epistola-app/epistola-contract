#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Build-time generator: reads the bundled OpenAPI spec and emits the derived TypeScript sources
 * into the hand-written package. Mirrors the Kotlin build's generateContractVersionResource /
 * generateClientIdentityConstants / generateProblemSlugs / generateValidation tasks, the .NET
 * Epistola.Client.Gen program and the Python gen/generate_derived.py — and reads the same parts of
 * the spec that contracts/api/build-logic/contract-spec-model.gradle.kts does, so a new constraint
 * keyword or a change to a registry's shape is the same edit here as there.
 *
 *     node gen/generate-derived.mjs <openapi.yaml> <output-dir>
 *
 * Emits, into <output-dir>:
 *     contractVersion.ts      CONTRACT_VERSION, from info.version
 *     contractIdentity.ts     ContractIdentity, from x-client-identity
 *     contractMediaTypes.ts   ContractMediaTypes, from the media types the operations declare
 *     contractOperations.ts   CONTRACT_OPERATIONS — method, path template and the response media
 *                             types of every operation, which is what the Accept header is built from
 *     knownProblemSlugs.ts    KnownProblemSlugs + GENERATED_PROBLEM_TYPE_BASE + ProblemExtensionMembers,
 *                             from x-problem-types and the problem schemas it names
 *     modelValidation.ts      MODEL_CONSTRAINTS + validateModel() + per-model validators, from the
 *                             schema constraints
 *     index.ts                re-exports the above
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { parse as parseYaml } from 'yaml'

const HEADER = '// Generated from the bundled OpenAPI spec by gen/generate-derived.mjs — do not edit.\n'
const HTTP_METHODS = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch']

main(process.argv.slice(2))

function main(argv) {
  if (argv.length !== 2) {
    console.error('usage: generate-derived.mjs <openapi.yaml> <output-dir>')
    process.exit(1)
  }
  const [specPath, outDir] = argv
  const root = parseYaml(readFileSync(specPath, 'utf8'))
  const model = specModel(root)

  mkdirSync(outDir, { recursive: true })
  write(outDir, 'contractVersion.ts', generateContractVersion(model))
  write(outDir, 'contractIdentity.ts', generateContractIdentity(model))
  write(outDir, 'contractMediaTypes.ts', generateContractMediaTypes(model))
  write(outDir, 'contractOperations.ts', generateContractOperations(model))
  write(outDir, 'knownProblemSlugs.ts', generateProblemSlugs(model))
  write(outDir, 'modelValidation.ts', generateModelValidation(model))
  write(outDir, 'index.ts', generateIndex())
}

// --- The spec model (the same reading as contract-spec-model.gradle.kts) ---

function specModel(root) {
  const version = root?.info?.version
  if (typeof version !== 'string') {
    fail('the bundled spec has no info.version')
  }

  const registry = root['x-problem-types']
  if (!isObject(registry)) {
    fail('bundled spec has no x-problem-types extension — the problem-slug constants cannot be generated')
  }
  const problemTypeBase = registry.base
  if (typeof problemTypeBase !== 'string' || problemTypeBase === '') {
    fail('x-problem-types.base is missing from the bundled spec')
  }
  const rawTypes = Array.isArray(registry.types) ? registry.types : []
  if (rawTypes.length < 8) {
    fail(`x-problem-types lists only ${rawTypes.length} problem types (expected at least 8) — was the registry truncated?`)
  }
  const problemTypes = rawTypes.map((entry) => {
    if (typeof entry?.slug !== 'string') {
      fail(`an x-problem-types entry has no slug: ${JSON.stringify(entry)}`)
    }
    return {
      slug: entry.slug,
      status: entry.status,
      schema: entry.schema,
      description: String(entry.description ?? '').replace(/\s+/g, ' ').trim(),
      constantName: entry.slug.toUpperCase().replace(/-/g, '_'),
    }
  })

  const identity = root['x-client-identity']
  if (!isObject(identity)) {
    fail('the bundled spec has no x-client-identity extension — the client-identity constants cannot be generated')
  }
  const clientIdentity = {}
  for (const key of ['nodeIdHeader', 'contractProduct', 'userAgentProductSeparator', 'userAgentVersionSeparator']) {
    if (typeof identity[key] !== 'string') {
      fail(`x-client-identity.${key} is missing from the bundled spec`)
    }
    clientIdentity[key] = identity[key]
  }

  // The versioned vendor media types, taken from what the spec's operations actually declare
  // rather than from a literal repeated in each module.
  const vendorMediaTypePattern = /^application\/vnd\.epistola\.v\d+\+(json|ndjson)$/
  const vendorMediaTypes = {}
  for (const key of mediaTypeKeys(root)) {
    const match = vendorMediaTypePattern.exec(key)
    if (match && !(match[1] in vendorMediaTypes)) {
      vendorMediaTypes[match[1]] = key
    }
  }
  for (const suffix of ['json', 'ndjson']) {
    if (!vendorMediaTypes[suffix]) {
      fail(`the bundled spec declares no application/vnd.epistola.v{n}+${suffix} media type — the hand-written request paths generate their content types from it`)
    }
  }

  const schemas = root?.components?.schemas
  if (!isObject(schemas)) {
    fail('the bundled spec has no components.schemas')
  }

  // The members each problem schema adds to the RFC 9457 base.
  const propertiesOf = (schemaName) => {
    const schema = schemas[schemaName]
    if (!isObject(schema)) {
      fail(`x-problem-types names schema '${schemaName}', which components.schemas has not`)
    }
    const direct = Object.keys(schema.properties ?? {})
    const composed = (Array.isArray(schema.allOf) ? schema.allOf : [])
      .filter((branch) => !('$ref' in branch))
      .flatMap((branch) => Object.keys(branch.properties ?? {}))
    return new Set([...direct, ...composed])
  }
  const baseProblemProperties = propertiesOf('ProblemDetail')
  const problemExtensionMembers = {}
  for (const schemaName of new Set(problemTypes.map((t) => t.schema).filter((s) => typeof s === 'string'))) {
    if (schemaName === 'ProblemDetail') continue
    const members = [...propertiesOf(schemaName)].filter((p) => !baseProblemProperties.has(p))
    if (members.length === 0) {
      fail(`problem schema '${schemaName}' adds nothing to ProblemDetail — either it is redundant or the base problem schema gained a member that belongs only to the extension`)
    }
    problemExtensionMembers[schemaName] = members
  }

  const constrainedSchemas = []
  for (const [schemaName, schema] of Object.entries(schemas)) {
    if (!isObject(schema) || schema.type !== 'object' || !isObject(schema.properties)) continue
    const required = Array.isArray(schema.required) ? schema.required : []
    const fields = []
    for (const [propertyName, property] of Object.entries(schema.properties)) {
      // Skip $ref properties — the referenced type carries its own constraints.
      if (!isObject(property) || '$ref' in property) continue
      const declaredType = property.type
      const explicitlyNullable = Array.isArray(declaredType) && declaredType.includes('null')
      const baseType = typeof declaredType === 'string'
        ? declaredType
        : Array.isArray(declaredType) ? declaredType.find((t) => t !== 'null') : undefined
      if (!baseType) continue

      const constraints = []
      if (baseType === 'string') {
        if (isNumber(property.minLength) || isNumber(property.maxLength)) {
          constraints.push({ kind: 'length', min: numberOrUndefined(property.minLength), max: numberOrUndefined(property.maxLength) })
        }
        if (typeof property.pattern === 'string') {
          constraints.push({ kind: 'pattern', pattern: property.pattern })
        }
      } else if (baseType === 'integer') {
        if (isNumber(property.minimum) || isNumber(property.maximum)) {
          constraints.push({ kind: 'range', min: numberOrUndefined(property.minimum), max: numberOrUndefined(property.maximum) })
        }
      } else if (baseType === 'array') {
        if (isNumber(property.minItems)) {
          constraints.push({ kind: 'minItems', min: property.minItems })
        }
      }
      if (constraints.length > 0) {
        fields.push({ property: propertyName, nullable: !required.includes(propertyName) || explicitlyNullable, constraints })
      }
    }
    if (fields.length > 0) {
      constrainedSchemas.push({ name: schemaName, fields })
    }
  }
  if (constrainedSchemas.length === 0) {
    fail('the bundled spec produced no constrained schemas — either it lost all its constraints or the schema-walking code in generate-derived.mjs no longer matches its structure')
  }

  const operations = []
  for (const [path, item] of Object.entries(root.paths ?? {})) {
    if (!isObject(item)) continue
    for (const method of HTTP_METHODS) {
      const operation = item[method]
      if (!isObject(operation)) continue
      if (typeof operation.operationId !== 'string') {
        fail(`${method.toUpperCase()} ${path} has no operationId`)
      }
      // The error responses are `$ref`s into components.responses (the bundle keeps them shared),
      // so resolve those or the Accept header would lose application/problem+json.
      const accept = []
      for (const response of Object.values(operation.responses ?? {})) {
        for (const mediaType of Object.keys(resolveRef(root, response)?.content ?? {})) {
          if (!accept.includes(mediaType)) accept.push(mediaType)
        }
      }
      const contentType = Object.keys(resolveRef(root, operation.requestBody)?.content ?? {})[0]
      operations.push({ operationId: operation.operationId, method: method.toUpperCase(), path, accept, contentType })
    }
  }
  if (operations.length === 0) {
    fail('the bundled spec has no operations')
  }

  return { version, problemTypeBase, problemTypes, clientIdentity, vendorMediaTypes, problemExtensionMembers, constrainedSchemas, operations }
}

// --- Emitters ---

function generateContractVersion(model) {
  return `${HEADER}
/** The Epistola contract version this client library was built against (the spec's info.version). */
export const CONTRACT_VERSION = ${str(model.version)}
`
}

function generateContractIdentity(model) {
  const id = model.clientIdentity
  return `${HEADER}
/**
 * The client-identity wire contract, from the spec's \`x-client-identity\` extension.
 *
 * This client writes these headers and the Epistola server module parses them; both generate from
 * this one registry, so the two halves cannot drift apart.
 */
export const ContractIdentity = {
  /** Header carrying the caller's node identifier. */
  NODE_ID_HEADER: ${str(id.nodeIdHeader)},
  /** The product token every Epistola client's \`User-Agent\` must lead with. */
  CONTRACT_PRODUCT: ${str(id.contractProduct)},
  /** Separator between \`User-Agent\` product tokens. */
  PRODUCT_SEPARATOR: ${str(id.userAgentProductSeparator)},
  /** Separator between a product name and its version. */
  VERSION_SEPARATOR: ${str(id.userAgentVersionSeparator)},
} as const
`
}

function generateContractMediaTypes(model) {
  return `${HEADER}
/**
 * The versioned vendor media types this API speaks.
 *
 * Generated because they carry the API major version: hand-writing them in the request paths the
 * generator does not cover (result collection) would leave those paths behind at the next bump.
 * Public because a consumer building its own request needs the same values.
 */
export const ContractMediaTypes = {
  /** Request and response bodies. */
  VENDOR_JSON: ${str(model.vendorMediaTypes.json)},
  /** Streamed NDJSON responses, as used by result collection. */
  VENDOR_NDJSON: ${str(model.vendorMediaTypes.ndjson)},
} as const
`
}

function generateContractOperations(model) {
  const entries = model.operations
    .map((op) => `  { operationId: ${str(op.operationId)}, method: ${str(op.method)}, path: ${str(op.path)}, accept: ${str(op.accept.join(', '))}, contentType: ${op.contentType ? str(op.contentType) : 'undefined'} },`)
    .join('\n')
  return `${HEADER}
/** The HTTP methods the contract's operations use. */
export type ContractHttpMethod = 'GET' | 'PUT' | 'POST' | 'DELETE' | 'OPTIONS' | 'HEAD' | 'PATCH'

/** One operation of the contract, as far as the wire is concerned. */
export interface ContractOperation {
  readonly operationId: string
  readonly method: ContractHttpMethod
  /** The path template, relative to the API base path, e.g. \`/tenants/{tenantId}/templates\`. */
  readonly path: string
  /**
   * The response media types the operation declares, in the order the contract declares them,
   * joined for the \`Accept\` header — the success type first, then \`application/problem+json\`
   * where the operation declares error responses. Empty when the operation declares no response
   * body at all.
   */
  readonly accept: string
  /** The request-body media type, or undefined when the operation takes no body. */
  readonly contentType: string | undefined
}

/**
 * Every operation of the contract. The generated API classes set the request \`Content-Type\` but
 * never an \`Accept\`, so the client derives that header from this table: it asks for exactly the
 * media types the operation is declared to return, which is how it comes to ask for the problem
 * document it is built to parse.
 */
export const CONTRACT_OPERATIONS: readonly ContractOperation[] = [
${entries}
]
`
}

function generateProblemSlugs(model) {
  const constants = model.problemTypes
    .map((t) => `  /** ${t.status} — ${t.description} */\n  ${t.constantName}: ${str(t.slug)},`)
    .join('\n')
  const members = Object.entries(model.problemExtensionMembers)
    .sort(([a], [b]) => a.localeCompare(b))
    .flatMap(([schema, names]) => names.map((member) =>
      `  /** The \`${member}\` extension member of \`${schema}\`. */\n  ${member.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase()}: ${str(member)},`))
    .join('\n')
  return `${HEADER}
/** Base URI from the spec's x-problem-types registry; must equal TYPE_BASE in the error module. */
export const GENERATED_PROBLEM_TYPE_BASE = ${str(model.problemTypeBase)}

/**
 * The canonical problem \`type\` slugs the Epistola API emits, from the contract's error-type
 * registry (the spec's \`x-problem-types\` extension / \`docs/error-types.md\`).
 *
 * Convenience constants for \`switch (e.typeSlug)\`. \`typeSlug\` is deliberately a plain
 * \`string | undefined\` (not a union of these) so the API can introduce new problem types without
 * forcing a client release — always keep a \`default\` branch.
 */
export const KnownProblemSlugs = {
${constants}
} as const

/** One of the registered problem slugs. Compare \`typeSlug\` against these, but never narrow it to them. */
export type KnownProblemSlug = (typeof KnownProblemSlugs)[keyof typeof KnownProblemSlugs]

/**
 * The names of the members Epistola problem bodies carry on top of the RFC 9457 base, derived from
 * the problem schemas the registry names.
 *
 * The server writes them and this client reads them back out of the raw body by name, so both
 * generate the names from the contract: a rename would otherwise make the extension silently
 * vanish rather than fail.
 */
export const ProblemExtensionMembers = {
${members}
} as const
`
}

function generateModelValidation(model) {
  const names = model.constrainedSchemas.map((s) => s.name).sort()
  const table = model.constrainedSchemas
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((schema) => {
      const fields = schema.fields
        .map((field) => `    { property: ${str(field.property)}, nullable: ${field.nullable}, constraints: [${field.constraints.map(constraintLiteral).join(', ')}] },`)
        .join('\n')
      return `  ${schema.name}: [\n${fields}\n  ],`
    })
    .join('\n')
  const wrappers = names
    .map((name) => `/** Validates a \`${name}\` against its schema constraints; returns it on success. */
export function validate${name}(value: ${name}): ${name} {
  return validateModel(${str(name)}, value)
}`)
    .join('\n\n')
  return `${HEADER}
import type {
${names.map((n) => `  ${n},`).join('\n')}
} from './api/models/index.js'
import { ModelValidationException, type ConstraintViolation } from '../validation/modelValidationException.js'

/**
 * Explicit fail-fast \`validate\` helpers for the contract's request/response models — the
 * counterpart of the Kotlin / .NET \`validate()\` extensions and the Python \`validate()\` helper.
 *
 * The generated models are plain interfaces with no runtime checks, so these are the only place a
 * constraint the contract declares — a slug pattern, a length bound, a range — is enforced before
 * the request leaves the process. The set of models is derived from the spec, so the build fails if
 * the contract ever silently drops all of its constraints.
 */

/** A constraint the contract declares on one property. */
export type FieldConstraint =
  | { readonly kind: 'length'; readonly min?: number; readonly max?: number }
  | { readonly kind: 'pattern'; readonly pattern: string }
  | { readonly kind: 'range'; readonly min?: number; readonly max?: number }
  | { readonly kind: 'minItems'; readonly min: number }

/** One constrained property of a model. */
export interface ConstrainedField {
  readonly property: string
  /** Whether the property may be absent or null (not required, or explicitly nullable). */
  readonly nullable: boolean
  readonly constraints: readonly FieldConstraint[]
}

/** Names of the contract models that carry validatable constraints. */
export const CONSTRAINED_MODELS = [
${names.map((n) => `  ${str(n)},`).join('\n')}
] as const

export type ConstrainedModelName = (typeof CONSTRAINED_MODELS)[number]

/** The constraints of every constrained model, keyed by model name. */
export const MODEL_CONSTRAINTS: Readonly<Record<ConstrainedModelName, readonly ConstrainedField[]>> = {
${table}
}

/**
 * Validates \`value\` against the constraints the contract declares on \`model\`; returns it on
 * success, throws {@link ModelValidationException} listing every violation otherwise.
 */
export function validateModel<T extends object>(model: ConstrainedModelName, value: T): T {
  const violations: ConstraintViolation[] = []
  const record = value as Record<string, unknown>
  for (const field of MODEL_CONSTRAINTS[model]) {
    const actual = record[field.property]
    if (actual === undefined || actual === null) {
      if (!field.nullable) {
        violations.push({ property: field.property, message: 'is required' })
      }
      continue
    }
    for (const constraint of field.constraints) {
      const message = check(actual, constraint)
      if (message !== undefined) {
        violations.push({ property: field.property, message })
      }
    }
  }
  if (violations.length > 0) {
    throw new ModelValidationException(model, violations)
  }
  return value
}

function check(actual: unknown, constraint: FieldConstraint): string | undefined {
  switch (constraint.kind) {
    case 'length': {
      if (typeof actual !== 'string') return 'must be a string'
      const length = [...actual].length
      if (constraint.min !== undefined && length < constraint.min) return \`length must be at least \${constraint.min}\`
      if (constraint.max !== undefined && length > constraint.max) return \`length must be at most \${constraint.max}\`
      return undefined
    }
    case 'pattern':
      if (typeof actual !== 'string') return 'must be a string'
      return new RegExp(constraint.pattern).test(actual) ? undefined : \`must match pattern \${constraint.pattern}\`
    case 'range':
      if (typeof actual !== 'number') return 'must be a number'
      if (constraint.min !== undefined && actual < constraint.min) return \`must be at least \${constraint.min}\`
      if (constraint.max !== undefined && actual > constraint.max) return \`must be at most \${constraint.max}\`
      return undefined
    case 'minItems':
      if (!Array.isArray(actual)) return 'must be an array'
      return actual.length >= constraint.min ? undefined : \`must have at least \${constraint.min} item(s)\`
  }
}

${wrappers}
`
}

function generateIndex() {
  return `${HEADER}
export * from './contractVersion.js'
export * from './contractIdentity.js'
export * from './contractMediaTypes.js'
export * from './contractOperations.js'
export * from './knownProblemSlugs.js'
export * from './modelValidation.js'
`
}

// --- Helpers ---

function constraintLiteral(constraint) {
  switch (constraint.kind) {
    case 'length':
    case 'range': {
      const parts = [`kind: ${str(constraint.kind)}`]
      if (constraint.min !== undefined) parts.push(`min: ${constraint.min}`)
      if (constraint.max !== undefined) parts.push(`max: ${constraint.max}`)
      return `{ ${parts.join(', ')} }`
    }
    case 'pattern':
      return `{ kind: 'pattern', pattern: ${str(constraint.pattern)} }`
    case 'minItems':
      return `{ kind: 'minItems', min: ${constraint.min} }`
    default:
      fail(`unhandled constraint kind: ${constraint.kind}`)
  }
}

/** Follows a local `$ref` (`#/components/...`) to its target; returns other nodes unchanged. */
function resolveRef(root, node) {
  if (!isObject(node) || typeof node.$ref !== 'string') return node
  if (!node.$ref.startsWith('#/')) fail(`only local $refs are supported in the bundled spec, got ${node.$ref}`)
  let target = root
  for (const segment of node.$ref.slice(2).split('/')) {
    target = target?.[segment.replace(/~1/g, '/').replace(/~0/g, '~')]
  }
  if (target === undefined) fail(`unresolved $ref ${node.$ref}`)
  return resolveRef(root, target)
}

function* mediaTypeKeys(node) {
  if (Array.isArray(node)) {
    for (const item of node) yield* mediaTypeKeys(item)
  } else if (isObject(node)) {
    for (const [key, value] of Object.entries(node)) {
      yield key
      yield* mediaTypeKeys(value)
    }
  }
}

function str(value) {
  // JSON string literals are valid TypeScript string literals (ES2019 made the line separators legal).
  return JSON.stringify(value)
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isNumber(value) {
  return typeof value === 'number' && Number.isFinite(value)
}

function numberOrUndefined(value) {
  return isNumber(value) ? value : undefined
}

function write(outDir, name, content) {
  writeFileSync(join(outDir, name), content, 'utf8')
  console.log(`    wrote ${name}`)
}

function fail(message) {
  console.error(`generate-derived: ${message}`)
  process.exit(1)
}
