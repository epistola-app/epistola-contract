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
├── spec/                              # OpenAPI specification (source of truth)
│   ├── epistola-api.yaml              # Main spec file
│   ├── paths/                         # Path definitions
│   ├── components/
│   │   ├── schemas/                   # Data models
│   │   └── responses/                 # Response definitions
│   └── redocly.yaml                   # Spec validation config
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
cd spec
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

### Kotlin Client (`io.epistola:client-kotlin-spring-restclient`)

A Kotlin client library using:
- **Spring RestClient** (Spring Boot 3.2+)
- **Jackson** for JSON serialization
- Java 8 date/time handling

### Kotlin Server (`io.epistola:epistola-server-kotlin`)

Spring server interfaces for implementing the API:
- Interface-only generation (no implementations)
- Spring Boot 3.x compatible
- Bean validation annotations
- Ready to implement in your Spring application

## Versioning

This repository uses **SemVer** versioning where the version represents the API contract version:

- **Major**: Breaking API changes
- **Minor**: New features, backwards compatible
- **Patch**: Bug fixes, client/server-only fixes

## Using in Your Project

### Kotlin Client (Gradle)

```kotlin
dependencies {
    implementation("io.epistola:client-kotlin-spring-restclient:1.0.0")
}
```

### Kotlin Server (Gradle)

```kotlin
dependencies {
    implementation("io.epistola:epistola-server-kotlin:1.0.0")
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
