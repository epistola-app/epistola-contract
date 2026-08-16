# Catalog migration and canonicalization

`epistola-catalog` owns catalog wire-version gating and canonical fingerprints. Consumers should
pass manifest and resource-detail streams through `CatalogSchemaMigrator` before product-specific
import or persistence. The API reports stable migration findings for unsupported or inconsistent
wire versions and never exposes a JSON mapper.

The migration baseline is wire version 4 and the current wire version is 6. Version 4 migrates to
5 and then 6; version 5 migrates directly to 6. Versions below 4 and above 6 are rejected even when
their JSON happens to bind to the current model. The v5-to-v6 migration supplies `nl-NL` as the
default language and an empty keyword set when those fields are absent.

`CatalogCanonicalizer` hashes canonical catalog content. It sorts resources and JSON object keys,
normalizes numeric JSON representation, includes streamed asset digests, dependency identity,
publisher metadata, compatibility declarations, and includes, and excludes volatile release
timestamps, release versions, resource URLs, and ZIP metadata. Therefore equivalent archives have
identical fingerprints even when their entry order, timestamps, compression, or JSON property order
differ.

The current canonical form is V4. `CatalogCanonicalizer.currentFingerprint(catalog)` produces V4.
The existing `fingerprint(catalog)` method continues to produce V1 for source, binary, and
behavioral compatibility, and the versioned overload supports explicit selection. Whole-catalog
validation uses the source wire version: v4/v5 input accepts V1 through V3 and the equivalent
legacy-v4 projection, while native v6 input must carry V4. New fingerprints should always use
`currentFingerprint`. V3 retains the semantic v5 resource projection. V4 adds an algorithm domain
prefix and includes `defaultLanguage`, sorted exact-case `keywords`, and catalog presentation asset
references. Re-exporting a migrated catalog replaces a present legacy fingerprint with V4.

Authoritative versioned inputs and expected hashes are published below
`META-INF/epistola-catalog/fixtures/v1` in the Maven artifact and
`@epistola.app/epistola-catalog/fixtures/v1/*` in the npm artifact.
