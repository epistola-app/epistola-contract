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
