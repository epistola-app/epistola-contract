# Codex Instructions

## Workflow

- Commit changes frequently, after completing each meaningful unit of work, using Conventional
  Commits.
- Always maintain `CHANGELOG.md`. Update it with every notable change.
- Never push to a remote without asking for permission first.

## Shared Skills

Reusable agent workflows live under `.agents/skills`.

When the user asks to create a release, cut a release, publish a version, or create a GitHub release,
read and follow:

`.agents/skills/release/SKILL.md`

Agent-specific skill directories, such as `.claude/skills` and `.codex/skills`, should contain only
small adapters that point at the shared skill.

## Checks

- Have you thoroughly tested?
- Have you applied local formatting rules?

## Catalog Contract Parity

When changing the public catalog wire model, keep every contract representation in sync:

- Update the Kotlin protocol model and its tests.
- Update the current versioned JSON Schema under `contracts/catalog/schemas`.
- Regenerate the ignored TypeScript definitions with `pnpm generate:types` from
  `contracts/catalog` to verify the schema produces the intended public types; do not edit
  generated files by hand.
- Update versioned fixtures and parity/conformance tests where the wire behavior changes.
- Run both the JVM catalog tests and the npm wire/build checks before committing.

A released `schemaVersion` is never tightened in place. A change that is not round-trip compatible
needs a new wire version:

- Add new versioned schemas and keep the previous ones.
- Bump `CatalogWireSchema.CURRENT_VERSION` and add an explicit migration that repairs with notices.
  What an archive cannot say for itself (the registry and namespace of a v7 archive) comes from the caller through
  `CatalogMigrationOptions`; without it the migration reports a finding and never invents a value.
- When a migration rewrites or removes content a fingerprint covers (v7 keywords, v8 stencil
  versions), the old fingerprint must still verify against the source content
  (`CatalogCanonicalizer.matchesSourceV4Fingerprint`). New identity or content gets a new
  fingerprint algorithm (V5, then V6, at v8) rather than changing an existing one.
- When a migration renames a member (v8 renamed `slug` to `key`, `themeId`, `stencilId`,
  `assetId`, `iconAssetSlug` and `imageAssetSlugs` to their `…Key`/`…Keys` forms, and an attribute
  assignment's `catalog` to `catalogKey`), list it once in `CatalogKeyRenames`: the
  migration renames forward from it, the wire check refuses the old name at the new version, and
  the legacy fingerprint algorithms (V1 to V4) canonicalize under the old name from it, so no
  published fingerprint moves. Keep `@JsonAlias` with the old name on the renamed Kotlin property,
  so stored documents still bind.
- The template document schemas are shared across wire versions. When one changes in a way an
  older wire version must not see, freeze the older shape (`template-document-v7.schema.json`
  holds the theme reference's `themeId` for wire v5 to v7) rather than tightening it in place.
  Wire v8 references frozen copies of all three (`template-document-v8`, `template-shared-v8`,
  `theme-v8`), which a test holds equal to the live schemas while v8 is current: a change to a
  live schema after v8 ships needs a new wire version with copies of its own.
- A document that declares the current wire version is checked against its published schemas
  before it binds (`CatalogWireSchemaCheck`), so a member the model does not know, a missing
  required one, or a value of the wrong type is a finding rather than dropped or defaulted by
  Jackson. An explicit `null` counts as leaving the member out. A document migrated from an older
  version is not checked against the newer schemas. The writer leaves null properties out, so
  what it writes passes the schemas; a member the schema requires but allows to be null is
  written anyway (`@get:JsonInclude(ALWAYS)`).
- Add golden fixtures under `fixtures/v1/migrations/` and `fixtures/v1/wire-vN/`.
- Bump the conformance fixtures.
- Bump `x-epistola-catalog-contract.wireSchemaVersion` in `contracts/api/openapi.yaml`, which the
  server stubs' `CatalogContractVersionTest` enforces.
- When a migration rewrites values a fingerprint covers (v8 pads short keys and cuts long ones,
  `CatalogKeys.migrate`, with every reference to them), make the rewrite a pure function of the
  value so that independently migrated catalogs agree, refuse a collision rather than choosing
  another value, and have the legacy algorithms restore the source values from the raw documents
  (`CatalogCanonicalizer.sourceKeyed`).
- When a wire version adds a resource member a legacy fingerprint algorithm must not cover (v8's
  data example `key`, template `description`, variant `metadata`), list it in
  `CatalogV8Additions` (or its successor), which V1 to V4 strip, and omit it from the model's JSON
  when absent so V5 and V6 stay put for content that does not use it.

Define rules that consumers also enforce (such as `CatalogKeys`, `CatalogKeywords`, `CatalogNamespaces`,
`CatalogArchivePaths` and `CatalogMetadata`) once in the catalog module,
and export them to Kotlin and TypeScript with parity tests against the schema. Enforce them in the
migrator and validator, never in model constructors: consumers rebind stored manifests.

A catalog change also reaches the API side. The server stubs build against the catalog source, so
run `make build` and `make conformance` from the repository root as well. `CLAUDE.md` ("The
portable catalog contract") has the detail.
