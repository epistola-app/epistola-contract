# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Dual authentication support** for system-to-system communication
  - OAuth 2.0 Client Credentials flow with JWT (recommended)
  - API Key authentication via `X-API-Key` header (fallback)
- **Role-based access control** with five independent roles (can be combined)
  - `reader`: Read-only access to resources within allowed tenants
  - `editor`: Create and update resources within allowed tenants
  - `generator`: Submit document generation jobs
  - `manager`: Delete resources and cancel jobs within allowed tenants
  - `tenant_control`: Manage tenants (list all, create, update, delete)
- **Tenant authorization** via JWT claims (`allowed_tenants`)
- **Security schemes** in OpenAPI spec: `bearerAuth` (JWT) and `apiKeyAuth`
- **401/403 error responses** for authentication/authorization failures
- **`x-required-roles`** extension on all endpoints documenting permission requirements
- Authentication documentation at `docs/auth.md`
- Template data validation endpoint `POST /tenants/{tenantId}/templates/{templateId}/validate`
  - Pre-flight validation of input data against template JSON Schema
  - Returns validation result with detailed error information (path, message, keyword)
  - Enables faster feedback before batch submission without rendering overhead

### Changed
- **BREAKING**: Removed `/v1` prefix from all URL paths
  - API versioning is handled via `Accept` header (`application/vnd.epistola.v1+json`)
  - Paths now start with `/tenants` instead of `/v1/tenants`
- Standardized version handling across all workflows to use `-Pversion=` consistently
  - Release workflow now passes full version (e.g., `1.0.3`) instead of patch version
  - Snapshot workflow centralizes version calculation in spec-validation job
- Mock server is now automatically published as part of release and snapshot workflows
  - Releases publish with spec version tag (e.g., `1.0.0`) and `latest`
  - Snapshots publish with snapshot version tag (e.g., `1.0-SNAPSHOT`) and `latest`
- Removed redundant `build-summary` job from build workflow (use GitHub's native required checks)

### Added
- OpenAPI development tooling
  - `make breaking` - Check for breaking API changes against main branch using oasdiff
  - `make mock` - Start Prism mock server for API testing on http://localhost:4010
  - `make validate-impl` - Validate implementation against OpenAPI spec using Prism proxy
  - CI workflow for automatic breaking change detection on PRs modifying the spec
- Mock server Docker image published to GitHub Container Registry (ghcr.io)
  - Based on Stoplight Prism with bundled OpenAPI spec
  - Automatically released with snapshots and releases, also available via manual workflow dispatch
- Claude skill for OpenAPI spec maintenance (`.claude/skills/openapi.md`)
  - Guidance for file structure navigation
  - Patterns for adding endpoints and schemas
  - REST best practices and versioning guidelines
- GitHub Pages API documentation with Redoc
  - Multi-version support with version selector
  - Landing page showing all available versions
  - Automatic deployment after successful releases to Maven Central
  - Manual deployment via workflow dispatch

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
