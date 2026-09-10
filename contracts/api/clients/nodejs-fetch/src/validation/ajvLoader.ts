// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Loads Ajv on first use.
 *
 * `ajv` and `ajv-formats` are optional peer dependencies: only a consumer that validates template
 * data on the client needs them, and everyone else should not carry a JSON Schema compiler for a
 * feature they never call. So nothing in this package imports them statically — the validator asks
 * this loader, which imports them dynamically the first time and turns "module not found" into an
 * error that says what to install.
 *
 * The types here are structural on purpose. Naming Ajv's own types in a published declaration file
 * would make every consumer's type-check resolve `ajv`, including the consumers who deliberately
 * did not install it.
 */

/** The subset of an Ajv error object the validator reads. */
export interface AjvErrorLike {
  readonly instancePath: string
  readonly keyword: string
  readonly message?: string
  readonly params: Record<string, unknown>
}

/** The subset of an Ajv validate function the validator uses. */
export interface AjvValidateFunctionLike {
  (data: unknown): boolean
  errors?: AjvErrorLike[] | null
}

/** The subset of an Ajv instance the validator uses. */
export interface AjvInstanceLike {
  compile(schema: object): AjvValidateFunctionLike
}

/** The Ajv options the validator sets. */
export interface AjvOptionsLike {
  readonly allErrors?: boolean
  readonly strict?: boolean
}

/** What the loader hands back: one constructor per JSON Schema dialect, and the formats plugin. */
export interface AjvRuntime {
  /** Draft-07, Ajv's default dialect. */
  readonly Ajv: new (options?: AjvOptionsLike) => AjvInstanceLike
  readonly Ajv2019: new (options?: AjvOptionsLike) => AjvInstanceLike
  readonly Ajv2020: new (options?: AjvOptionsLike) => AjvInstanceLike
  addFormats(ajv: AjvInstanceLike): unknown
}

/** Imports a module by specifier; overridable so the failure path can be tested. */
export type ModuleImporter = (specifier: string) => Promise<unknown>

/** The message a consumer sees when the peers are missing. */
export const AJV_INSTALL_HINT =
  'Client-side template validation needs the optional peer dependencies ajv and ajv-formats. Install them with `npm install ajv ajv-formats` (or the pnpm/yarn equivalent).'

// Literal specifiers, so bundlers can see what is being imported.
const defaultImporter: ModuleImporter = async (specifier) => {
  switch (specifier) {
    case 'ajv':
      return import('ajv')
    case 'ajv/dist/2019.js':
      return import('ajv/dist/2019.js')
    case 'ajv/dist/2020.js':
      return import('ajv/dist/2020.js')
    case 'ajv-formats':
      return import('ajv-formats')
    default:
      throw new Error(`unexpected module ${specifier}`)
  }
}

let cached: Promise<AjvRuntime> | undefined

/**
 * The Ajv runtime, loaded once per process. Rejects with a message naming the packages to install
 * when `ajv` or `ajv-formats` cannot be resolved; the original resolution error is the `cause`.
 */
export function loadAjv(importer: ModuleImporter = defaultImporter): Promise<AjvRuntime> {
  if (importer !== defaultImporter) {
    return resolveRuntime(importer)
  }
  cached ??= resolveRuntime(importer)
  return cached
}

async function resolveRuntime(importer: ModuleImporter): Promise<AjvRuntime> {
  let modules: unknown[]
  try {
    modules = await Promise.all([importer('ajv'), importer('ajv/dist/2019.js'), importer('ajv/dist/2020.js'), importer('ajv-formats')])
  } catch (cause) {
    throw new Error(AJV_INSTALL_HINT, { cause })
  }
  const [core, v2019, v2020, formats] = modules
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
