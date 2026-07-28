# Epistola REST API Contract

This directory owns the REST API specification and everything derived from it.

## Authoritative sources

- `openapi.yaml` is the OpenAPI entry point.
- `paths/` contains endpoint definitions.
- `components/` contains API-specific schemas, parameters, and responses.
- `../catalog/schemas/` contains stable portable catalog schemas that the API may reference.

The catalog is a separate compatibility boundary. An API-breaking change is currently acceptable,
but it must not change catalog validation or its published Kotlin, TypeScript, npm, or Maven
interfaces.

## Derived projects

- `clients/` contains generated client libraries and their handwritten extensions.
- `server-stubs/` contains generated server contracts.
- `mock-server/` packages the bundled API for Prism.
- `build/openapi.yaml` is the generated, self-contained API bundle and is not committed.

From the repository root:

```bash
make lint
make bundle
make build
```

Pinned API tools live in `tools/`; API-specific scripts live in `scripts/`.
