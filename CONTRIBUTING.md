# Contributing

Thank you for your interest in contributing! This document provides guidelines for contributors.

## Getting Started

1. Install [mise](https://mise.jdx.dev/) version manager
2. Run `./scripts/init.sh` to set up tools
3. Build with `gradle build`

## How to Contribute

### Reporting Bugs

Found a bug? [Open an issue](../../issues/new?template=bug_report.yml) with:
- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Environment details

### Suggesting Features

Have an idea? [Open a feature request](../../issues/new?template=feature_request.yml) describing:
- The problem you're trying to solve
- Your proposed solution
- Any alternatives you've considered

### Submitting Code

1. Fork the repository
2. Create a feature branch (see naming conventions below)
3. Make your changes
4. Ensure tests pass (`gradle test`)
5. Submit a pull request

## Development Workflow

### Branch Naming

Use descriptive branch names with these prefixes:

| Prefix | Purpose |
|--------|---------|
| `feat/` | New features |
| `fix/` | Bug fixes |
| `docs/` | Documentation changes |
| `chore/` | Maintenance tasks |
| `refactor/` | Code refactoring |

**Examples:**
- `feat/add-user-authentication`
- `fix/login-redirect-bug`
- `docs/update-api-reference`

### Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/). Commit messages are validated by a Git hook.

**Format:** `<type>: <description>`

| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `chore` | Maintenance, dependencies |
| `refactor` | Code change that neither fixes nor adds |
| `test` | Adding or updating tests |

**Examples:**
```
feat: add PDF export functionality
fix: resolve login redirect loop
docs: update installation instructions
```

**Breaking Changes:**
- Add `!` after type: `feat!: remove deprecated API`
- Or add `BREAKING CHANGE:` in the commit footer

### Code Style

- We use [ktlint](https://pinterest.github.io/ktlint/) for code formatting
- Run `gradle ktlintCheck` to check
- Run `gradle ktlintFormat` to auto-fix
- EditorConfig is configured for consistent formatting

### API Changes

When modifying the OpenAPI specification:

1. **Check for breaking changes** before submitting your PR:
   ```bash
   make breaking
   ```

2. **Avoid breaking changes** when possible. These require a major version bump:
   - Removing or renaming endpoints
   - Removing or renaming required fields
   - Changing field types
   - Adding new required fields to existing schemas
   - Changing enum values
   - Making validation stricter

3. **Non-breaking changes** are safe:
   - Adding new endpoints
   - Adding optional fields
   - Adding new query parameters
   - Adding new enum values (if clients handle unknown values)
   - Relaxing validation

4. **When breaking changes are necessary**:
   - Document the change clearly in your PR
   - The CI will flag breaking changes in a PR comment
   - Coordinate with API consumers before merging

5. **Test with mock server**:
   ```bash
   make mock
   # In another terminal:
   curl http://localhost:4010/api/v1/tenants
   ```

### Conventions

- **Pagination**: list responses are `{ items: [...], page: PageMeta }` — carry the
  pagination metadata under a `page` property that `$ref`s
  `contracts/api/components/schemas/common.yaml#/PageMeta` (Spring `PagedModel` shape:
  `number`, `size`, `totalElements`, `totalPages`), and reference
  `contracts/api/components/parameters/pagination.yaml#/Page` + `#/Size` for the query params.
  Don't redeclare page fields inline. Bounded sub-resource lists (e.g. variant
  activations) may stay unpaginated.
- **Enum casing**: new resource-state enums use **lowercase kebab-case** (e.g.
  `draft`, `published`, `self-signed-jwt`), matching the slug convention. Some older
  enums use `SCREAMING_SNAKE_CASE` (generation job status `PENDING`/`IN_PROGRESS`/…,
  catalog type `AUTHORED`/`SUBSCRIBED`, code-list `sourceType`/`authType`, health
  `UP`/`DEGRADED`); these are retained as-is because changing enum values is breaking.
  Do not introduce new SCREAMING_SNAKE enums. Enums carrying industry-standard names
  (`A4`/`Letter`) or sample payload data are exempt.
- **Problem types**: see [error-types.md](contracts/api/docs/error-types.md) and the
  `x-problem-types` extension in `contracts/api/openapi.yaml` — the machine-readable registry
  that the client constants are generated from and CI checks against.

### Testing

- All PRs must pass CI checks
- New features should include tests
- Run tests locally: `gradle test`

## Pull Request Process

1. **Create your PR** with a clear description
2. **Link related issues** using keywords (e.g., "Closes #123")
3. **Ensure CI passes** - all checks must be green
4. **Wait for review** - maintainers will review your PR
5. **Address feedback** - make requested changes
6. **Get merged!** - once approved, your PR will be merged

## Questions?

For questions, please use [GitHub Discussions](../../discussions) rather than opening an issue.

## License

By contributing, you agree that your contributions will be licensed under the same license as the project.

---

Thank you for contributing!
