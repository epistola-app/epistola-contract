# Epistola Catalog Contract

The catalog contract defines the portable content that moves between Epistola
Suite, Exchange, and other consumers. It is the source of truth for catalog
wire formats, template models, registries, conformance fixtures, validation,
canonicalization, migrations, and archive handling.

## Authoritative Sources

| Concern | Source |
| --- | --- |
| Template and theme shapes | [`schemas/`](schemas/) |
| Component and style vocabulary | [`registry/`](registry/) |
| Portable compatibility examples | [`fixtures/`](fixtures/) |
| Semantic validation and archive behavior | [`src/main/kotlin/`](src/main/kotlin/) |
| TypeScript public facade | [`ts/`](ts/) and generated sources in [`generated/`](generated/) |

The catalog currently writes wire `schemaVersion: 6`, migrates versions 4 and 5, and uses template
`modelVersion: 1`. Published Maven and npm artifacts are released on the
repository's coordinated release train.

The versioned v5 and v6 manifest and resource-detail schemas preserve each accepted wire shape.
Their unversioned counterparts point at v6, and the npm package root exports TypeScript declarations
generated from the current schemas.

## Build

```bash
./gradlew build sourcesJar dokkaJavadocJar
pnpm install --frozen-lockfile
pnpm generate:types
pnpm build
npm pack --dry-run
```

Run the same checks from the repository root with:

```bash
make build-epistola-catalog
```

## Documentation

- [Archive format](docs/catalog-archive.md)
- [Canonicalization and fingerprints](docs/catalog-canonicalization.md)
- [Compatibility](docs/catalog-compatibility.md)
- [Catalog licensing](docs/catalog-licensing.md)
- [Registry](docs/catalog-registry.md)
- [Portable validation](docs/portable-catalog-validation.md)
