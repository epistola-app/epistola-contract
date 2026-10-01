// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Loads Ajv for the reference adapter.
 *
 * Structural types rather than Ajv's own, and a dynamic import rather than a static one. That was
 * worth keeping from the loader this replaced: Ajv is CommonJS, and under NodeNext resolution a
 * static `import Ajv from 'ajv'` types as the module namespace rather than the class, so it is
 * neither constructable nor callable. {@link unwrap} is where that is dealt with, once.
 *
 * The published package no longer references Ajv at all — it validates through the server and lets
 * a consumer plug in a {@link import('../../../src/validation/templateDataValidator.js').TemplateDataValidator}
 * of their own — so this lives in test sources and `ajv` is a devDependency.
 */

/** The subset of an Ajv error object the adapter reads. */
export interface AjvErrorLike {
  readonly instancePath: string
  readonly keyword: string
  readonly message?: string
  readonly params: Record<string, unknown>
}

/** The subset of an Ajv validate function the adapter uses. */
export interface AjvValidateFunctionLike {
  (data: unknown): boolean
  errors?: AjvErrorLike[] | null
}

/** The subset of an Ajv instance the adapter uses. */
export interface AjvInstanceLike {
  compile(schema: object): AjvValidateFunctionLike
}

/** The Ajv options the adapter sets. */
export interface AjvOptionsLike {
  readonly allErrors?: boolean
  readonly strict?: boolean
}

/** One constructor per JSON Schema dialect, and the formats plugin. */
export interface AjvRuntime {
  /** Draft-07, Ajv's default dialect. */
  readonly Ajv: new (options?: AjvOptionsLike) => AjvInstanceLike
  readonly Ajv2019: new (options?: AjvOptionsLike) => AjvInstanceLike
  readonly Ajv2020: new (options?: AjvOptionsLike) => AjvInstanceLike
  addFormats(ajv: AjvInstanceLike): unknown
}

let cached: Promise<AjvRuntime> | undefined

/** The Ajv runtime, loaded once per process. */
export function loadAjv(): Promise<AjvRuntime> {
  cached ??= resolveRuntime()
  return cached
}

async function resolveRuntime(): Promise<AjvRuntime> {
  const [core, v2019, v2020, formats] = await Promise.all([
    import('ajv'),
    import('ajv/dist/2019.js'),
    import('ajv/dist/2020.js'),
    import('ajv-formats'),
  ])
  return {
    Ajv: unwrap(core),
    Ajv2019: unwrap(v2019),
    Ajv2020: unwrap(v2020),
    addFormats: unwrap(formats),
  }
}

/**
 * Ajv ships CommonJS and assigns its export to both `module.exports` and `module.exports.default`.
 * A dynamic import therefore yields a namespace whose `default` is the class, which in turn carries
 * itself as `default`; an ESM build would yield the class as `default` alone. Both unwrap the same.
 */
function unwrap<T>(module: unknown): T {
  const namespace = module as { default?: unknown }
  const outer = namespace.default ?? module
  const inner = (outer as { default?: unknown }).default ?? outer
  return inner as T
}
