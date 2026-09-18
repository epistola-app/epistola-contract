# Catalog migration and canonicalization

`epistola-catalog` owns catalog wire-version gating and canonical fingerprints. Consumers should
pass manifest and resource-detail streams through `CatalogSchemaMigrator` before product-specific
import or persistence. The API reports stable migration findings for unsupported or inconsistent
wire versions and never exposes a JSON mapper.

The migration baseline is wire version 4 and the current wire version is 7. Each version migrates
one step at a time to 7. Versions below 4 and above 7 are rejected even when their JSON happens to
bind to the current model. The v5-to-v6 migration supplies empty catalog attribute and keyword
collections when those fields are absent; it does not invent a locale.

The v6-to-v7 migration normalizes catalog keywords with `CatalogKeywords.normalize`. It reduces
diacritics and ligatures to ASCII, lowercases, and turns every run of other characters into one
hyphen. It shortens a result longer than 30 characters, merges keywords that collide, and removes a
keyword that keeps no letters or digits. After sorting, it keeps the first 20 keywords and removes
the rest. Each change produces a `CATALOG_KEYWORD_NORMALIZED`, `CATALOG_KEYWORD_TRUNCATED`, or
`CATALOG_KEYWORD_REMOVED` notice at the keyword's source index. Keywords that were already invalid
v6 (blank, untrimmed, or exact duplicates) remain findings. The published
`migrations/v6-to-v7/keyword-normalization.json` fixture defines normalization for other
implementations.

`CatalogCanonicalizer` hashes canonical catalog content. It sorts resources and JSON object keys,
normalizes numeric JSON representation, includes streamed asset digests, dependency identity,
publisher metadata, compatibility declarations, and includes, and excludes volatile release
timestamps, release versions, resource URLs, and ZIP metadata. Therefore equivalent archives have
identical fingerprints even when their entry order, timestamps, compression, or JSON property order
differ.

The current canonical form is V4. `CatalogCanonicalizer.currentFingerprint(catalog)` produces V4.
The existing `fingerprint(catalog)` method continues to produce V1 for source, binary, and
behavioral compatibility, and the versioned overload supports explicit selection. Whole-catalog
validation uses the source wire version. v4/v5 input accepts V1 through V3 and the equivalent
legacy-v4 projection. v6 input must carry V4, computed either over the migrated content or over the
keywords its source `catalog.json` listed, because a v6 producer fingerprinted keywords that the v7
migration may have rewritten. Native v7 input must carry V4 over its own content. New fingerprints
should always use `currentFingerprint`. V3 retains the semantic v5 resource projection. V4 adds an
algorithm domain prefix and includes qualified catalog attributes sorted by catalog and key, sorted
`keywords`, catalog presentation asset references, and catalog-wide license metadata. V4 remains the
current algorithm for wire v7, whose canonical shape is unchanged. Re-exporting a migrated catalog
replaces a present legacy fingerprint with V4.

Authoritative versioned inputs and expected hashes are published below
`META-INF/epistola-catalog/fixtures/v1` in the Maven artifact and
`@epistola.app/epistola-catalog/fixtures/v1/*` in the npm artifact.
