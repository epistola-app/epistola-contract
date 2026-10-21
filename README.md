# Epistola Contract

Contract repository for [Epistola](https://github.com/epistola-app/epistola). It owns two
related but independently consumable specifications:

- `contracts/api`: the REST API contract, plus generated clients, server stubs, and tooling.
- `contracts/catalog`: the stable portable catalog contract, its schemas, and language bindings.

The API may refer directly to catalog schemas. The catalog remains the compatibility boundary:
API evolution must not silently change catalog validation or its published interfaces.

## API Documentation

View the interactive API documentation at: https://epistola-app.github.io/epistola-contract/

### How It Works

The documentation uses [Redoc](https://redocly.com/redoc) to render the OpenAPI specification as interactive API docs. Each API version gets its own documentation page that persists indefinitely.

**Documentation structure:**
```
https://epistola-app.github.io/epistola-contract/
├── index.html          # Landing page with version cards
├── latest/             # Always points to newest version
├── v1.0/               # Version 1.0 docs
│   ├── index.html      # Redoc documentation
│   └── openapi.yaml    # Bundled OpenAPI spec
└── v1.1/               # Version 1.1 docs (after API version bump)
```

### Automatic Deployment

Documentation is automatically deployed after every successful release. The workflow:
1. Triggers when the "Release to Maven Central" workflow completes successfully
2. Bundles the OpenAPI spec into a single file
3. Deploys to the `gh-pages` branch with the release version (e.g., `v1.0.1/`)
4. Updates the `latest/` copy and version manifest; a pre-release (e.g., `v2.0.0-alpha.1/`) is
   listed with status `prerelease` and never becomes `latest`

### Manual Deployment

To deploy documentation without a release:
1. Go to **Actions** > **Deploy API Documentation**
2. Click **Run workflow**
3. Enter the API version (e.g., `1.0`)

### Version Selector

Each documentation page includes a version selector dropdown, allowing users to switch between different API versions.

## Overview

This repository follows a contract-first approach:

1. Authored specifications live below `contracts/`.
2. API clients and server stubs are generated during the build.
3. Generated code and the bundled OpenAPI document are not committed.

## Repository Structure

```
epistola-contract/
├── contracts/
│   ├── api/
│   │   ├── openapi.yaml               # Authored REST API entry point
│   │   ├── paths/                     # Endpoint definitions
│   │   ├── components/                # API schemas and responses
│   │   ├── clients/                   # Kotlin, Jakarta EE, .NET, Python, and Node.js clients
│   │   ├── server-stubs/              # Generated server contracts
│   │   ├── docs/                      # API design documentation
│   │   └── tools/                     # Pinned API build tools
│   └── catalog/
│       ├── schemas/                   # Stable catalog JSON Schemas
│       ├── src/                       # Kotlin catalog implementation
│       ├── src-ts/                    # TypeScript catalog implementation
│       └── docs/                      # Catalog contract documentation
├── .github/workflows/
├── gradle/                            # Shared dependency versions
├── Makefile                           # Repository-wide commands
├── CHANGELOG.md
└── README.md
```

## Quick Start

### Prerequisites

Install [mise](https://mise.jdx.dev/) version manager:

```bash
# macOS
brew install mise

# Other platforms: https://mise.jdx.dev/getting-started.html
```

### Setup

1. **Clone the repository**

2. **Initialize the development environment:**
   ```bash
   ./scripts/init.sh
   ```
   This installs development tools via mise, Git hooks, and [oasdiff](https://github.com/Tufin/oasdiff) for breaking change detection.

3. **Restart your shell** to activate mise

## Building

### Validate OpenAPI Spec

```bash
make lint
```

### Build Kotlin Client

```bash
cd contracts/api/clients/kotlin-spring-restclient
./gradlew build
```

This will:
1. Generate Kotlin client code from the OpenAPI spec
2. Compile the generated code
3. Run tests

### Bundle OpenAPI Spec

Before building, you must bundle the modular OpenAPI spec into a single file:

```bash
make bundle
```

The generated bundle is written to `contracts/api/build/openapi.yaml`.

### Build Jakarta EE Client

```bash
cd contracts/api/clients/jakarta
./gradlew build
```

This generates MicroProfile Rest Client interfaces and JSON-B models from the bundled
spec, compiles them together with the hand-written conventions, and runs the tests.

### Build Kotlin Server Stubs

```bash
cd contracts/api/server-stubs/kotlin-springboot4
./gradlew build
```

This will:
1. Generate Spring server interfaces from the bundled OpenAPI spec
2. Compile the generated code
3. Run tests

**Note:** The build will fail if the bundled specification does not exist. Run `make bundle` first.

## Generated Artifacts

**Take one per application.** An application either calls Epistola — with the Kotlin, Jakarta EE,
.NET, Python or Node.js client matching its runtime — or implements the API with the server stubs.
It is not expected to hold two.

The three JVM artifacts rely on that: each compiles in its own copy of the shared wire-protocol
logic (partition routing, poll backoff, the `User-Agent` grammar, problem type URIs) under the same
package, which keeps it out of every published POM. Two of them on one classpath would therefore be
a split package — harmless on a flat classpath, since they are released in lockstep and the
bytecode matches, but rejected under JPMS and OSGi.

### Kotlin Client (`app.epistola.contract:client-spring3-restclient`)

A Kotlin client library using:
- **Spring RestClient** (Spring Boot 3.2+)
- **Jackson** for JSON serialization
- Java 8 date/time handling

### Jakarta EE Client (`app.epistola.contract:client-jakarta`)

A Java client library for Jakarta EE application servers (WildFly, Open Liberty, Payara,
Quarkus) using:
- **MicroProfile Rest Client** interfaces — `@Inject @RestClient` with configuration alone
- **JSON-B** for binding, supplied by the server
- Identity headers, API-key auth (and experimental self-signed JWT auth), RFC 9457
  problem-detail handling, NDJSON result collection, and client-side schema validation

It ships **no runtime dependencies**: every container-supplied API is `compileOnly`, so
nothing is added to a consumer's WAR and no REST implementation is bundled. See the
[Jakarta client README](contracts/api/clients/jakarta/README.md).

### Kotlin Server Stubs (`app.epistola.contract:server-kotlin-springboot4`)

Spring Boot 4 interfaces to implement, with the portable catalog's Kotlin types in their
signatures, plus helpers for RFC 9457 problem responses and for reading the client identity
headers.

### .NET Client (`Epistola.Contract.Client`)

A .NET 8 client library using:
- **HttpClient** with a composable handler chain
- Newtonsoft.Json serialization
- Identity headers, API-key auth (and experimental self-signed JWT auth), RFC 9457
  problem-detail handling, NDJSON result collection, and client-side schema validation

### Python Client (`epistola-client`)

A Python 3.9+ client library using:
- **urllib3** transport with **pydantic v2** models
- Identity headers, API-key auth (and experimental self-signed JWT auth), RFC 9457
  problem-detail handling, NDJSON result collection, and client-side JSON-Schema validation

### Node.js Client (`@epistola.app/epistola-client`)

A Node.js 22.12+ client library, shipped as an ES module with TypeScript declarations, using:
- The platform's own **`fetch`** (openapi-generator's `typescript-fetch`) and no runtime
  dependencies; Ajv is an optional peer, loaded on first use, for client-side schema validation
- Identity headers, API-key auth (and experimental self-signed JWT auth on `node:crypto`), RFC 9457
  problem-detail handling, NDJSON result collection with gzip/zstd, and client-side JSON-Schema validation
- The `Accept` header each operation is declared with, derived from a generated table of the
  contract's operations — the generator sets none, and Node's `fetch` would send `*/*`

### Portable Catalog (`app.epistola.contract:epistola-catalog` / `@epistola.app/epistola-catalog`)

The catalog is the complete portable aggregate and the boundary for release
versioning, canonical fingerprints, reference resolution, migration, and
validation. It contains templates, stencils, themes, variants, attributes,
code lists, fonts, assets, and their shared component/style vocabulary without
depending on Suite or Exchange implementation code.

The artifact currently provides:

- Kotlin template/theme and catalog protocol types
- JSON schemas and generated TypeScript types
- Component and style registries under `contracts/catalog/registry`
- Canonical model, theme, component, and style types exported from
  `@epistola.app/epistola-catalog`
- Typed TypeScript registry facade exported as
  `@epistola.app/epistola-catalog/registry`
- JVM resources under `META-INF/epistola-catalog`

This is a clean pre-1.0 coordinate rename. Consumers must replace
`app.epistola.contract:epistola-model` and
`@epistola.app/epistola-model`; no duplicate-class compatibility artifact is
published.

See [contracts/catalog/docs/catalog-registry.md](contracts/catalog/docs/catalog-registry.md) for registry details.
See [contracts/catalog/docs/catalog-compatibility.md](contracts/catalog/docs/catalog-compatibility.md) for the
explicit breaking changes and required producer/consumer migrations.

### Kotlin Server (`app.epistola.contract:server-kotlin-springboot4`)

Spring server interfaces for implementing the API:
- Interface-only generation (no implementations)
- Spring Boot 4.x compatible (Jackson 3)
- Bean validation annotations
- Ready to implement in your Spring application

## Versioning

This repository uses a versioning scheme tied to the OpenAPI spec version:

**Format**: `{API_MAJOR}.{API_MINOR}.{PATCH}`

- **API_MAJOR.API_MINOR**: Read automatically from `contracts/api/openapi.yaml`
- **PATCH**: Calculated from git tags (highest existing + 1)

### How it works
- The API version is the single source of truth in the OpenAPI spec
- Gradle reads the version at build time
- CI calculates the patch version from existing release tags
- Local builds default to patch `0` (not for release)

### Version Examples
- `1.0.0` - First release of API version 1.0
- `1.0.1` - Second release (bug fix, dependency update)
- `1.1.0` - First release after API minor version bump (spec changed to 1.1.x)

## Development Workflow

### Day-to-day Development

1. **Create a feature branch** from `main`:
   ```bash
   git checkout main
   git pull
   git checkout -b feat/add-new-endpoint
   ```

2. **Make changes** (spec, build config, etc.)

3. **Create a Pull Request** to `main`
   - CI runs: spec validation → build client → build server
   - Add backport labels if the fix should go to release branches (e.g., `backport:release/1.0`)

4. **Merge to main**
   - Backport PRs are automatically created for labeled branches
   - No release happens yet (main requires manual release)

5. **Release from main** (when ready)
   - `make release` publishes a GitHub release for the next version
   - The release workflow publishes all artifacts and verifies the catalog artifacts

### Workflow Diagram

```
feature/xyz ──► PR ──► main (build only)
                         │
                         ├──► published GitHub release ──► publish all artifacts
                         │
                         └──► backport:release/1.0 label
                                      │
                                      ▼
                              backport PR created
                                      │
                                      ▼
                              merge to release/1.0 (build only)
                                      │
                                      ▼
                              make release (when ready) ──► publish all artifacts
```

### When to Release

Nothing is released on a push. A release is always started by hand with `make release`, when the
branch is ready:

- **From main**: when you have new features or changes ready for users
- **From release branches**: when the backported fixes are ready to ship
- **A pre-release** (`make release PRERELEASE=alpha.1`): to let consumers try a version before it is
  released, also from a `feature/*` branch

### Branch Rules

| Branch | Who commits | How | Releases |
|--------|-------------|-----|----------|
| `main` | Everyone via PR | Feature branches + PRs | Manual |
| `release/*` | Maintainers only | Backport PRs or cherry-picks | Manual |
| `feature/*` | Anyone | Direct commits | Pre-releases only, manual |

## Release Workflow

GitHub Actions publishes the Maven, npm, NuGet, PyPI, and container artifacts.
A release comes from `main` or a `release/*` branch; a pre-release may also come from a `feature/*`
branch. A push never releases anything.

### Release Process

1. Merge the release changes into the branch you release from (`main` or `release/X.Y`), and push.
2. Prepare the changelog.
3. Run `make release` on that branch. It takes the next patch of the spec's `major.minor` from the
   existing tags, commits and pushes that `info.version` when it differs, and publishes the GitHub
   release `vX.Y.Z` for that commit.
4. The published GitHub release triggers `.github/workflows/release.yml`.

**Pre-releases:** `make release PRERELEASE=alpha.1` publishes `v<info.version>-alpha.1` (for example
`v2.0.0-alpha.1`) for the current commit, marked as a pre-release, without changing the spec. It
works from `main`, `release/*` and `feature/*`, and is refused once `v<info.version>` itself is
released. A pre-release is published everywhere a release is, but never as the latest version:
npm publishes it under the `next` dist-tag instead of `latest`, the mock-server image gets no
`latest` tag, and the docs do not move `latest/`. NuGet and PyPI (`2.0.0a1`) treat it as a
pre-release by its version, and Maven orders it before `2.0.0`. Versions published to Maven
Central, NuGet and PyPI cannot be withdrawn.

The workflow will:
1. **Validate** - The tag's `major.minor` matches the spec, the commit is on an allowed branch, and
   the GitHub release is marked pre-release exactly when the tag has a suffix
2. **Version** - Every artifact gets the tag's version
3. **Build and test** - Run the full build with that version
4. **Preflight artifacts** - Generate Kotlin API documentation and build, pack,
   and inspect the exact npm tarball before publishing anything
5. **Publish** - Sign and publish to Maven Central, npm, NuGet, and PyPI;
   publication errors fail the workflow
6. **Verify** - Resolve the catalog from Maven Central and npm using clean
   consumers and inspect the published registries and API documentation
7. **Finalize** - Attach the bundled `openapi.yaml` (single-file spec with all refs resolved) and
   the .NET SBOM to the GitHub release

### Branch Strategy

| Branch | Spec Version | Releases | Purpose |
|--------|--------------|----------|---------|
| `main` | Any (e.g., `2.1.0`) | `make release` | Latest development |
| `release/2.0` | Must be `2.0.x` | `make release` | Maintenance for 2.0 |
| `release/1.0` | Must be `1.0.x` | `make release` | Maintenance for 1.0 |
| `feature/*` | Any | `make release PRERELEASE=…` | Pre-releases of upcoming work |

### Creating a Release Branch

When bumping the API major/minor version:

```bash
# 1. Before bumping, create release branch for current version
git checkout main
git checkout -b release/1.0
git push -u origin release/1.0

# 2. Back on main, bump the spec version
git checkout main
# Edit contracts/api/openapi.yaml: version: 1.0.0 -> 2.0.0
git commit -am "feat(spec): bump API version to 2.0.0"
```

### Backporting Fixes

There are two ways to backport fixes to release branches:

#### Option 1: Automated Backport (Recommended)

1. Create a PR to `main` with your fix
2. Add labels `backport:release/2.0` and/or `backport:release/1.0`
3. Merge the PR
4. The backport bot automatically creates PRs to the release branches
5. Review and merge the backport PRs, then `make release` on each release branch when ready

#### Option 2: Manual Cherry-pick

```bash
# 1. Fix on main (spec: 2.1.0)
git checkout main
# ... make fix, commit ...
git push

# 2. Backport to 2.0
git checkout release/2.0
git cherry-pick <commit-sha>
git push
make release  # → releases 2.0.x

# 3. Backport to 1.0
git checkout release/1.0
git cherry-pick <commit-sha>
git push
make release  # → releases 1.0.x
```

### Version Calculation

`make release` calculates the patch version from existing git tags (pre-release tags such as
`v2.0.0-alpha.1` do not count):

```
Tags: client-spring3-restclient-v1.0.0, client-spring3-restclient-v1.0.1
Next release: client-spring3-restclient-v1.0.2
```

When the API version changes (e.g., `1.0` → `2.0`), patch resets to `0`:

```
Tags: client-spring3-restclient-v1.0.5
Spec: 2.0.0
Next release: client-spring3-restclient-v2.0.0
```

### Required Repository Secrets

Configure these secrets in your GitHub repository settings:

| Secret | Description |
|--------|-------------|
| `OSSRH_USERNAME` | Sonatype OSSRH username |
| `OSSRH_PASSWORD` | Sonatype OSSRH password/token |
| `GPG_PRIVATE_KEY` | GPG private key for signing (armor format) |
| `GPG_PASSPHRASE` | GPG key passphrase |
| `GPG_KEY_ID` | GPG key ID (last 8 characters) |

### npm Trusted Publishing

`@epistola.app/epistola-catalog` and `@epistola.app/epistola-client` publish through npm trusted
publishing rather than a long-lived token. Configure each npm package's settings with:

- Provider: GitHub Actions
- Organization: `epistola-app`
- Repository: `epistola-contract`
- Workflow: `release.yml`
- Allowed action: `npm publish`

Trusted-publisher configuration is package-specific. The configuration for
the former `@epistola.app/epistola-model` package does not authorize the
renamed package, and the catalog's does not authorize the client. Because npm
exposes trusted-publisher settings only after the package exists, bootstrap a
new name once with a narrowly scoped token and a prerelease such as
`0.14.1-rc.1` under the `next` tag. Configure and verify trusted publishing
immediately afterwards, then revoke the bootstrap token. Normal releases
require no npm token. `@epistola.app/epistola-client` still needs this
bootstrap before its first release.

### Python Trusted Publishing

The Python client publishes to PyPI/TestPyPI via OIDC trusted publishing. While the
Epistola PyPI organization is pending approval, configure the `epistola-client` project
under Sander de Groot's personal PyPI/TestPyPI account. The trusted-publisher records
still use GitHub owner `epistola-app`, repository `epistola-contract`, and the workflow
files `release.yml` (PyPI) and `snapshot.yml` (TestPyPI).

### Local Testing

Use the Makefile to simulate CI locally:

```bash
make              # Run lint + build (same as CI)
make lint         # Validate OpenAPI spec only
make build        # Build both modules
make bundle       # Create bundled openapi.yaml
make publish-local  # Publish to ~/.m2 for local testing
```

### API Development Tools

Additional commands for API development and testing:

```bash
# Check for breaking changes against main branch
make breaking

# Start mock server on http://localhost:4010
make mock
# Test with: curl http://localhost:4010/api/tenants

# Validate your implementation against the spec
TARGET_URL=http://localhost:8080 make validate-impl
```

The mock server uses [Prism](https://stoplight.io/open-source/prism) to simulate API responses based on the OpenAPI spec, including example data and validation.

### Mock Server Docker Image

A Docker image of the mock server is published to GitHub Container Registry:

```bash
docker run -p 4010:4010 ghcr.io/epistola-app/epistola-contract/mock-server:latest

# Or use a specific version
docker run -p 4010:4010 ghcr.io/epistola-app/epistola-contract/mock-server:1.0.0
```

Test with: `curl http://localhost:4010/api/tenants`

## Using in Your Project

### Kotlin Client (Gradle)

```kotlin
dependencies {
    implementation("app.epistola.contract:client-spring3-restclient:2.0.0")
}
```

#### Client-side validation

Template data can be checked before a generation request is sent. The client ships no JSON Schema
library: validation goes through a pluggable `TemplateDataValidator`, and the shipped
implementation asks the server's `validateTemplateData`, so the verdict is the server's. Every
client reports a failure the same way: a JSON Pointer `path` into the data and a JSON Schema
`keyword`. See [the Kotlin client's README](contracts/api/clients/kotlin-spring-restclient/README.md#client-side-validation)
and the other clients' READMEs.

### .NET Client (NuGet)

```bash
dotnet add package Epistola.Contract.Client
```

See the [.NET client README](contracts/api/clients/dotnet-httpclient/README.md) for identity,
authentication, problem-detail error handling, result collection, and schema validation.

### Python Client (pip)

```bash
pip install epistola-client
```

```python
from epistola_client import EpistolaClientBuilder, ClientIdentity, TemplatesApi

http = (
    EpistolaClientBuilder()
    .base_url("https://api.epistola.app")
    .identity(ClientIdentity.builder().node_id("my-pod").build())
    .install_problem_detail_handler()
    .build()
)
templates = TemplatesApi(http)
```

See the [Python client README](contracts/api/clients/python-urllib3/README.md) for authentication,
problem-detail error handling (`type_slug` / `KnownProblemSlugs`), NDJSON result collection,
and client-side schema validation.

### Node.js Client (npm)

```bash
npm install @epistola.app/epistola-client
```

```ts
import { ClientIdentity, EpistolaClient, TemplatesApi } from '@epistola.app/epistola-client'

const client = EpistolaClient.builder('https://api.epistola.app/api', 'epk_...')
  .identity(ClientIdentity.builder().nodeId('my-pod').build())
  .build()
const templates = new TemplatesApi(client)
```

See the [Node.js client README](contracts/api/clients/nodejs-fetch/README.md) for authentication,
problem-detail error handling (`typeSlug` / `KnownProblemSlugs`), NDJSON result collection,
and client-side schema validation.

### Kotlin Server (Gradle)

```kotlin
dependencies {
    implementation("app.epistola.contract:server-kotlin-springboot4:1.0.0")
}
```

Then implement the generated interfaces in your Spring application.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Commit Conventions

This project uses [Conventional Commits](https://www.conventionalcommits.org/). Commits are validated by a Git hook.

```bash
feat(spec): add new endpoint
fix(client): resolve serialization issue
docs: update documentation
chore: maintenance task
```

## CI/CD

All artifacts are built and tested in parallel via GitHub Actions:

1. **Spec Validation**: Validates OpenAPI spec with Redocly CLI
2. **Kotlin Client**: Generates and builds the Spring RestClient-based client
3. **Jakarta EE Client**: Generates and builds the MicroProfile Rest Client-based client
4. **Kotlin Server**: Generates and builds the Spring server stubs
5. **.NET Client**: Generates, builds, and tests the HttpClient-based client
6. **Python Client**: Generates, builds, and tests the urllib3-based client
7. **Node.js Client**: Generates, builds, and tests the fetch-based client

## License

This project is licensed under the [European Union Public License 1.2](LICENSE) (EUPL-1.2).
