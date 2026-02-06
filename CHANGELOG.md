# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Maven Central publishing configuration for both modules
  - Signing plugin with GPG support
  - Complete POM metadata (name, description, license, developers, SCM)
  - Sources and Javadoc JAR generation
  - OSSRH repository configuration
- GitHub Actions release workflow (`release.yml`)
  - Manual dispatch with module selection
  - Automatic patch version increment after release
  - GitHub release creation with Maven coordinates
- Version property files (`gradle.properties`) in both modules
  - `apiVersion`: tracks OpenAPI spec version (1.0)
  - `patchVersion`: auto-incremented by CI (starts at 0)

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
- `epistola-server-kotlin` module for Spring server stubs
  - Interface-only generation for clean implementations
  - Spring Boot 3.x compatible
  - Bean validation annotations
- Redocly configuration for spec validation (`redocly.yaml`)
- Spec validation job in CI pipeline
- Maven publishing configuration for both modules

### Removed
- Placeholder EpistolaClient class (replaced by generated code)
- Placeholder test class (replaced by generated tests)
