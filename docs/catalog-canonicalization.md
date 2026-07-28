# Catalog migration and canonicalization

`epistola-catalog` owns catalog wire-version gating and canonical fingerprints. Consumers should
pass manifest and resource-detail streams through `CatalogSchemaMigrator` before product-specific
import or persistence. The API reports stable migration findings for unsupported or inconsistent
wire versions and never exposes a JSON mapper.

The current baseline and wire version are both 4. Earlier version numbers have no defined
historical transform in the contract yet, so versions below 4 are rejected even when their JSON
happens to bind to the current model. Supporting an older wire version requires an explicit,
tested migration before lowering the baseline gate.

`CatalogCanonicalizer` hashes canonical catalog content. It sorts resources and JSON object keys,
normalizes numeric JSON representation, includes streamed asset digests, dependency identity,
publisher metadata, compatibility declarations, and includes, and excludes volatile release
timestamps, release versions, resource URLs, and ZIP metadata. Therefore equivalent archives have
identical fingerprints even when their entry order, timestamps, compression, or JSON property order
differ.

The current canonical form is V2. `CatalogCanonicalizer.currentFingerprint(catalog)` produces V2.
The existing `fingerprint(catalog)` method continues to produce V1 for source, binary, and
behavioral compatibility, and the versioned overload supports explicit selection. Whole-catalog
validation accepts both V2 and the legacy V1 hash so existing installed catalogs remain valid;
newly generated fingerprints should always use `currentFingerprint`.

Authoritative versioned inputs and expected hashes are published below
`META-INF/epistola-catalog/fixtures/v1` in the Maven artifact and
`@epistola.app/epistola-catalog/fixtures/v1/*` in the npm artifact.
