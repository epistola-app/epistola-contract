# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **Snapshot workflow restructured** to match release workflow pattern
  - Replaced sequential `build-all` job with parallel matrix-based `build` job (client and server build concurrently)
  - Consolidated `publish-client` and `publish-server` into a single matrix-based `publish` job
  - Build jobs now skip when no relevant files changed (previously always built both modules)
  - `detect-changes` job now skips on `[release]` commits (previously ran unnecessarily)
  - `mock-server` job no longer waits for Gradle builds (only needs bundled spec)

### Fixed
- CLAUDE.md `security-defined` validation rule documented as "Disabled" but was actually set to `error` in `redocly.yaml`
- Version injection `sed` command in release and snapshot workflows replaced all `version:` lines in bundled spec, corrupting schema property definitions (now only replaces the first match: `info.version`)

### Added
- **Trunk-based release flow** — releases are triggered by including `[release]` in a commit message on `main`
- **`make release`** convenience target that creates a `[release]` marker commit with safety checks (must be on `main`, clean working tree)
- Snapshot publishing automatically skips when a `[release]` commit is pushed (prevents duplicate artifacts)
- Release branches (`release/X.Y`) are still supported for hotfixing older versions — any push to a release branch triggers a release

### Removed
- `version-bump.yml` workflow (was for release-branch model)
- `make cut-release` target (replaced by `make release`)

### Added
- **Bulk template import endpoint** `POST /tenants/{tenantId}/templates/import`
  - Create-or-update semantics for idempotent template synchronization
  - Supports full template definition: metadata, dataModel, dataExamples, templateModel, variants
  - Per-variant templateModel override (falls back to top-level templateModel)
  - Automatic publishing to specified environments after import
  - Per-template result status: `created`, `updated`, `unchanged`, `failed`
  - New schemas: `ImportTemplatesRequest`, `ImportTemplateDto`, `ImportVariantDto`, `ImportTemplatesResponse`, `ImportTemplateResultDto`

### Added
- **Template model schema types** for the node/slot graph model (`spec/components/schemas/template-model.yaml`)
  - `TemplateDocumentDto`: root document with modelVersion, root, nodes, slots, themeRef, and optional overrides
  - `NodeDto`: graph node with id, type, slots, styles (open), stylePreset, and props (open)
  - `SlotDto`: graph slot with id, nodeId, name, and children
  - `ThemeRefDto`: theme reference with type enum (`inherit` / `override`) and optional themeId
  - `BlockStylePresetDto`: structured preset with label, styles (open), and optional applicableTo
- `PageSettingsDto.backgroundColor` property for page background color

### Changed
- **BREAKING**: `VersionDto.templateModel` and `UpdateDraftRequest.templateModel` changed from bare `type: object` to `TemplateDocumentDto`
  - Server stubs: `ObjectNode` → `TemplateDocumentDto`
  - Client: `Any?` → `TemplateDocumentDto`
  - Wire format remains compatible — same JSON, now properly described
  - All examples updated from old block-based model to node/slot graph format
- **BREAKING**: `DocumentStylesDto` changed from explicit properties to an open object
  - Matches `template-shared.schema.json#DocumentStyles` where available properties are driven by the style registry
  - Server stubs: typed data class → `Map`/`ObjectNode`
  - Client: typed data class → `Any`
- **BREAKING**: `blockStylePresets` in `ThemeDto`, `CreateThemeRequest`, and `UpdateThemeRequest` changed
  from unstructured `additionalProperties: type: object` to `additionalProperties: $ref: BlockStylePresetDto`
  - Each preset now has `label` (required), `styles` (required), and `applicableTo` (optional)
  - Server/client: `Map<String, Any>` → `Map<String, BlockStylePresetDto>`

### Fixed
- `MarginsDto` description corrected from "pixels" to "millimeters" matching the source of truth
  - Added `required` constraint on all four sides and `minimum: 0` validation

### Added
- Consumer registration design document (`docs/consumer_registration.md`) covering:
  - Consumer registry for tracking which systems consume the Epistola API (platform-level CRUD)
  - Template dependency declaration per tenant for impact analysis
  - 409 Conflict responses on delete when dependent consumers exist
  - Attribution via `DocumentDto.createdBy` population
  - Integration with the event system actor model
  - Phased implementation recommendation (registry, dependencies, events)

### Fixed
- Fix mock server Docker image pull failure ("manifest unknown") by disabling provenance attestations,
  which forced OCI-only manifest format incompatible with older Docker clients
- Prevent half-releases by separating build and publish phases in CI workflows
  - Release and snapshot workflows now build and test all modules first
  - Publishing only starts after all builds succeed
  - Previously, modules built and published independently — if one succeeded and the other failed,
    only one artifact would be published to Maven Central

### Added
- Event system design document (`docs/event_system.md`) exploring five delivery mechanisms:
  Long Polling, SSE, Webhooks, Polling with Event Log, and gRPC Hybrid (notification
  channel + REST event log). Recommends Polling with Event Log for contract-first
  compatibility, with two upgrade paths: Long Polling (primary) and gRPC Hybrid (future).
- Client-side validation extension functions generated from OpenAPI schema constraints
  - `.validate()` extension on all model classes that have constrained properties (25 models)
  - Enforces `pattern`, `minLength`/`maxLength`, `minimum`/`maximum`, and `minItems` constraints
  - Nullable fields use safe `?.let` pattern; null values skip validation
  - Returns `this` for fluent chaining (e.g., `createTenantRequest.validate()`)
  - Generated into `build/generated-validation/` (not committed) alongside the OpenAPI-generated client code
- OpenAPI examples for consistent, deterministic mock server responses
  - Schema-level examples on all response/DTO types (used by Prism for reliable static responses)
  - Property-level examples on all properties (used by documentation renderers like Redoc)
  - Examples follow a coherent "Epistola story" narrative (Acme Corp tenant, invoice template, English variant)
  - All IDs, timestamps, and references are cross-consistent across schemas
  - List responses include realistic multi-item arrays (e.g., production + staging environments)

### Changed
- Switched Prism mock server from dynamic (`-d`) to static mode
  - Responses are now deterministic and consistent across repeated requests
  - Mock data is derived from schema examples instead of randomly generated

### Fixed
- Mock server Docker image now receives the correct auto-incremented version during releases
  - Previously used static spec version from `epistola-api.yaml` (e.g., always `0.1.0`)
  - Now uses the same centralized version calculation as Maven artifacts (e.g., `0.1.2`)
- Centralized release version calculation in `validate-and-bundle` job
  - All artifacts (client, server, mock server) now share the same version per release
  - Eliminates potential version drift between independently calculated artifact versions

### Changed
- Enabled automatic release to Maven Central (no more manual "Publish" click in Sonatype Central Portal)

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
