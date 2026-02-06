# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **BREAKING**: Renamed server module from `epistola-server-kotlin` to `server-kotlin-springboot4`
  - Artifact ID changed from `server-spring-boot4` to `server-kotlin-springboot4`
  - Removed submodule structure (flattened to single module)
- Upgraded vanniktech/gradle-maven-publish-plugin from 0.30.0 to 0.36.0 for snapshot support
  - Plugin API changed: removed `SonatypeHost` enum (Central Portal is now default)
- **BREAKING**: Migrated Maven publishing from OSSRH (s01.oss.sonatype.org) to Sonatype Central Portal
  - Replaced manual `maven-publish` and `signing` plugins with vanniktech/gradle-maven-publish-plugin 0.36.0
  - Publishing now uses `publishToMavenCentral` command instead of `publish`
  - GPG signing now uses in-memory keys instead of requiring GPG binary
  - **User action required**: Generate new Central Portal token at https://central.sonatype.com/account
- **BREAKING**: Server module now targets Spring Boot 4.x with Jackson 3
  - Updated Spring Boot from 3.5.1 to 4.0.2
  - Updated OpenAPI Generator from 7.13.0 to 7.19.0
  - Added Jackson 3 module (`tools.jackson.module:jackson-module-kotlin`)
  - Added `useJakartaEe` configuration option

### Added
- Manual workflow dispatch for snapshot publishing with option to skip change detection
- Maven Central publishing configuration for both modules
  - Signing plugin with GPG support
  - Complete POM metadata (name, description, license, developers, SCM)
  - Sources and Javadoc JAR generation
  - OSSRH repository configuration
- GitHub Actions release workflow (`release.yml`)
  - Manual dispatch with module selection
  - Automatic patch version calculation from git tags
  - GitHub release creation with Maven coordinates
- Automatic version reading from OpenAPI spec
  - API version (major.minor) read from `epistola-api.yaml`
  - Patch version calculated from existing git tags
  - Local builds use version `X.Y.0` (not for release)

### Changed
- Moved `epistola-api.yaml` to repository root for easier access
- Moved `redocly.yaml` to repository root

### Changed
- **BREAKING**: Transformed repository into contract-first architecture
- Renamed repository concept from `epistola-api-clients` to `epistola-contract`
- OpenAPI specification is now the source of truth in `spec/` directory
- Generated code is no longer committed - built fresh from spec during each build
- Renamed Kotlin client module to `client-kotlin-spring-restclient`
- Kotlin client now uses Spring RestClient (Spring Boot 3.2+) instead of Ktor
- Updated Java toolchain from 25 to 21 for broader compatibility
- GitHub Actions workflow now runs spec validation, client, and server builds in parallel

### Added
- OpenAPI specification in `spec/` directory (copied from epistola-suite)
- `client-kotlin-spring-restclient` module with OpenAPI Generator configuration
  - Spring RestClient for HTTP communication
  - Jackson for JSON serialization
  - Java 8 date/time handling
- `server-kotlin-springboot4` module for Spring server stubs
  - Interface-only generation for clean implementations
  - Spring Boot 4.x compatible (Jackson 3)
  - Bean validation annotations
- Redocly configuration for spec validation (`redocly.yaml`)
- Spec validation job in CI pipeline
- Maven publishing configuration for both modules

### Removed
- Placeholder EpistolaClient class (replaced by generated code)
- Placeholder test class (replaced by generated tests)
