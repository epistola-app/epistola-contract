# Epistola Contract

Contract-first API repository for [Epistola](https://github.com/sdegroot/epistola). This repository owns the OpenAPI specification and generates both client libraries and server stubs.

## Overview

This repository follows the **contract-first** approach:
1. The OpenAPI specification (`spec/`) is the single source of truth
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
│   └── build.yml                      # Build all artifacts in parallel
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

## Publishing to Maven Central

Artifacts are published to Maven Central via GitHub Actions.

### Required Repository Secrets

Configure these secrets in your GitHub repository settings:

| Secret | Description |
|--------|-------------|
| `OSSRH_USERNAME` | Sonatype OSSRH username |
| `OSSRH_PASSWORD` | Sonatype OSSRH password/token |
| `GPG_PRIVATE_KEY` | GPG private key for signing (armor format) |
| `GPG_PASSPHRASE` | GPG key passphrase |
| `GPG_KEY_ID` | GPG key ID (last 8 characters) |

### Manual Release

1. Go to **Actions** > **Release to Maven Central**
2. Click **Run workflow**
3. Select the module to release
4. The workflow will:
   - Build and test
   - Publish to Maven Central
   - Increment patch version
   - Create a GitHub release tag

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
