# Claude Code Instructions for Epistola Contract

This is the **contract-first API repository** for the Epistola document generation platform. The OpenAPI specification is the single source of truth, and all client libraries and server stubs are generated from it.

## Project Overview

Epistola is a document template management and generation system. This repository defines the API contract and generates:
- **Kotlin client** using Spring RestClient (for consuming the API)
- **Kotlin server stubs** using Spring Boot 4 (for implementing the API)

## Repository Structure

```
epistola-contract/
├── epistola-api.yaml              # Root OpenAPI spec (source of truth)
├── spec/                          # Modular OpenAPI components
│   ├── paths/                     # Endpoint definitions
│   │   ├── templates.yaml
│   │   ├── variants.yaml
│   │   ├── versions.yaml
│   │   ├── generation.yaml
│   │   └── ...
│   └── components/
│       ├── schemas/               # Data models (DTOs, requests, responses)
│       └── responses/             # Shared error responses
├── client-kotlin-spring-restclient/  # Generated Kotlin client
├── server-kotlin-springboot4/        # Generated Spring server stubs
├── openapi.yaml                   # Bundled spec (generated, gitignored)
├── Makefile                       # Build commands
└── redocly.yaml                   # Spec validation rules
```

## Key Commands

```bash
make lint       # Validate OpenAPI spec
make bundle     # Bundle spec into openapi.yaml
make build      # Build client and server
make mock       # Start mock server on localhost:4010
make breaking   # Check for breaking changes vs main
```

## Working with the OpenAPI Spec

### Adding a New Endpoint

1. **Add path definition** to the appropriate file in `spec/paths/`
2. **Add schemas** to `spec/components/schemas/` (create new file if needed)
3. **Reference in epistola-api.yaml**:
   - Add path reference under `paths:`
   - Add schema references under `components: schemas:`
4. **Validate**: `make lint`
5. **Test generation**: `make build`

### Schema File Pattern

Each schema file contains related types. Example structure:
```yaml
# spec/components/schemas/example.yaml
ExampleDto:
  type: object
  required:
    - id
    - name
  properties:
    id:
      type: string
    name:
      type: string

CreateExampleRequest:
  type: object
  required:
    - name
  properties:
    name:
      type: string
```

### Path File Pattern

```yaml
# spec/paths/example.yaml
example-collection:
  parameters:
    - name: tenantId
      in: path
      required: true
      schema:
        type: string
  get:
    operationId: listExamples
    tags:
      - Examples
    responses:
      '200':
        content:
          application/vnd.epistola.v1+json:
            schema:
              $ref: '../components/schemas/example.yaml#/ExampleListResponse'
```

### Content Type

All endpoints use versioned media type: `application/vnd.epistola.v1+json`

### Common Patterns

- **Tenant scoping**: All resources are scoped under `/v1/tenants/{tenantId}/`
- **Slug IDs**: Use lowercase kebab-case identifiers (pattern: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`)
- **Error responses**: Reference `../components/responses/errors.yaml#/ErrorResponse`
- **Validation errors**: Reference `../components/responses/errors.yaml#/ValidationErrorResponse`

## API Domain Model

The Epistola API manages:

1. **Tenants** - Multi-tenant isolation
2. **Templates** - Document templates with JSON Schema for input validation
3. **Themes** - Reusable style collections
4. **Environments** - Deployment contexts (staging, production)
5. **Variants** - Language/brand variations of templates
6. **Versions** - Version lifecycle (draft → published → archived)
7. **Generation** - Async document generation with batch support

### Template Hierarchy

```
Tenant
└── Template (with JSON Schema)
    └── Variant (language/brand)
        └── Version (draft/published/archived)
            └── Activation (per environment)
```

## Build System

- **Gradle** with Kotlin DSL for client/server modules
- **OpenAPI Generator** for code generation
- **Redocly CLI** for spec validation and bundling
- **Prism** for mock server

### Generated Code Location

- Client: `client-kotlin-spring-restclient/client/build/generated/`
- Server: `server-kotlin-springboot4/build/generated/`

Generated code is NOT committed - rebuilt from spec each time.

## Validation

The spec is validated with Redocly using these rules:
- `operation-operationId`: Required (error)
- `operation-summary`: Recommended (warn)
- `no-unresolved-refs`: Required (error)
- `operation-4xx-response`: Recommended (warn)
- `security-defined`: Required (error)

## Testing Changes

1. Run `make lint` to validate spec syntax
2. Run `make bundle` to create bundled spec
3. Run `make build` to verify client/server generation compiles
4. Run `make mock` to test endpoints with mock server

## Branching Strategy

This project uses a **trunk-based** development model with releases from `main`:

- **`main`** is the only long-lived branch. All development happens here.
- **Snapshots** are published on every push to `main`.
- **Releases** are triggered by creating a GitHub Release (manually or via `make release`).
- **Release branches** (`release/X.Y`) can be created for hotfixing older versions. Any push to a release branch triggers a release automatically.

### Creating a Release

```bash
# Auto-calculates next patch version, updates spec, commits, pushes, and creates GitHub Release
make release

# Or manually with gh CLI
gh release create v0.2.0 --title "v0.2.0" --generate-notes
```

`make release` performs these steps:
1. Reads the major.minor from `epistola-api.yaml` and auto-increments the patch from existing git tags
2. Updates `info.version` in `epistola-api.yaml` to the full release version (e.g. `0.3.1`)
3. Commits the spec update and pushes to main
4. Creates the GitHub Release with the version tag

For example, if the spec says `0.3.0` and the latest tag is `v0.3.0`, the next release will update the spec to `0.3.1` and create tag `v0.3.1`.

### Bumping the API Version

To release a new major/minor version, update `info.version` in `epistola-api.yaml` (e.g., from `0.2.0` to `0.3.0`) and then `make release`. The release process will auto-calculate the patch.

### Hotfixing Older Versions

When a fix is needed on an older release:

1. Create a `release/X.Y` branch from the relevant tag (if it doesn't exist yet)
2. Cherry-pick or apply the fix on the release branch
3. Push — any push to `release/**` triggers a release automatically (version is auto-calculated)
4. The branch spec version must match the branch name (e.g., `release/0.1` requires `info.version: 0.1.x`)

## Commit Guidelines

- Follow conventional commits: `feat(api):`, `fix(spec):`, `docs:`, etc.
- Update CHANGELOG.md for user-facing changes
- Never push directly - create commits locally for review

## CI/CD Notes

- Tool setup (Java, Gradle, Node, pnpm) is managed by `.mise.toml` via composite actions in `.github/actions/`
- npm publishing uses **OIDC authentication** (`id-token: write` permission), not an `NPM_TOKEN` secret — do not add `NODE_AUTH_TOKEN` env vars to npm publish steps
- Maven Central publishing uses GPG signing with in-memory keys (secrets: `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_KEY_ID`, `GPG_PASSPHRASE`)

## Important Notes

- `openapi.yaml` is gitignored - always regenerate with `make bundle`
- The API version in `epistola-api.yaml` drives artifact versioning
- All IDs use slug format (kebab-case, 3-63 chars)
- Timestamps use ISO 8601 format (`date-time`)
