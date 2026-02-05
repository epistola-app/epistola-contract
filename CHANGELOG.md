# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Restructured repository as multi-language API client monorepo for Epistola
- Moved Kotlin client to `epistola-client-kotlin/` subdirectory
- Simplified Kotlin client to single `client` module (removed app/lib split)
- Removed Spring Boot dependencies from Kotlin client (pure library)
- Updated GitHub Actions workflow for parallel client builds
- Rewrote README to reflect multi-client architecture

### Added
- Initial Kotlin client structure with Gradle build
- Support for parallel CI builds of multiple clients
- Build summary job to aggregate client build results
