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
  fingerprint algorithm (V5 at v8) rather than changing an existing one.
- Add golden fixtures under `fixtures/v1/migrations/` and `fixtures/v1/wire-vN/`.
- Bump the conformance fixtures.
- Bump `x-epistola-catalog-contract.wireSchemaVersion` in `contracts/api/openapi.yaml`, which the
  server stubs' `CatalogContractVersionTest` enforces.

Define rules that consumers also enforce (such as `CatalogKeywords` and `CatalogNamespaces`) once in the catalog module,
and export them to Kotlin and TypeScript with parity tests against the schema. Enforce them in the
migrator and validator, never in model constructors: consumers rebind stored manifests.

A catalog change also reaches the API side. The server stubs build against the catalog source, so
run `make build` and `make conformance` from the repository root as well. `CLAUDE.md` ("The
portable catalog contract") has the detail.
