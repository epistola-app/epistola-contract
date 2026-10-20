# Consumer Registration Design

> **Status**: Draft
> **Date**: 2026-02-17
> **Updated**: 2026-10-19, for contract 2.0
> **Audience**: Epistola contributors and API consumers

Part of this design is in the contract and part is not:

- **In the spec**: consumer registration and lifecycle, tenant-scoped, under the `Consumers` tag
  (`contracts/api/paths/consumers.yaml`, `contracts/api/components/schemas/consumers.yaml`). Every
  operation is **experimental** (`x-experimental: true`): Epistola Suite may not implement it yet,
  and it may still change. Sections 3 and 5.1–5.2 describe it as the spec has it; the spec wins
  where they differ.
- **Proposed**: dependency declaration, impact queries, impact warnings, document attribution and
  the event integration (sections 4, 5.3–5.5 and 6). None of it is in the spec. Every operation,
  schema, member and problem type these sections name is marked **proposed**.

---

## 1. Problem Statement

Epistola has little visibility into which systems consume its API. Authentication (API keys,
experimentally JWTs) identifies callers, and the Consumers API records each consuming application per
tenant, but nothing records what a consumer depends on. This creates several concrete problems:

- **No dependency tracking** — Releasing a draft that removes or changes a template, or
  undeploying a release from an environment, has unknown blast radius. A template might be used by
  five consumers, but Epistola has no way to surface this before the release or undeploy proceeds.
- **No attribution** — The `DocumentDto.createdBy` field exists but is always `null`. Generated
  documents cannot be traced back to the system that requested them.
- **No impact analysis for breaking changes** — When a release changes a template's data contract,
  removes a variant, or an environment stops serving a release, there is no mechanism to warn
  operators about dependent consumers before the change takes effect.

The registry itself, which systems connect to a tenant and who to contact about them, is what the
Consumers API provides.

### Goals

1. Provide a registry of consumer systems that connect to a tenant. (In the spec.)
2. Enable consumers to declare which templates, in which catalogs, they depend on.
3. Surface dependency information when an operation that can break a consumer (releasing a draft,
   undeploying a release) is attempted.
4. Populate the `DocumentDto.createdBy` field with the consumer that requested generation.

### Non-goals

- **Request-level logging** — Tracking individual API calls per consumer (which endpoints, how
  often) is a server-side observability concern, not an API contract concern.
- **Usage analytics and dashboards** — Aggregated metrics, rate trends, and consumption reports are
  operational tools built on top of request logging. The contract carries coarse signals only:
  `ConsumerDto.lastSeenAt` and `ConsumerNodeDto.lastSeenAt` per consumer, and
  `DeploymentDto.lastUsedAt` and `documentsRequested` per deployed release.
- **Audit trail** — The future event system (see `event-system.md`) will provide an audit trail via
  the `actor` field on events. Consumer registration enriches that field but does not replace it.
- **Access control enforcement** — Dependencies are informational. They do not gate which templates
  a consumer can access. Authorization is handled by the consumer's granted permissions
  (`ConsumerPermissions`) and, for API keys and users, by Suite roles.

---

## 2. Scope: Contract vs Server-Side

This design covers what belongs in the **API contract** (OpenAPI spec). Server-side implementation
details are explicitly out of scope.

### In scope (contract-level)

| Capability | Description | Status |
|---|---|---|
| Consumer registration and lifecycle | Register, approve, reject, list, get, update, delete consumers, and rotate a public key | In the spec (experimental) |
| Dependency declaration | Consumers declare which templates they depend on | Proposed |
| Impact queries | Query which consumers depend on a given template | Proposed |
| Impact warnings | Releasing or undeploying returns 409 Conflict when dependents would be affected | Proposed |
| Attribution | `DocumentDto.createdBy` populated with the consumer ID | Proposed (the field exists) |

### Out of scope (server-side only)

| Capability | Why out of scope |
|---|---|
| API-key identity mapping | API-key consumers are provisioned out of band by a tenant manager and appear directly as `active`; which key belongs to which consumer is server configuration. |
| Request logging per consumer | Observability infrastructure, not an API contract |
| Usage analytics | Built on top of request logs, not exposed as API endpoints |
| Audit trail enrichment | Covered by the event system's `actor` field (Section 2.2 of `event-system.md`) |

---

## 3. Consumer Model

### 3.1 Identity

A consumer is identified by `id` in `ConsumerDto`, and by the `consumerId` path parameter:

- **Pattern**: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
- **Length**: 3-63 characters
- **Unique**: within the tenant
- **Examples**: `invoice-service`, `crm-backend`, `report-generator`

How the ID is chosen depends on how the consumer authenticates (`authMethod`):

- **Self-signed JWT**: the consumer chooses it at registration (`RegisterConsumerRequest.id`) and
  uses it as the `iss` claim of the JWTs it signs.
- **OAuth**: derived from the JWT `client_id` claim when the consumer is auto-registered on its
  first authenticated request to the tenant.
- **API key**: set when a tenant manager provisions the consumer, out of band.

### 3.2 Scope: Tenant-level

Consumers are registered **per tenant**, under `/tenants/{tenantKey}/consumers`, like every other
Epistola resource except tenants themselves.

**Justification**: each tenant independently manages which applications can access its resources.
A consuming system that serves several tenants (an `invoice-service` generating invoices for
`acme-corp` and `globex-inc`) registers in each, with separate approvals, separate granted
permissions and separate expiry.

The trade-off is that the same application is a separate consumer in each tenant. The API offers
no cross-tenant view of one application, and so no cross-tenant impact analysis.

### 3.3 Metadata

`ConsumerDto` has these members (required ones in bold):

| Member | Type | Description |
|---|---|---|
| **`id`** | string (slug, 3-63) | Unique within the tenant; see 3.1 |
| **`tenantKey`** | string | Tenant the consumer is registered in |
| **`name`** | string | Human-readable name of the consuming application |
| `description` | string or null | What the application does and why it uses Epistola |
| `contact` | string or null | Team or individual responsible for the application |
| **`status`** | `pending`, `active`, `rejected`, `expired`, `inactive` | Lifecycle status; see 3.5 |
| **`authMethod`** | `oauth`, `self-signed-jwt`, `api-key` | How the consumer authenticates; see 3.4 |
| **`requestedPermissions`** | `ConsumerPermissions` | What the consumer asked for; see 3.6 |
| **`grantedPermissions`** | `ConsumerPermissions` | What a tenant manager granted |
| `publicKey` | string or null | PEM public key; null unless `authMethod` is `self-signed-jwt` |
| `expiresAt` | date-time or null | When the approval expires; null means no expiry |
| `rejectionReason` | string or null | Reason given when the registration was rejected |
| **`nodes`** | array of `ConsumerNodeDto` | Known instances of the application |
| `totalPartitions` | integer or null | Partitions for generation result collection; null until the consumer has polled `collectGenerationResults` |
| **`createdAt`** | date-time | When the consumer was registered |
| `lastSeenAt` | date-time or null | When the server last received a request from the consumer |
| **`lastModified`** | date-time | When the consumer record was last changed; equal to `createdAt` at registration, moved forward by every change to the consumer |

`ConsumerNodeDto` describes one instance of the application:

| Member | Type | Description |
|---|---|---|
| **`nodeId`** | string | From the `X-EP-Node-Id` header |
| `userAgent` | string or null | Last seen `User-Agent` header from this node |
| `partitions` | array of integer | Partitions assigned to this node for result collection |
| **`lastSeenAt`** | date-time | When the server last received a request from this node |

### 3.4 Auth Linkage

The contract records how a consumer authenticates (`authMethod`), and the server identifies the
consumer from its credentials:

- **`oauth`** (experimental): JWTs issued by an external identity provider. The `client_id` claim
  identifies the consumer.
- **`self-signed-jwt`** (experimental): short-lived JWTs (60 seconds) signed with the consumer's
  private key. The `iss` claim is the consumer ID, and the server verifies the signature with the
  registered `publicKey`.
- **`api-key`**: a long-lived key provisioned by a tenant manager, sent as
  `Authorization: ApiKey <key>`. This is the supported
  method. Which key belongs to which consumer is server configuration.

In every case the credential proves identity only. What the consumer may do is its
`grantedPermissions`, set by a tenant manager.

**Proposed, not in the spec**: an optional `X-Consumer-Id` header for explicit identification, for
shared credentials or development environments. The spec has no such header.

### 3.5 Lifecycle

| Status | Meaning | Reached by |
|---|---|---|
| `pending` | Registered, awaiting a tenant manager's approval | `registerConsumer` (self-signed JWT), or the first authenticated request of an OAuth consumer |
| `active` | Approved and operational | `approveConsumer`; `updateConsumer` with `status: active` to reactivate; API-key consumers start here |
| `rejected` | A tenant manager rejected the registration | `rejectConsumer`, optionally with a `reason` (`rejectionReason`) |
| `expired` | The approval's `expiresAt` passed | Time |
| `inactive` | Deactivated by a tenant manager | `updateConsumer` with `status: inactive` |

`updateConsumer` can only move between `active` and `inactive`; approval and rejection have their
own operations.

`deleteConsumer` permanently removes the consumer from the tenant. Partitions assigned to its nodes
are released. Uncollected generation results stay in the system but are no longer deliverable
through `collectGenerationResults`; the documents can still be read with `downloadDocument`.
Documents keep the consumer ID in `createdBy` (an orphaned reference).

**Proposed**: deleting a consumer also removes its dependency declarations (section 4).

### 3.6 Permissions

A consumer requests permissions at registration (`requestedPermissions`), or, for OAuth, through
its JWT scopes. A tenant manager grants equal or fewer on approval (`grantedPermissions`), and can
change them later with `updateConsumer`. `ConsumerPermissions` has one level per resource type.
Higher levels imply lower ones, and every level defaults to `none`:

| Resource | Levels | Meaning |
|---|---|---|
| `templates` | `none`, `read`, `write` | read: list and get templates, their variants' content and data contracts. write: create, update and delete templates, edit their content in drafts, mark ready |
| `variants` | `none`, `read`, `write` | read: list and get variants. write: create, update, delete |
| `themes` | `none`, `read`, `write` | read: list and get themes. write: create, update, delete |
| `environments` | `none`, `read`, `write`, `deploy` | read: list and get environments, what they serve and their deployment history. write: create, update, delete environments. deploy: deploy and undeploy releases of catalogs (released ones and drafts) |
| `stencils` | `none`, `read`, `write` | read: list and get stencils and their usage. write: create, update, edit their content in drafts, mark ready |
| `catalogs` | `none`, `read`, `write`, `release` | read: list catalogs, their releases and a draft's changes. write: import catalogs. release: cut catalog releases |
| `generation` | `none`, `read`, `write` | read: view jobs and documents, and collect results through `collectGenerationResults`. write: submit generation and preview |
| `consumers` | `none`, `read`, `manage` | read: list and view consumers. manage: approve, reject, update and delete consumers |

For OAuth consumers, requested permissions are derived from JWT scopes of the form
`epistola:{resource}:{level}`, for example `epistola:generation:write`,
`epistola:catalogs:release` or `epistola:environments:deploy`.

---

## 4. Dependency Model (proposed)

Nothing in this section is in the spec.

### 4.1 Scope: Tenant-Level

Dependencies are **tenant-scoped**, like the consumers that declare them and the catalogs,
templates and environments they reference.

A dependency states: "Consumer X depends on template Y of catalog C." A catalog is named by its
registry, namespace and key, never its key alone, so the template's key alone does not identify it.

A dependency names the template, not a release: templates are released with their catalog,
and a consumer renders whichever release its requests select (by `versionRange` and `environmentKey`).

### 4.2 Structure

```yaml
# Proposed, not in the spec
TemplateDependencyDto:
  type: object
  required:
    - consumerId
    - registry
    - namespace
    - catalogKey
    - templateKey
    - createdAt
  properties:
    consumerId:
      type: string
      description: ID of the consumer that declared this dependency
      example: invoice-service
    registry:
      type: string
      description: >
        Registry of the template's catalog, written in full (the
        default registry by its name, never `~`)
      example: exchange.epistola.app
    namespace:
      type: string
      description: Namespace of the template's catalog
      example: acme-corp
    catalogKey:
      type: string
      description: Key of the template's catalog
      example: letters
    templateKey:
      type: string
      description: Template this consumer depends on
      example: invoice
    variantKeys:
      type: array
      items:
        type: string
      description: >
        Specific variants the consumer uses. Empty array means all
        variants (or the consumer doesn't track variant-level dependencies).
      example: ["english", "dutch"]
    environmentKeys:
      type: array
      items:
        type: string
      description: >
        Environments the consumer generates through (its requests'
        environmentKey). Empty array means all environments.
      example: ["production"]
    versionRange:
      type: [string, "null"]
      description: >
        The version range the consumer's requests name, if any
        (see "Version ranges" in the API description). A release
        that the range does not match does not affect the consumer.
      example: "^1.4.0"
    purpose:
      type: [string, "null"]
      description: Why this consumer depends on this template
      example: Generates monthly PDF invoices for B2B customers
    createdAt:
      type: string
      format: date-time
      description: When this dependency was declared
      example: "2026-10-17T10:00:00Z"
    lastModified:
      type: [string, "null"]
      format: date-time
      description: When this dependency was last updated
      example: null
```

### 4.3 Declaration Model

Dependencies are declared **explicitly** via the API, not auto-detected from usage patterns. This
is deliberate:

- Auto-detection would require tracking every generation request per consumer, which is a
  server-side observability concern.
- Explicit declaration is more reliable — a consumer knows which templates it uses better than
  usage logs do (a template might be used only monthly).
- Explicit dependencies can include intent (the `purpose` field) and forward-looking declarations
  (declaring a dependency before the consumer starts using the template in production).

### 4.4 Authorization

| Operation | Who can perform it |
|---|---|
| Declare/update a dependency | The consumer itself, for its own record; or a caller allowed to update consumers (`TENANT_USERS`, or `consumers: manage`) |
| Remove a dependency | The same |
| List dependencies for a consumer | The consumer itself; or a caller allowed to view consumers (`TENANT_USERS`, or `consumers: read`) |
| List dependents for a template | A caller allowed to view consumers |

The intent is that consumers manage their own dependencies as part of their deployment pipeline.
Tenant managers can view all dependencies in their tenant for operational awareness.

---

## 5. API Design

### 5.1 Consumer Registration and Lifecycle (in the spec, experimental)

The operations live under the tenant, at `/tenants/{tenantKey}/consumers`. All are experimental.
Every request and response body uses `application/vnd.epistola.v2+json`.

**Authorization**: `listConsumers`, `getConsumer`, `updateConsumer`, `deleteConsumer`,
`approveConsumer` and `rejectConsumer` require the Suite permission `TENANT_USERS`
(`x-required-permissions`). `registerConsumer` requires no authentication (`security: []`): the
public key in the request establishes the consumer's identity. `rotatePublicKey` admits only the
consumer itself: it declares `security: bearerAuth` alone and `x-required-authentication`, and the
request must carry a self-signed JWT issued by the consumer in the path and signed with its current
key. No permission admits anyone else; an API key or another consumer's JWT is refused with 403.

#### Register a consumer

Self-service registration for self-signed JWT consumers. OAuth consumers are auto-registered as
`pending` on their first authenticated request instead, and API-key consumers are provisioned by a
tenant manager and start as `active`.

```
POST /tenants/{tenantKey}/consumers/register
Content-Type: application/vnd.epistola.v2+json

{
  "id": "invoice-service",
  "name": "Invoice Service",
  "description": "Generates PDF invoices for the billing pipeline",
  "contact": "billing-team@acme-corp.com",
  "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...",
  "requestedPermissions": {
    "generation": "write",
    "catalogs": "read"
  }
}

201 Created
Content-Type: application/vnd.epistola.v2+json

{
  "id": "invoice-service",
  "tenantKey": "acme-corp",
  "name": "Invoice Service",
  "description": "Generates PDF invoices for the billing pipeline",
  "contact": "billing-team@acme-corp.com",
  "status": "pending",
  "authMethod": "self-signed-jwt",
  "requestedPermissions": {
    "templates": "none", "variants": "none", "themes": "none", "environments": "none",
    "stencils": "none", "catalogs": "read", "generation": "write", "consumers": "none"
  },
  "grantedPermissions": {
    "templates": "none", "variants": "none", "themes": "none", "environments": "none",
    "stencils": "none", "catalogs": "none", "generation": "none", "consumers": "none"
  },
  "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...",
  "expiresAt": null,
  "rejectionReason": null,
  "nodes": [],
  "totalPartitions": null,
  "createdAt": "2026-10-17T10:00:00Z",
  "lastSeenAt": null,
  "lastModified": "2026-10-17T10:00:00Z"
}
```

Responses: 201, 400 (`validation-error`), 404 and 409.

#### Approve or reject a consumer

```
POST /tenants/{tenantKey}/consumers/{consumerId}/approve
Content-Type: application/vnd.epistola.v2+json

{
  "grantedPermissions": { "generation": "write" },
  "expiresAt": "2027-10-17T00:00:00Z"
}

200 OK   (ConsumerDto, status = active)
```

```
POST /tenants/{tenantKey}/consumers/{consumerId}/reject
Content-Type: application/vnd.epistola.v2+json

{
  "reason": "Unknown application — contact the platform team for onboarding"
}

200 OK   (ConsumerDto, status = rejected)
```

The reject body is optional.

#### List consumers

```
GET /tenants/{tenantKey}/consumers?q=invoice&status=active&page=0&size=20
Accept: application/vnd.epistola.v2+json

200 OK
Content-Type: application/vnd.epistola.v2+json

{
  "items": [ { ...ConsumerDto... } ],
  "page": {
    "number": 0,
    "size": 20,
    "total": 1,
    "totalPages": 1
  }
}
```

`q` searches name and ID; `status` takes one of the five statuses.

#### Get consumer

```
GET /tenants/{tenantKey}/consumers/{consumerId}
Accept: application/vnd.epistola.v2+json

200 OK   (ConsumerDto)
```

#### Update consumer

```
PATCH /tenants/{tenantKey}/consumers/{consumerId}
Content-Type: application/vnd.epistola.v2+json

{
  "description": "Generates PDF and HTML invoices for the billing pipeline",
  "status": "inactive"
}

200 OK   (ConsumerDto)
```

Every member is optional: `name`, `description` and `contact` (null clears the last two), `status`
(`active` or `inactive` only), `grantedPermissions`, and `expiresAt` (null removes the expiry).

#### Rotate public key

```
PUT /tenants/{tenantKey}/consumers/{consumerId}/public-key
Authorization: Bearer <JWT signed with the current key>
Content-Type: application/vnd.epistola.v2+json

{
  "publicKey": "-----BEGIN PUBLIC KEY-----\n..."
}

200 OK   (ConsumerDto)
```

The old key is invalidated immediately. API-key consumers cannot rotate this way; a tenant manager
issues a new key and revokes the old one out of band.

#### Delete consumer

```
DELETE /tenants/{tenantKey}/consumers/{consumerId}

204 No Content
```

See 3.5 for the effect on partitions, results and documents.

### 5.2 Request and Response Schemas

All in `contracts/api/components/schemas/consumers.yaml`:

| Schema | Members (required in bold) |
|---|---|
| `RegisterConsumerRequest` | **`id`** (slug, 3-63), **`name`** (1-255), `description` (max 1000), `contact` (max 255), **`publicKey`** (PEM, RSA 2048+ or Ed25519, max 8192), **`requestedPermissions`** |
| `ApproveConsumerRequest` | **`grantedPermissions`**, `expiresAt` (nullable) |
| `RejectConsumerRequest` | `reason` (max 1000) |
| `UpdateConsumerRequest` | `name` (1-255), `description` (nullable, max 1000), `contact` (nullable, max 255), `status` (`active`, `inactive`), `grantedPermissions`, `expiresAt` (nullable) |
| `RotatePublicKeyRequest` | **`publicKey`** (max 8192) |
| `ConsumerListResponse` | **`items`** (`ConsumerDto`), **`page`** (`PageMeta`) |
| `ConsumerDto` | See 3.3 |
| `ConsumerNodeDto` | See 3.3 |
| `ConsumerPermissions` | See 3.6 |

### 5.3 Dependency Management (proposed)

Dependencies are managed under the consumer that declares them. The path names the template by its
catalog's three parts and its key; as everywhere in a path, `~` means the default registry.

#### Declare or update a dependency

Uses `PUT` for idempotent upsert — the consumer either creates or replaces its dependency
declaration for a given template.

```
PUT /tenants/{tenantKey}/consumers/{consumerId}/dependencies/{registry}/{namespace}/{catalogKey}/{templateKey}
Content-Type: application/vnd.epistola.v2+json

{
  "variantKeys": ["english", "dutch"],
  "environmentKeys": ["production"],
  "versionRange": "^1.4.0",
  "purpose": "Generates monthly PDF invoices for B2B customers"
}

200 OK
Content-Type: application/vnd.epistola.v2+json

{
  "consumerId": "invoice-service",
  "registry": "exchange.epistola.app",
  "namespace": "acme-corp",
  "catalogKey": "letters",
  "templateKey": "invoice",
  "variantKeys": ["english", "dutch"],
  "environmentKeys": ["production"],
  "versionRange": "^1.4.0",
  "purpose": "Generates monthly PDF invoices for B2B customers",
  "createdAt": "2026-10-17T10:30:00Z",
  "lastModified": null
}
```

Request schema:

```yaml
# Proposed, not in the spec
DeclareTemplateDependencyRequest:
  type: object
  properties:
    variantKeys:
      type: array
      items:
        type: string
      description: Specific variants the consumer uses (empty = all)
      example: ["english", "dutch"]
    environmentKeys:
      type: array
      items:
        type: string
      description: Environments the consumer generates through (empty = all)
      example: ["production"]
    versionRange:
      type: [string, "null"]
      maxLength: 100
      description: The version range the consumer's requests name, if any
      example: "^1.4.0"
    purpose:
      type: string
      maxLength: 500
      description: Why this consumer depends on this template
      example: Generates monthly PDF invoices for B2B customers
```

The template need not exist in any release of the catalog yet: a consumer may declare a dependency
before the template is released.

#### List dependencies for a consumer

```
GET /tenants/{tenantKey}/consumers/{consumerId}/dependencies
Accept: application/vnd.epistola.v2+json

200 OK
Content-Type: application/vnd.epistola.v2+json

{
  "items": [
    {
      "consumerId": "invoice-service",
      "registry": "exchange.epistola.app",
      "namespace": "acme-corp",
      "catalogKey": "letters",
      "templateKey": "invoice",
      "variantKeys": ["english", "dutch"],
      "environmentKeys": ["production"],
      "versionRange": "^1.4.0",
      "purpose": "Generates monthly PDF invoices for B2B customers",
      "createdAt": "2026-10-17T10:30:00Z",
      "lastModified": null
    },
    {
      "consumerId": "invoice-service",
      "registry": "exchange.epistola.app",
      "namespace": "acme-corp",
      "catalogKey": "letters",
      "templateKey": "credit-note",
      "variantKeys": [],
      "environmentKeys": ["production"],
      "versionRange": null,
      "purpose": "Generates credit notes when invoices are reversed",
      "createdAt": "2026-10-17T10:35:00Z",
      "lastModified": null
    }
  ]
}
```

The dependency list is not paginated — the number of templates a single consumer depends on is
expected to be small (tens, not thousands).

Response schema:

```yaml
# Proposed, not in the spec
TemplateDependencyListResponse:
  type: object
  required:
    - items
  properties:
    items:
      type: array
      items:
        $ref: '#/TemplateDependencyDto'
```

#### Remove a dependency

```
DELETE /tenants/{tenantKey}/consumers/{consumerId}/dependencies/{registry}/{namespace}/{catalogKey}/{templateKey}

204 No Content
```

### 5.4 Impact Query (proposed)

Allows querying which consumers depend on a given template. This is the read side of dependency
tracking — useful for impact analysis before releasing or undeploying.

The path follows `validateTemplateData`, which also addresses a template in a catalog without a
release (`/catalogs/{registry}/{namespace}/{catalogKey}/templates/{templateKey}/validate`): a
dependency is on the template, whatever release a consumer renders.

#### List dependents for a template

```
GET /tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/templates/{templateKey}/dependents?status=active
Accept: application/vnd.epistola.v2+json

200 OK
Content-Type: application/vnd.epistola.v2+json

{
  "items": [
    {
      "consumerId": "invoice-service",
      "registry": "exchange.epistola.app",
      "namespace": "acme-corp",
      "catalogKey": "letters",
      "templateKey": "invoice",
      "variantKeys": ["english", "dutch"],
      "environmentKeys": ["production"],
      "versionRange": "^1.4.0",
      "purpose": "Generates monthly PDF invoices for B2B customers",
      "createdAt": "2026-10-17T10:30:00Z",
      "lastModified": null
    },
    {
      "consumerId": "report-generator",
      "registry": "exchange.epistola.app",
      "namespace": "acme-corp",
      "catalogKey": "letters",
      "templateKey": "invoice",
      "variantKeys": [],
      "environmentKeys": [],
      "versionRange": null,
      "purpose": "Generates monthly summary reports including invoice copies",
      "createdAt": "2026-10-18T09:00:00Z",
      "lastModified": null
    }
  ]
}
```

Query parameters:

| Parameter | Type | Description |
|---|---|---|
| `status` | string | Filter by consumer status: `active` (default), any other `ConsumerDto.status`, or `all` |

Response schema:

```yaml
# Proposed, not in the spec
TemplateDependentListResponse:
  type: object
  required:
    - items
  properties:
    items:
      type: array
      items:
        $ref: '#/TemplateDependencyDto'
```

### 5.5 Impact Warnings (proposed)

In 2.0, deleting or changing a template does not reach a consumer when it happens. Templates are
edited in a draft release, which generation does not render; the change reaches consumers
when the draft is released and an environment serves the release. (A non-production environment
a draft is deployed to renders it live; this proposal does not warn about that.)

So the warnings belong on the operations that change what consumers render:

| Operation | Conflict condition |
|---|---|
| `releaseCatalogDraft` | The release removes a template or a variant an active consumer depends on (`ResourceChangeDto.change: removed`), and the consumer's `versionRange`, if any, matches the new release |
| `undeployCatalogRelease`, and `deployCatalogRelease` with `replace=true` | An active consumer depends on a template of the catalog through this environment (`environmentKeys` empty or containing it), and the environment would no longer serve a release that matches the consumer's `versionRange` |

The response is **409 Conflict** instead of proceeding, as an RFC 9457 problem. The problem type
`has-dependents` and its `dependents` member are **proposed**: neither is in `x-problem-types`.

```
POST /tenants/{tenantKey}/catalogs/~/acme-corp/letters/releases/k7m2x9qa/release

409 Conflict
Content-Type: application/problem+json

{
  "type": "https://epistola.app/errors/has-dependents",
  "title": "Consumers depend on this",
  "status": 409,
  "detail": "Releasing removes template 'invoice': 2 active consumers depend on it",
  "dependents": [
    {
      "consumerId": "invoice-service",
      "name": "Invoice Service",
      "contact": "billing-team@acme-corp.com",
      "templateKey": "invoice"
    },
    {
      "consumerId": "report-generator",
      "name": "Report Generator",
      "contact": "analytics-team@acme-corp.com",
      "templateKey": "invoice"
    }
  ]
}
```

The `dependents` array includes consumer name and contact so operators can immediately reach out to
affected teams.

#### Proceeding anyway

The opt-in follows `acknowledgedBehind` on `ReleaseCatalogDraftRequest`, which already acknowledges
a known risk by naming it:

- `releaseCatalogDraft`: a **proposed** `acknowledgedDependents` member, an array of consumer IDs.
  The release proceeds when it names every dependent; naming others changes nothing.
- `undeployCatalogRelease` and `deployCatalogRelease` have no request body: a **proposed** query parameter
  `acknowledgeDependents=true`.

Proceeding does not remove the dependency records: they still describe what the consumer expects,
and the next impact query shows them.

Response schema for the conflict:

```yaml
# Proposed, not in the spec
HasDependentsProblemDetail:
  allOf:
    - $ref: '#/ProblemDetail'
    - type: object
      required:
        - dependents
      properties:
        dependents:
          type: array
          items:
            type: object
            required:
              - consumerId
              - name
              - templateKey
            properties:
              consumerId:
                type: string
                description: ID of the dependent consumer
                example: invoice-service
              name:
                type: string
                description: Name of the dependent consumer
                example: Invoice Service
              contact:
                type: [string, "null"]
                description: Contact for the dependent consumer
                example: billing-team@acme-corp.com
              templateKey:
                type: string
                description: The template the consumer depends on
                example: invoice
```

A new problem type touches every client's problem parser and the server stubs; see "The
problem-type registry and code that follows it" in the repository's `CLAUDE.md`.

### 5.6 Endpoint Summary

In the spec (experimental):

| Method | Path | Operation | Tag | Permission |
|---|---|---|---|---|
| `POST` | `/tenants/{tenantKey}/consumers/register` | `registerConsumer` | Consumers | none (unauthenticated) |
| `GET` | `/tenants/{tenantKey}/consumers` | `listConsumers` | Consumers | `TENANT_USERS` |
| `GET` | `/tenants/{tenantKey}/consumers/{consumerId}` | `getConsumer` | Consumers | `TENANT_USERS` |
| `PATCH` | `/tenants/{tenantKey}/consumers/{consumerId}` | `updateConsumer` | Consumers | `TENANT_USERS` |
| `DELETE` | `/tenants/{tenantKey}/consumers/{consumerId}` | `deleteConsumer` | Consumers | `TENANT_USERS` |
| `POST` | `/tenants/{tenantKey}/consumers/{consumerId}/approve` | `approveConsumer` | Consumers | `TENANT_USERS` |
| `POST` | `/tenants/{tenantKey}/consumers/{consumerId}/reject` | `rejectConsumer` | Consumers | `TENANT_USERS` |
| `PUT` | `/tenants/{tenantKey}/consumers/{consumerId}/public-key` | `rotatePublicKey` | Consumers | JWT signed with the current key |

Proposed:

| Method | Path | Operation | Tag | Permission |
|---|---|---|---|---|
| `PUT` | `/tenants/{tenantKey}/consumers/{consumerId}/dependencies/{registry}/{namespace}/{catalogKey}/{templateKey}` | `declareTemplateDependency` | Consumer Dependencies | see 4.4 |
| `GET` | `/tenants/{tenantKey}/consumers/{consumerId}/dependencies` | `listConsumerDependencies` | Consumer Dependencies | see 4.4 |
| `DELETE` | `/tenants/{tenantKey}/consumers/{consumerId}/dependencies/{registry}/{namespace}/{catalogKey}/{templateKey}` | `deleteTemplateDependency` | Consumer Dependencies | see 4.4 |
| `GET` | `/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/templates/{templateKey}/dependents` | `listTemplateDependents` | Consumer Dependencies | see 4.4 |

### 5.7 Path Design

**Consumer registration and lifecycle** is placed at `/tenants/{tenantKey}/consumers` because
consumers are tenant-scoped (3.2). `register`, `approve`, `reject` and `public-key` are
sub-resources because each is a distinct action with its own authorization.

**Dependency management** (proposed) is placed under
`/tenants/{tenantKey}/consumers/{consumerId}/dependencies/...` because a dependency belongs to the
consumer that declares it. The rest of the path names the template the same way the API names a
catalog everywhere: registry, namespace and catalog key as separate segments.

**Impact query** (proposed) is placed under
`/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/templates/{templateKey}/dependents`
because the question is "who depends on this template?" — a template-centric view, independent of
any one release.

---

## 6. Integration Points (proposed)

### 6.1 Event System

The event system design (`event-system.md`, Section 2.2) defines an `actor` field on the event
envelope:

```yaml
actor:
  type: object
  properties:
    type:
      type: string
      enum: [user, api-key, system]
    identifier:
      type: string
```

With consumer registration, the `actor` object gains a `consumerId` property:

```yaml
actor:
  type: object
  properties:
    type:
      type: string
      enum: [user, api-key, system]
    identifier:
      type: string
    consumerId:
      type: [string, "null"]
      description: >
        Consumer that initiated this action, if identified.
        Populated when the server can map the authenticated caller
        to a registered consumer.
```

This enriches every event: instead of just "user jane@acme-corp.com performed this action", the
event now records "user jane@acme-corp.com via invoice-service performed this action."

Consumer registration also generates its own events, named as `event-system.md` names event types:

| Event Type | Trigger |
|---|---|
| `consumer.registered` | `registerConsumer`, or the auto-registration of an OAuth consumer |
| `consumer.approved` | `approveConsumer` |
| `consumer.rejected` | `rejectConsumer` |
| `consumer.updated` | `updateConsumer` |
| `consumer.public-key-rotated` | `rotatePublicKey` |
| `consumer.expired` | The approval's `expiresAt` passed |
| `consumer.deleted` | `deleteConsumer` |
| `consumer-dependency.declared` | `declareTemplateDependency` (proposed) |
| `consumer-dependency.removed` | `deleteTemplateDependency` (proposed) |

Consumers and their dependencies are tenant-scoped, so all of these events fit the tenant-scoped
event model without modification.

### 6.2 Document Generation

The `DocumentDto.createdBy` field (currently `type: [string, "null"]`, always `null`) gets populated
with the consumer ID when the server can identify the calling consumer.

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "tenantKey": "acme-corp",
  "registry": "exchange.epistola.app",
  "namespace": "acme-corp",
  "catalogKey": "letters",
  "templateKey": "invoice",
  "variantKey": "english",
  "version": "1.4.0",
  "filename": "invoice-2026-001.pdf",
  "correlationId": "order-7890",
  "contentType": "application/pdf",
  "sizeBytes": 184320,
  "createdAt": "2026-10-17T08:00:03Z",
  "createdBy": "invoice-service"
}
```

This is fully backwards compatible — the field already exists and is nullable. Consumers that don't
check `createdBy` are unaffected.

**Note**: The field description in `generation.yaml` already says that `createdBy` contains a
**consumer identifier** (e.g., `invoice-service`), not a human user identifier, kept when the
consumer is deleted: "which system", not "which person" — a deliberate choice since document
generation is typically triggered by automated systems, not individual users.

### 6.3 Authentication

The consumer identity is resolved from the credential, as 3.4 describes:

1. **OAuth** (experimental) — the JWT `client_id` claim is the consumer ID.
2. **Self-signed JWT** (experimental) — the JWT `iss` claim is the consumer ID, verified against the
   registered `publicKey`.
3. **API key** — each key is associated with a consumer in the server's key store.

A caller that is not a consumer (a user in Epistola Suite, an API key not linked to one) leaves
`createdBy` null.

---

## 7. Recommendation

### Phased Implementation

Consumer registration delivers value incrementally. Each phase is independently useful and does not
require subsequent phases.

#### Phase 1: Consumer Registry + Attribution

**Scope**: Consumer registration and lifecycle, and `createdBy` population.

**Status**: The registry is in the spec, as the experimental Consumers API. Attribution is not done:
the server does not populate `createdBy`, and its description still describes a user.

**Value**: Provides a registry of which systems use a tenant. Documents can be traced back to the
generating system.

**API changes still needed**:
- Update the `DocumentDto.createdBy` description (6.2)
- Server populates `DocumentDto.createdBy` (no schema change needed — field exists)

**Effort**: Small.

#### Phase 2: Dependencies + Impact Analysis

**Scope**: Dependency declaration, impact queries, and 409 Conflict on release and undeploy.

**Value**: This is the high-value feature. Operators can now see which systems are affected before
releasing or undeploying. A release cannot remove a template consumers depend on without someone
acknowledging it.

**API changes**:
- Add `TemplateDependencyDto`, `DeclareTemplateDependencyRequest`, `TemplateDependencyListResponse`,
  `TemplateDependentListResponse` schemas
- Add dependency management endpoints under `/tenants/{tenantKey}/consumers/{consumerId}/dependencies/`
- Add the impact query endpoint under
  `/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/templates/{templateKey}/dependents`
- Register the `has-dependents` problem type with `HasDependentsProblemDetail`
- Return 409 from `releaseCatalogDraft`, `undeployCatalogRelease` and `deployCatalogRelease` (`replace=true`)
  when dependents would be affected
- Add `acknowledgedDependents` to `ReleaseCatalogDraftRequest` and `acknowledgeDependents` to
  `undeployCatalogRelease` and `deployCatalogRelease`
- Add `Consumer Dependencies` tag

**Effort**: Medium — the dependency model is simple, but the 409 Conflict integration touches
releasing and deploying.

#### Phase 3: Event System Integration

**Scope**: Enrich event `actor` with `consumerId`, add consumer and dependency event types.

**Value**: Full audit trail with consumer attribution. Every event records not just who performed
the action, but which system they used.

**API changes**:
- Extend `actor` schema in event envelope with `consumerId`
- Add consumer and dependency event types

**Depends on**: Event system implementation (see `event-system.md`).

**Effort**: Small — schema additions to the event model.

---

## Appendix A: Resource Scopes

Consumers are tenant-scoped like every resource but tenants themselves. Resources in a catalog are
scoped further, to a release of the catalog.

| Resource | Scope | Path Prefix | Management Permission |
|---|---|---|---|
| Tenants | Platform | `/tenants` | `TENANT_MANAGER` platform role (create, delete); `TENANT_SETTINGS` (update) |
| Consumers | Tenant | `/tenants/{tenantKey}/consumers` | `TENANT_USERS` |
| Environments | Tenant | `/tenants/{tenantKey}/environments` | `TENANT_SETTINGS`; `ENVIRONMENT_DEPLOY` to deploy and undeploy |
| Releases | Catalog | `/tenants/{tenantKey}/catalogs/{registry}/{namespace}/{catalogKey}/releases` | `TEMPLATE_EDIT` (drafts); `CATALOG_RELEASE` (release, discard) |
| Templates | Release | `…/releases/{release}/templates` | `TEMPLATE_EDIT` |
| Themes | Release | `…/releases/{release}/themes` | `THEME_EDIT` |

## Appendix B: Scoping Coherence

Consumers and their dependencies are both tenant-scoped, so no operation crosses a scope boundary.
This appendix addresses the remaining consistency concerns.

**Scenario: Consumer is deleted while dependencies exist**

When a consumer is deleted (`deleteConsumer`), its dependencies are deleted with it.

**Scenario: Tenant is deleted**

When a tenant is deleted (`deleteTenant`), its consumers and their dependencies are deleted as part
of the tenant cleanup. The same application registered in other tenants is unaffected: those are
separate consumers.

**Scenario: The same application in several tenants**

Each registration is a separate consumer, with its own approval, permissions and dependencies. Its
dependencies are declared in each tenant separately.

**Scenario: Dependency references a non-existent consumer**

The `PUT` dependency endpoint validates that the consumer ID exists in the tenant. If the consumer
does not exist, the server returns 404. Dependencies cannot be created for unregistered consumers.

**Scenario: The template is not in every release**

A dependency names a template, not a release. The template may be new in a draft, removed in a later
release, or absent from a release an environment still serves; the dependency stays. Impact warnings
(5.5) are where it matters.

## Appendix C: Comparison with Auto-Detection

An alternative to explicit dependency declaration is automatic detection: the server tracks which
consumer calls which template (via generation requests) and builds the dependency graph
automatically.

| Aspect | Explicit Declaration | Auto-Detection |
|---|---|---|
| **Accuracy** | Declared by the consumer — may be incomplete or stale | Based on actual usage — always up to date |
| **Intent** | Captures purpose and scope (variants, environments, version range) | Only captures observed usage patterns |
| **Forward-looking** | Can declare dependencies before first use | Only reflects past behavior |
| **Server complexity** | Low — simple CRUD | High — requires tracking every API call per consumer |
| **Privacy** | Consumer controls what it declares | Server tracks all activity |
| **Contract expressibility** | Full — standard REST endpoints | Minimal — auto-detection is server-internal |

The explicit model is chosen because it fits the contract-first approach (it's an API feature, not
an observability feature), captures richer information (purpose, intended variants, environments
and version ranges), and has minimal server complexity. A server implementation could supplement explicit
declarations with auto-detected usage data as a future enhancement; `DeploymentDto.lastUsedAt` and
`documentsRequested` already report usage per deployed release, though not per consumer.
