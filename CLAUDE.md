# Claude Code Instructions for Epistola Contract

This is the **contract-first API repository** for the Epistola document generation platform. The OpenAPI specification is the single source of truth, and all client libraries and server stubs are generated from it.

## Project Overview

Epistola is a document template management and generation system. This repository defines the API contract and generates:
- **Kotlin client** using Spring RestClient (for consuming the API)
- **Java client** using MicroProfile Rest Client, for Jakarta EE application servers
- **.NET client** using HttpClient, and a **Python client** using urllib3
- **Kotlin server stubs** using Spring Boot 4 (for implementing the API)

## Repository Structure

```
epistola-contract/
├── contracts/api/openapi.yaml              # Root OpenAPI spec (source of truth)
├── contracts/api/                # REST API contract and derived projects
│   ├── paths/                     # Endpoint definitions
│   │   ├── templates.yaml
│   │   ├── variants.yaml
│   │   ├── versions.yaml
│   │   ├── generation.yaml
│   │   └── ...
│   └── components/
│       ├── schemas/               # Data models (DTOs, requests, responses)
│       └── responses/             # Shared error responses
│   ├── clients/kotlin-spring-restclient/  # Generated Kotlin client
│   ├── clients/jakarta/                   # Generated Jakarta EE client
│   └── server-stubs/kotlin-springboot4/   # Generated Spring server stubs
├── openapi.yaml                   # Bundled spec (generated, gitignored)
├── Makefile                       # Build commands
└── redocly.yaml                   # Spec validation rules
```

## Key Commands

```bash
make lint          # Validate OpenAPI spec
make bundle        # Bundle spec into openapi.yaml
make build         # Build every client, the server stubs and the catalog
make build-jakarta # Build the Jakarta EE client only
make mock          # Start mock server on localhost:4010
make breaking      # Check for breaking changes vs main
```

## Working with the OpenAPI Spec

### Adding a New Endpoint

1. **Add path definition** to the appropriate file in `contracts/api/paths/`
2. **Add schemas** to `contracts/api/components/schemas/` (create new file if needed)
3. **Reference in contracts/api/openapi.yaml**:
   - Add path reference under `paths:`
   - Add schema references under `components: schemas:`
4. **Validate**: `make lint`
5. **Test generation**: `make build`

### Schema File Pattern

Each schema file contains related types. Example structure:
```yaml
# contracts/api/components/schemas/example.yaml
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
# contracts/api/paths/example.yaml
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

- Kotlin client: `contracts/api/clients/kotlin-spring-restclient/build/generated/`
- Jakarta client: `contracts/api/clients/jakarta/build/generated/`
- Server: `contracts/api/server-stubs/kotlin-springboot4/build/generated/`

Generated code is NOT committed - rebuilt from spec each time.

### Shared build logic

`contracts/api/build-logic/` holds the Gradle scripts the JVM builds share
(`apply(from = "$rootDir/../../build-logic/…")`):

- `contract-version.gradle.kts` — group and version, derived from the spec.
- `contract-spec-model.gradle.kts` — reads the bundled spec into the plain-data model the two
  clients and the server stubs all generate from: problem types, the client-identity registry,
  constrained schemas, and the contract version. Each build emits its own language from that
  model; only the emitted syntax is per-module. Add a constraint keyword or change a registry's
  shape here, not in the individual build files.

### Shared protocol logic

`contracts/api/protocol-java/` holds the wire-protocol behaviour that is neither generated nor
language-specific, used by both JVM clients and the server stubs:

- `PartitionRouting` — the partition math and `routingKeyToMe`
- `PollBackoff` — the adaptive result-collection polling policy
- `UserAgent` — the `User-Agent` grammar, **both** formatting (clients) and parsing (server)
- `ProblemTypeUris` — problem `type` URI ↔ slug, used in opposite directions by client and server
- `Murmur3` — the hash the server assigns partitions with
- `ProtocolJwtSigner` — self-signed JWT minting on plain `java.security`, no JOSE library
- `Compression` — result-collection decompression, chosen by sniffing the stream's magic bytes
  rather than trusting `Content-Encoding`

**It is not published.** Each consumer adds `src/main/java` to its own source set
(`epistolaProtocolSources` in their builds) and compiles the classes into its own jar, so the
published surface stays at four artifacts and no consumer gains a coordinate to resolve. Its own
Gradle build exists to keep the logic under test in isolation; CI builds it, nothing publishes it.

This rests on an assumption worth stating, because it is what makes source inclusion safe: **an
application takes one of these artifacts, not two.** It is either a Spring application calling
Epistola, or a Jakarta EE application calling Epistola, or an implementation of the API — never a
combination, and never two clients.

All three jars therefore carry `app.epistola.protocol.*` at the same FQCN, which would be a split
package if two of them ever met on one classpath. They are released in lockstep so the bytecode
matches and a flat classpath resolves it harmlessly, but JPMS and OSGi reject split packages
outright. If a consumer ever genuinely needs two — implementing the API while calling another
Epistola instance, say — relocate the classes per artifact: a `Copy` with a package-rewriting
filter over five files in each build, no plugin required.

It is **Java, not Kotlin**, so the Jakarta client does not compile in a dependency on
kotlin-stdlib. It has **no dependencies**; `ProtocolContractTest` asserts that, because whatever it
gains is gained by every consumer.

The package is `@NullMarked` (JSpecify, `compileOnly`). Kotlin consumers **must** set
`-Xjspecify-annotations=strict`, or the annotations are silently ignored and they get platform
types back. Both Kotlin builds set it; the flag carries a comment saying why.

### Contract constants are generated, not copied

Anything both sides of the wire must agree on lives in a machine-readable spec extension and is
generated into every module that needs it. No module depends on another to get them — the clients
are standalone artifacts, and the Jakarta client ships no runtime dependencies at all.

| Extension | Generates | Into |
| --- | --- | --- |
| `x-problem-types` | `KnownProblemSlugs`, the problem-type base URI | Kotlin client, Jakarta client, server stubs |
| `x-client-identity` | `ContractIdentity` — the `X-EP-Node-Id` header name and the `User-Agent` product grammar | Kotlin client, Jakarta client, server stubs |
| the registry's problem schemas | `ProblemExtensionMembers` — the members each problem body adds to the RFC 9457 base (`errors`, `validationErrors`) | Kotlin client, Jakarta client, server stubs |
| the operations' content types | `ContractMediaTypes` — the versioned vendor media types, which carry the API major version | Kotlin client, Jakarta client (and the server build's `produces` rewrite) |

The clients write those headers and the server parses them, so a divergence would make every
request from that client unidentifiable with nothing else to catch it. When you change one of
these registries, expect tests in all three modules to fail — they pin the literals on purpose.

### The problem-type registry and code that follows it

The machine-readable problem-type registry is the **`x-problem-types` extension** at the top
of `contracts/api/openapi.yaml`. Automation keeps most consumers aligned with it:

- `KnownProblemSlugs` is **generated** from it in all three JVM modules — both clients and the
  server stubs (`generateProblemSlugs` task in each build). Do not edit them by hand.
- `contracts/api/scripts/check-error-registry.sh` (run by `make lint` and CI) fails when
  `contracts/api/docs/error-types.md` disagrees with `x-problem-types`.
- Guard tests (`ProblemRegistryTest` in each module's `.../error/` test package) fail when the
  hand-written helpers drift from the registry.

When you add, rename, or change a problem `type`, update in the same change:

- `x-problem-types` in `contracts/api/openapi.yaml` AND the table in `contracts/api/docs/error-types.md` (the check
  script holds them together).
- The response components in `contracts/api/components/responses/problem-responses.yaml` /
  `auth-errors.yaml`, and any new problem-detail schema in `errors.yaml` (register it in
  `contracts/api/openapi.yaml` under `components.schemas`; if it should reuse Spring's native
  `ProblemDetail` on the server, add it to `schemaMappings` in the server `build.gradle.kts` —
  and to `.openapi-generator-ignore` if it is allOf-composed).
- **Server** `ProblemDetails.kt`: a builder + `*_PROPERTY` constant for any new extension member
  (following `validation` / `ERRORS_PROPERTY`), and a delegating entry in the compatibility
  `KnownSlugs` object (`const val X = KnownProblemSlugs.X`) — `ProblemRegistryTest` fails until
  that object covers every generated slug. The slug *values* are generated; only that
  compatibility shim is hand-written.
- **Kotlin client**: only if the problem carries a new extension member — extend
  `ProblemDetailErrorHandler.parseProblem` to surface it on `ProblemDetailException`
  (with an `isXxxProblem` flag, following `errors`/`validationErrors`).
- **Jakarta client**: the same, in `ProblemDetailParser.parse` and `ProblemDetailException`
  (`app.epistola.client.jakarta.error`).
- The `when (e.typeSlug)` example / helper example in each module `README.md`.
- The unit tests in each module's `.../error/` test package.

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
1. Reads the major.minor from `contracts/api/openapi.yaml` and auto-increments the patch from existing git tags
2. Updates `info.version` in `contracts/api/openapi.yaml` to the full release version (e.g. `0.3.1`)
3. Commits the spec update and pushes to main
4. Creates the GitHub Release with the version tag

For example, if the spec says `0.3.0` and the latest tag is `v0.3.0`, the next release will update the spec to `0.3.1` and create tag `v0.3.1`.

### Bumping the API Version

To release a new major/minor version, update `info.version` in `contracts/api/openapi.yaml` (e.g., from `0.2.0` to `0.3.0`) and then `make release`. The release process will auto-calculate the patch.

### Hotfixing Older Versions

When a fix is needed on an older release:

1. Create a `release/X.Y` branch from the relevant tag (if it doesn't exist yet)
2. Cherry-pick or apply the fix on the release branch
3. Push — any push to `release/**` triggers a release automatically (version is auto-calculated)
4. The branch spec version must match the branch name (e.g., `release/0.1` requires `info.version: 0.1.x`)

## Commit Guidelines

- Follow conventional commits: `feat(api):`, `fix(spec):`, `docs:`, etc.
- Update CHANGELOG.md for user-facing changes
- **Whenever you open a PR, add a corresponding entry to CHANGELOG.md under the `## [Unreleased]` heading** (create the section if it isn't there). The release process promotes `[Unreleased]` to the new version.
- Never push directly - create commits locally for review

## CI/CD Notes

- Tool setup (Java, Gradle, Node, pnpm) is managed by `.mise.toml` via composite actions in `.github/actions/`
- npm publishing uses **OIDC authentication** (`id-token: write` permission), not an `NPM_TOKEN` secret — do not add `NODE_AUTH_TOKEN` env vars to npm publish steps
- NuGet.org publishing (the .NET client **release**) uses **OIDC trusted publishing** via the `NuGet/login@v1` action (`id-token: write`), not a stored `NUGET_API_KEY`. The `user:` (hardcoded in `release.yml`, public not secret) is the **personal nuget.org profile name** that created the policy (`sdegroot`) — NOT the package owner; the trusted-publisher policy on nuget.org selects the **Epistola** org as the package owner separately. The policy must be configured for the `release.yml` workflow (repo `epistola-app/epistola-contract`, empty environment). NuGet does not require package signing (nuget.org repository-signs on upload); do not add author-signing certs. Snapshots and feature snapshots publish to GitHub Packages with `GITHUB_TOKEN` (NuGet.org has no transient snapshot feed and its prereleases are permanent/public).
- Maven Central publishing uses GPG signing with in-memory keys (secrets: `OSSRH_USERNAME`, `OSSRH_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_KEY_ID`, `GPG_PASSPHRASE`)

## Important Notes

- `openapi.yaml` is gitignored - always regenerate with `make bundle`
- The API version in `contracts/api/openapi.yaml` drives artifact versioning
- All IDs use slug format (kebab-case, 3-63 chars)
- Timestamps use ISO 8601 format (`date-time`)
