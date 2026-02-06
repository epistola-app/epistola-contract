# Epistola Contract

Contract-first API repository for [Epistola](https://github.com/sdegroot/epistola). This repository owns the OpenAPI specification and generates both client libraries and server stubs.

## Overview

This repository follows the **contract-first** approach:
1. The OpenAPI specification (`epistola-api.yaml`) is the single source of truth
2. Client libraries and server stubs are generated from the spec during build
3. Generated code is NOT committed - it's created fresh each build

## Repository Structure

```
epistola-contract/
├── epistola-api.yaml                  # OpenAPI specification (source of truth)
├── redocly.yaml                       # Spec validation config
├── spec/                              # OpenAPI spec components
│   ├── paths/                         # Path definitions
│   └── components/
│       ├── schemas/                   # Data models
│       └── responses/                 # Response definitions
├── client-kotlin-spring-restclient/   # Generated Kotlin client (Spring RestClient)
│   ├── client/
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── epistola-server-kotlin/            # Generated Spring server stubs
│   ├── server/
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── .github/workflows/
│   ├── build.yml                      # Build all artifacts in parallel
│   └── release.yml                    # Release to Maven Central
├── Makefile                           # Local build commands
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

3. **Restart your shell** to activate mise

## Building

### Validate OpenAPI Spec

```bash
npx @redocly/cli lint epistola-api.yaml
```

### Build Kotlin Client

```bash
cd client-kotlin-spring-restclient
./gradlew build
```

This will:
1. Generate Kotlin client code from the OpenAPI spec
2. Compile the generated code
3. Run tests

### Build Kotlin Server Stubs

```bash
cd epistola-server-kotlin
./gradlew build
```

This will:
1. Generate Spring server interfaces from the OpenAPI spec
2. Compile the generated code
3. Run tests

## Generated Artifacts

### Kotlin Client (`app.epistola.contract:client-spring3-restclient`)

A Kotlin client library using:
- **Spring RestClient** (Spring Boot 3.2+)
- **Jackson** for JSON serialization
- Java 8 date/time handling

### Kotlin Server (`app.epistola.contract:server-spring-boot4`)

Spring server interfaces for implementing the API:
- Interface-only generation (no implementations)
- Spring Boot 3.x compatible
- Bean validation annotations
- Ready to implement in your Spring application

## Versioning

This repository uses a versioning scheme tied to the OpenAPI spec version:

**Format**: `{API_MAJOR}.{API_MINOR}.{PATCH}`

- **API_MAJOR.API_MINOR**: Read automatically from `epistola-api.yaml`
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

## Release Workflow

Artifacts are published to Maven Central via GitHub Actions. Releases can only be triggered from `main` or `release/*` branches.

### Release Process

1. Go to **Actions** > **Release to Maven Central**
2. Select the branch (`main` or `release/*`)
3. Click **Run workflow**
4. Select the module to release (`client-kotlin-spring-restclient`, `epistola-server-kotlin`, or `both`)

The workflow will:
1. **Validate** - Ensure branch version matches spec version (for release branches)
2. **Calculate version** - Read API version from spec, calculate patch from existing tags
3. **Build and test** - Run full build with calculated version
4. **Publish** - Sign and publish to Maven Central
5. **Create release** - Create GitHub release with:
   - Release notes
   - Maven coordinates
   - Bundled `openapi.yaml` (single-file spec with all refs resolved)

### Branch Strategy

| Branch | Spec Version | Publishes | Purpose |
|--------|--------------|-----------|---------|
| `main` | Any (e.g., `2.1.0`) | `2.1.x` | Latest development |
| `release/2.0` | Must be `2.0.x` | `2.0.x` | Maintenance for 2.0 |
| `release/1.0` | Must be `1.0.x` | `1.0.x` | Maintenance for 1.0 |

### Creating a Release Branch

When bumping the API major/minor version:

```bash
# 1. Before bumping, create release branch for current version
git checkout main
git checkout -b release/1.0
git push -u origin release/1.0

# 2. Back on main, bump the spec version
git checkout main
# Edit epistola-api.yaml: version: 1.0.0 -> 2.0.0
git commit -am "feat(spec): bump API version to 2.0.0"
```

### Backporting Fixes

To release a fix across multiple API versions:

```bash
# 1. Fix on main (spec: 2.1.0)
git checkout main
# ... make fix, commit ...
git push

# 2. Backport to 2.0
git checkout release/2.0
git cherry-pick <commit-sha>
git push
# Run release workflow on release/2.0 branch -> publishes 2.0.x

# 3. Backport to 1.0
git checkout release/1.0
git cherry-pick <commit-sha>
git push
# Run release workflow on release/1.0 branch -> publishes 1.0.x
```

### Version Calculation

The patch version is automatically calculated from existing git tags:

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

### Local Testing

Use the Makefile to simulate CI locally:

```bash
make              # Run lint + build (same as CI)
make lint         # Validate OpenAPI spec only
make build        # Build both modules
make bundle       # Create bundled openapi.yaml
make publish-local  # Publish to ~/.m2 for local testing
```

## Using in Your Project

### Kotlin Client (Gradle)

```kotlin
dependencies {
    implementation("app.epistola.contract:client-spring3-restclient:1.0.0")
}
```

### Kotlin Server (Gradle)

```kotlin
dependencies {
    implementation("app.epistola.contract:server-spring-boot4:1.0.0")
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
3. **Kotlin Server**: Generates and builds the Spring server stubs

## License

This project is licensed under the [European Union Public License 1.2](LICENSE) (EUPL-1.2).
