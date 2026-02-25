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
- `security-defined`: Disabled (auth not yet implemented)

## Testing Changes

1. Run `make lint` to validate spec syntax
2. Run `make bundle` to create bundled spec
3. Run `make build` to verify client/server generation compiles
4. Run `make mock` to test endpoints with mock server

## Branching Strategy

This project uses a **main-first** development model:

- **`main`** is the active development branch. All new features and fixes land here first.
- **`release/X.Y`** branches are cut from `main` for milestones and receive only bug fixes (via backport).
- Pushes to `release/**` trigger releases to Maven Central. Pushes to `main` trigger snapshot publishing.

### Cutting a Release

```bash
# Ensure epistola-api.yaml version matches (e.g., 0.2.0 for release/0.2)
make cut-release VERSION=0.2
git push origin release/0.2
```

After pushing, the `version-bump.yml` workflow automatically creates a PR to bump `main` to the next development version (e.g., `0.3.0`).

### Version Flow Example

```
main (0.1.0) → cut release/0.1 → main bumped to 0.2.0
main (0.2.0) → cut release/0.2 → main bumped to 0.3.0
```

### Backporting Fixes

1. Fix the issue on `main` via a normal PR
2. Add a `backport:release/X.Y` label to the PR
3. After merge, the backport workflow creates a cherry-pick PR to the release branch
4. Review and merge the backport PR — this triggers a new release automatically

## Commit Guidelines

- Follow conventional commits: `feat(api):`, `fix(spec):`, `docs:`, etc.
- Update CHANGELOG.md for user-facing changes
- Never push directly - create commits locally for review

## Important Notes

- `openapi.yaml` is gitignored - always regenerate with `make bundle`
- The API version in `epistola-api.yaml` drives artifact versioning
- All IDs use slug format (kebab-case, 3-63 chars)
- Timestamps use ISO 8601 format (`date-time`)
