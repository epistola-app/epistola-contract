# Catalog migration and canonicalization

`epistola-catalog` owns catalog wire-version gating and canonical fingerprints. Consumers should
pass manifest and resource-detail streams through `CatalogSchemaMigrator` before product-specific
import or persistence. The API reports stable migration findings for unsupported or inconsistent
wire versions and never exposes a JSON mapper.

The current baseline and wire version are both 4. Earlier version numbers have no defined
historical transform in the contract yet. To preserve the existing Suite behavior during this
transition, a lower version that already binds to the current shape is accepted. Adding the first
historical migration must introduce an explicit transform and make the baseline gate strict.

`CatalogCanonicalizer` hashes canonical catalog content. It sorts resources and JSON object keys,
normalizes numeric JSON representation, includes streamed asset digests and dependency identity,
and excludes volatile release timestamps, release versions, resource URLs, and ZIP metadata.
Therefore equivalent archives have identical fingerprints even when their entry order, timestamps,
compression, or JSON property order differ.

Authoritative versioned inputs and expected hashes are published below
`META-INF/epistola-catalog/fixtures/v1`.
