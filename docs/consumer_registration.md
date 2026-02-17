# Consumer Registration Design

> **Status**: Draft
> **Date**: 2026-02-17
> **Audience**: Epistola contributors and API consumers

---

## 1. Problem Statement

Epistola currently has no visibility into which systems consume its API. While authentication (JWT/API keys) identifies callers, the domain model does not track consumers, their dependencies, or their activity. This creates several concrete problems:

- **No consumer visibility** — There is no registry of which systems connect to Epistola. When operational issues arise, there is no way to determine which downstream systems are affected or who to contact.
- **No dependency tracking** — Deleting or changing a template has unknown blast radius. A template might be actively used by five services across three tenants, but Epistola has no way to surface this information before the delete proceeds.
- **No attribution** — The `DocumentDto.createdBy` field exists but is always `null`. Generated documents cannot be traced back to the system that requested them.
- **No impact analysis for breaking changes** — When a template's JSON Schema changes, a variant is removed, or a theme is deleted, there is no mechanism to warn operators about dependent consumers before the change takes effect.

### Goals

1. Provide a registry of consumer systems that connect to Epistola.
2. Enable consumers to declare which templates they depend on, per tenant.
3. Surface dependency information when destructive operations (delete, breaking changes) are attempted.
4. Populate the `DocumentDto.createdBy` field with the consumer that requested generation.

### Non-goals

- **Request-level logging** — Tracking individual API calls per consumer (which endpoints, how often) is a server-side observability concern, not an API contract concern.
- **Usage analytics and dashboards** — Aggregated metrics, rate trends, and consumption reports are operational tools built on top of request logging.
- **Audit trail** — The future event system (see `docs/event_system.md`) will provide an audit trail via the `actor` field on events. Consumer registration enriches that field but does not replace it.
- **Access control enforcement** — Dependencies are informational. They do not gate which templates a consumer can access. Authorization is handled by the existing role and tenant permission model.

---

## 2. Scope: Contract vs Server-Side

This design covers what belongs in the **API contract** (OpenAPI spec). Server-side implementation details are explicitly out of scope.

### In scope (contract-level)

| Capability | Description |
|---|---|
| Consumer CRUD | Register, list, get, update, and delete consumer records |
| Dependency declaration | Consumers declare which templates they depend on, per tenant |
| Impact queries | Query which consumers depend on a given template |
| Impact warnings | Destructive operations return 409 Conflict when dependents exist |
| Attribution | `DocumentDto.createdBy` populated with consumer ID |

### Out of scope (server-side only)

| Capability | Why out of scope |
|---|---|
| Auth identity mapping | Mapping JWT `client_id` or API key ID to a consumer is a server configuration concern. The API contract remains auth-agnostic. |
| Request logging per consumer | Observability infrastructure, not an API contract |
| Usage analytics | Built on top of request logs, not exposed as API endpoints |
| Audit trail enrichment | Covered by the event system's `actor` field (Section 2.2 of `docs/event_system.md`) |

---

## 3. Consumer Model

### 3.1 Identity

Consumers use the same slug-based ID pattern as all other Epistola resources:

- **Pattern**: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
- **Length**: 3-63 characters
- **Examples**: `invoice-service`, `crm-backend`, `report-generator`

The consumer ID is client-provided at registration time (like tenant IDs), not server-generated. This ensures consumers can use meaningful, stable identifiers.

### 3.2 Scope: Platform-level

Consumers are registered at the **platform level**, not per tenant. This is a deliberate departure from all existing Epistola resources, which are tenant-scoped.

**Justification**: A consuming system (e.g., `invoice-service`) typically spans multiple tenants. It generates invoices for `acme-corp` and `globex-inc` alike. Registering the same consumer once per tenant would create duplication and make cross-tenant impact analysis impossible.

The platform-level consumer registry is comparable to how tenants themselves are managed — both exist above the tenant boundary. Consumer CRUD uses the same `tenant_control` role already required for tenant management.

### 3.3 Metadata

```yaml
ConsumerDto:
  type: object
  required:
    - id
    - name
    - status
    - createdAt
  properties:
    id:
      type: string
      pattern: '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
      minLength: 3
      maxLength: 63
      description: Unique slug identifier of the consumer
      example: invoice-service
    name:
      type: string
      description: Human-readable name of the consuming system
      example: Invoice Service
    description:
      type: [string, "null"]
      description: What this consumer does and why it uses Epistola
      example: Generates PDF invoices for the billing pipeline
    contact:
      type: [string, "null"]
      description: Team or individual responsible for this consumer (email, Slack channel, etc.)
      example: billing-team@acme-corp.com
    status:
      type: string
      enum: [active, inactive]
      description: >
        Whether this consumer is actively using the API.
        Inactive consumers are retained for historical reference
        but excluded from impact analysis by default.
      example: active
    tags:
      type: array
      items:
        type: string
      description: Freeform tags for categorization and filtering
      example: ["billing", "production"]
    createdAt:
      type: string
      format: date-time
      description: When the consumer was registered
      example: "2026-02-17T10:00:00Z"
    updatedAt:
      type: [string, "null"]
      format: date-time
      description: When the consumer was last updated
      example: "2026-02-17T10:00:00Z"
```

### 3.4 Auth Linkage

The API contract intentionally does **not** expose auth identity mapping. How a consumer is identified at runtime is a server-side configuration concern:

- The server may map JWT `client_id` claims to consumer IDs.
- The server may map API key identifiers to consumer IDs.
- The server may accept an optional `X-Consumer-Id` header for explicit identification.

This keeps the API auth-agnostic — the same design works regardless of whether the deployment uses JWT, API keys, or some other authentication mechanism.

### 3.5 Lifecycle

Consumers have two statuses:

- **`active`** — The consumer is in use. It appears in impact analysis results by default.
- **`inactive`** — The consumer is no longer actively using the API. It is retained for historical context (document attribution, audit trail) but excluded from impact analysis unless explicitly requested.

Deletion permanently removes the consumer record. Dependencies referencing the deleted consumer are also removed. Documents previously attributed to the consumer retain the `createdBy` value (orphaned reference).

---

## 4. Dependency Model

### 4.1 Scope: Tenant-Level

While consumers are platform-level, dependencies are **tenant-scoped** because the resources they reference (templates, variants, environments) are tenant-scoped.

A dependency states: "Consumer X depends on template Y in tenant Z."

### 4.2 Structure

```yaml
TemplateDependencyDto:
  type: object
  required:
    - consumerId
    - templateId
    - createdAt
  properties:
    consumerId:
      type: string
      description: ID of the consumer that declared this dependency
      example: invoice-service
    templateId:
      type: string
      description: Template this consumer depends on
      example: invoice
    variantIds:
      type: array
      items:
        type: string
      description: >
        Specific variants the consumer uses. Empty array means all
        variants (or the consumer doesn't track variant-level dependencies).
      example: ["english", "dutch"]
    environmentIds:
      type: array
      items:
        type: string
      description: >
        Environments where this dependency is active. Empty array
        means all environments.
      example: ["production"]
    purpose:
      type: [string, "null"]
      description: Why this consumer depends on this template
      example: Generates monthly PDF invoices for B2B customers
    createdAt:
      type: string
      format: date-time
      description: When this dependency was declared
      example: "2026-02-17T10:00:00Z"
    updatedAt:
      type: [string, "null"]
      format: date-time
      description: When this dependency was last updated
      example: null
```

### 4.3 Declaration Model

Dependencies are declared **explicitly** via the API, not auto-detected from usage patterns. This is deliberate:

- Auto-detection would require tracking every generation request per consumer, which is a server-side observability concern.
- Explicit declaration is more reliable — a consumer knows which templates it uses better than usage logs do (a template might be used only monthly).
- Explicit dependencies can include intent (the `purpose` field) and forward-looking declarations (declaring a dependency before the consumer starts using the template in production).

### 4.4 Authorization

| Operation | Who can perform it |
|---|---|
| Declare/update a dependency | Any role with tenant access (the consumer system itself, identified via auth) |
| Remove a dependency | Any role with tenant access |
| List dependencies for a consumer | Any role with tenant access |
| List dependents for a template | Any role with tenant access |

The intent is that consumers manage their own dependencies as part of their deployment pipeline. Tenant managers can view all dependencies in their tenant for operational awareness.

---

## 5. API Design

### 5.1 Consumer CRUD (Platform-Level)

Consumer management endpoints live at the platform level, outside any tenant scope. This follows the same pattern as tenant management itself.

**Authorization**: `tenant_control` role required for all consumer CRUD operations.

#### Register a consumer

```
POST /consumers
Content-Type: application/vnd.epistola.v1+json

{
  "id": "invoice-service",
  "name": "Invoice Service",
  "description": "Generates PDF invoices for the billing pipeline",
  "contact": "billing-team@acme-corp.com",
  "tags": ["billing", "production"]
}

201 Created
Content-Type: application/vnd.epistola.v1+json

{
  "id": "invoice-service",
  "name": "Invoice Service",
  "description": "Generates PDF invoices for the billing pipeline",
  "contact": "billing-team@acme-corp.com",
  "status": "active",
  "tags": ["billing", "production"],
  "createdAt": "2026-02-17T10:00:00Z",
  "updatedAt": null
}
```

#### List consumers

```
GET /consumers?q=invoice&status=active&page=0&size=20
Accept: application/vnd.epistola.v1+json

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "items": [
    {
      "id": "invoice-service",
      "name": "Invoice Service",
      "description": "Generates PDF invoices for the billing pipeline",
      "contact": "billing-team@acme-corp.com",
      "status": "active",
      "tags": ["billing", "production"],
      "createdAt": "2026-02-17T10:00:00Z",
      "updatedAt": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

#### Get consumer

```
GET /consumers/{consumerId}
Accept: application/vnd.epistola.v1+json

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "id": "invoice-service",
  "name": "Invoice Service",
  "description": "Generates PDF invoices for the billing pipeline",
  "contact": "billing-team@acme-corp.com",
  "status": "active",
  "tags": ["billing", "production"],
  "createdAt": "2026-02-17T10:00:00Z",
  "updatedAt": null
}
```

#### Update consumer

```
PATCH /consumers/{consumerId}
Content-Type: application/vnd.epistola.v1+json

{
  "description": "Generates PDF and HTML invoices for the billing pipeline",
  "status": "inactive"
}

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "id": "invoice-service",
  "name": "Invoice Service",
  "description": "Generates PDF and HTML invoices for the billing pipeline",
  "contact": "billing-team@acme-corp.com",
  "status": "inactive",
  "tags": ["billing", "production"],
  "createdAt": "2026-02-17T10:00:00Z",
  "updatedAt": "2026-02-17T14:30:00Z"
}
```

#### Delete consumer

```
DELETE /consumers/{consumerId}

204 No Content
```

Deleting a consumer also removes all its dependencies across all tenants.

### 5.2 Request and Response Schemas

Following the existing Epistola pattern (see `tenants.yaml`):

```yaml
CreateConsumerRequest:
  type: object
  required:
    - id
    - name
  properties:
    id:
      type: string
      pattern: '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'
      minLength: 3
      maxLength: 63
      description: >
        Client-provided unique slug identifier for the consumer.
        Must be 3-63 characters, start with a letter, contain only
        lowercase letters, numbers, and non-consecutive hyphens.
      example: invoice-service
    name:
      type: string
      minLength: 1
      maxLength: 255
      description: Human-readable name of the consuming system
      example: Invoice Service
    description:
      type: string
      maxLength: 1000
      description: What this consumer does and why it uses Epistola
      example: Generates PDF invoices for the billing pipeline
    contact:
      type: string
      maxLength: 255
      description: Team or individual responsible for this consumer
      example: billing-team@acme-corp.com
    tags:
      type: array
      maxItems: 20
      items:
        type: string
        maxLength: 50
      description: Freeform tags for categorization
      example: ["billing", "production"]

UpdateConsumerRequest:
  type: object
  properties:
    name:
      type: string
      minLength: 1
      maxLength: 255
      description: New name for the consumer
      example: Invoice Service v2
    description:
      type: [string, "null"]
      maxLength: 1000
      description: Updated description (null to clear)
    contact:
      type: [string, "null"]
      maxLength: 255
      description: Updated contact (null to clear)
    status:
      type: string
      enum: [active, inactive]
      description: Updated status
    tags:
      type: array
      maxItems: 20
      items:
        type: string
        maxLength: 50
      description: Replacement tags (replaces entire array)

ConsumerListResponse:
  type: object
  required:
    - items
    - page
    - size
    - totalElements
    - totalPages
  properties:
    items:
      type: array
      items:
        $ref: '#/ConsumerDto'
    page:
      type: integer
      description: Current page number (0-based)
      example: 0
    size:
      type: integer
      description: Number of items per page
      example: 20
    totalElements:
      type: integer
      format: int64
      description: Total number of consumers
      example: 1
    totalPages:
      type: integer
      description: Total number of pages
      example: 1
```

### 5.3 Dependency Management (Tenant-Scoped)

Dependencies are managed under the tenant scope because the templates they reference are tenant-scoped. The consumer ID in the path identifies which consumer is declaring the dependency.

#### Declare or update a dependency

Uses `PUT` for idempotent upsert — the consumer either creates or replaces its dependency declaration for a given template.

```
PUT /tenants/{tenantId}/consumers/{consumerId}/dependencies/{templateId}
Content-Type: application/vnd.epistola.v1+json

{
  "variantIds": ["english", "dutch"],
  "environmentIds": ["production"],
  "purpose": "Generates monthly PDF invoices for B2B customers"
}

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "consumerId": "invoice-service",
  "templateId": "invoice",
  "variantIds": ["english", "dutch"],
  "environmentIds": ["production"],
  "purpose": "Generates monthly PDF invoices for B2B customers",
  "createdAt": "2026-02-17T10:30:00Z",
  "updatedAt": null
}
```

Request schema:

```yaml
DeclareTemplateDependencyRequest:
  type: object
  properties:
    variantIds:
      type: array
      items:
        type: string
      description: Specific variants the consumer uses (empty = all)
      example: ["english", "dutch"]
    environmentIds:
      type: array
      items:
        type: string
      description: Environments where this dependency is active (empty = all)
      example: ["production"]
    purpose:
      type: string
      maxLength: 500
      description: Why this consumer depends on this template
      example: Generates monthly PDF invoices for B2B customers
```

#### List dependencies for a consumer in a tenant

```
GET /tenants/{tenantId}/consumers/{consumerId}/dependencies
Accept: application/vnd.epistola.v1+json

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "items": [
    {
      "consumerId": "invoice-service",
      "templateId": "invoice",
      "variantIds": ["english", "dutch"],
      "environmentIds": ["production"],
      "purpose": "Generates monthly PDF invoices for B2B customers",
      "createdAt": "2026-02-17T10:30:00Z",
      "updatedAt": null
    },
    {
      "consumerId": "invoice-service",
      "templateId": "credit-note",
      "variantIds": [],
      "environmentIds": ["production"],
      "purpose": "Generates credit notes when invoices are reversed",
      "createdAt": "2026-02-17T10:35:00Z",
      "updatedAt": null
    }
  ]
}
```

The dependency list is not paginated — the number of templates a single consumer depends on within a single tenant is expected to be small (tens, not thousands).

Response schema:

```yaml
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
DELETE /tenants/{tenantId}/consumers/{consumerId}/dependencies/{templateId}

204 No Content
```

### 5.4 Impact Query (Tenant-Scoped)

Allows querying which consumers depend on a given template. This is the read side of dependency tracking — useful for impact analysis before making changes.

#### List dependents for a template

```
GET /tenants/{tenantId}/templates/{templateId}/dependents?status=active
Accept: application/vnd.epistola.v1+json

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "items": [
    {
      "consumerId": "invoice-service",
      "templateId": "invoice",
      "variantIds": ["english", "dutch"],
      "environmentIds": ["production"],
      "purpose": "Generates monthly PDF invoices for B2B customers",
      "createdAt": "2026-02-17T10:30:00Z",
      "updatedAt": null
    },
    {
      "consumerId": "report-generator",
      "templateId": "invoice",
      "variantIds": [],
      "environmentIds": [],
      "purpose": "Generates monthly summary reports including invoice copies",
      "createdAt": "2026-02-18T09:00:00Z",
      "updatedAt": null
    }
  ]
}
```

Query parameters:

| Parameter | Type | Description |
|---|---|---|
| `status` | string | Filter by consumer status: `active` (default), `inactive`, or `all` |

Response schema:

```yaml
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

### 5.5 Impact Warnings on Delete

When a delete operation targets a resource that has dependent consumers, the API returns **409 Conflict** instead of proceeding with the delete. The response body lists the affected consumers so the operator can assess impact and notify downstream teams.

#### Template delete with dependents

```
DELETE /tenants/{tenantId}/templates/{templateId}

409 Conflict
Content-Type: application/vnd.epistola.v1+json

{
  "code": "HAS_DEPENDENTS",
  "message": "Cannot delete template 'invoice': 2 active consumers depend on it",
  "dependents": [
    {
      "consumerId": "invoice-service",
      "name": "Invoice Service",
      "contact": "billing-team@acme-corp.com"
    },
    {
      "consumerId": "report-generator",
      "name": "Report Generator",
      "contact": "analytics-team@acme-corp.com"
    }
  ]
}
```

The `dependents` array includes consumer name and contact so operators can immediately reach out to affected teams.

#### Force delete

To proceed despite dependents, add `?force=true`:

```
DELETE /tenants/{tenantId}/templates/{templateId}?force=true

204 No Content
```

Force delete removes the template and all associated dependency records. This is an explicit opt-in to acknowledge the blast radius.

#### Same pattern for variant and theme deletes

The 409 Conflict pattern applies to:

| Operation | Conflict condition |
|---|---|
| `DELETE /tenants/{tenantId}/templates/{templateId}` | Any consumer has a dependency on this template |
| `DELETE /tenants/{tenantId}/templates/{templateId}/variants/{variantId}` | Any consumer's dependency references this specific variant |
| `DELETE /tenants/{tenantId}/themes/{themeId}` | Any consumer's dependency references a template using this theme |

All support `?force=true` to override.

Response schema for the conflict response. This extends the base `ErrorResponse` pattern (same `code` + `message` fields) with an additional `dependents` array, following the precedent set by `ValidationErrorResponse`:

```yaml
DependentConflictResponse:
  type: object
  required:
    - code
    - message
    - dependents
  properties:
    code:
      type: string
      description: Machine-readable error code
      example: HAS_DEPENDENTS
    message:
      type: string
      description: Human-readable description of the conflict
      example: "Cannot delete template 'invoice': 2 active consumers depend on it"
    dependents:
      type: array
      items:
        type: object
        required:
          - consumerId
          - name
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
```

### 5.6 Endpoint Summary

| Method | Path | Operation | Tag | Role |
|---|---|---|---|---|
| `POST` | `/consumers` | `createConsumer` | Consumers | `tenant_control` |
| `GET` | `/consumers` | `listConsumers` | Consumers | `tenant_control` |
| `GET` | `/consumers/{consumerId}` | `getConsumer` | Consumers | `tenant_control` |
| `PATCH` | `/consumers/{consumerId}` | `updateConsumer` | Consumers | `tenant_control` |
| `DELETE` | `/consumers/{consumerId}` | `deleteConsumer` | Consumers | `tenant_control` |
| `PUT` | `/tenants/{tenantId}/consumers/{consumerId}/dependencies/{templateId}` | `declareTemplateDependency` | Consumer Dependencies | `reader, editor, generator, manager` |
| `GET` | `/tenants/{tenantId}/consumers/{consumerId}/dependencies` | `listConsumerDependencies` | Consumer Dependencies | `reader, editor, generator, manager` |
| `DELETE` | `/tenants/{tenantId}/consumers/{consumerId}/dependencies/{templateId}` | `deleteTemplateDependency` | Consumer Dependencies | `reader, editor, generator, manager` |
| `GET` | `/tenants/{tenantId}/templates/{templateId}/dependents` | `listTemplateDependents` | Consumer Dependencies | `reader, editor, generator, manager` |

### 5.7 Path Design

**Consumer CRUD** is placed at `/consumers` (no tenant prefix) because consumers are platform-level resources. This is consistent with the pattern that platform-level resources (tenants at `/tenants`) do not have a tenant prefix.

**Dependency management** is placed under `/tenants/{tenantId}/consumers/{consumerId}/dependencies/...` because dependencies reference tenant-scoped resources. The consumer ID in the path identifies the consumer; the tenant ID scopes the dependency to that tenant's templates.

**Impact query** is placed under `/tenants/{tenantId}/templates/{templateId}/dependents` because the question is "who depends on this template?" — a template-centric view.

---

## 6. Integration Points

### 6.1 Event System

The event system design (`docs/event_system.md`, Section 2.2) defines an `actor` field on the event envelope:

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

This enriches every event: instead of just "user jane@acme-corp.com performed this action", the event now records "user jane@acme-corp.com via invoice-service performed this action."

Consumer registration also generates its own events:

| Event Type | Trigger |
|---|---|
| `consumer.registered` | `POST /consumers` |
| `consumer.updated` | `PATCH /consumers/{consumerId}` |
| `consumer.deleted` | `DELETE /consumers/{consumerId}` |
| `dependency.declared` | `PUT .../dependencies/{templateId}` |
| `dependency.removed` | `DELETE .../dependencies/{templateId}` |

Note: Consumer events are **not** tenant-scoped (consumers are platform-level). This requires extending the event model to support events without a `tenantId`, or using a reserved tenant ID (e.g., `_platform`). This decision is deferred to the event system implementation.

Dependency events **are** tenant-scoped and fit the existing event model without modification.

### 6.2 Document Generation

The `DocumentDto.createdBy` field (currently `type: [string, "null"]`, always `null`) gets populated with the consumer ID when the server can identify the calling consumer.

```json
{
  "id": "770e8400-e29b-41d4-a716-446655440002",
  "templateId": "invoice",
  "variantId": "english",
  "versionId": 3,
  "filename": "invoice-2026-001.pdf",
  "correlationId": "order-7890",
  "contentType": "application/pdf",
  "sizeBytes": 184320,
  "createdAt": "2026-02-01T08:00:03Z",
  "createdBy": "invoice-service"
}
```

This is fully backwards compatible — the field already exists and is nullable. Consumers that don't check `createdBy` are unaffected.

**Note**: The current field description in `generation.yaml` reads "User who generated the document (future feature)". This must be updated when implementing Phase 1 to reflect that `createdBy` contains a **consumer system identifier** (e.g., `invoice-service`), not a human user identifier. The field semantics change from "which person" to "which system" — a deliberate choice since document generation is typically triggered by automated systems, not individual users.

### 6.3 Authentication

The consumer identity can be resolved server-side in several ways:

1. **JWT `client_id` claim** — The OAuth2 client credentials `client_id` is mapped to a consumer ID in server configuration.
2. **API key metadata** — Each API key is associated with a consumer ID in the server's key store.
3. **`X-Consumer-Id` header** — An optional header that allows explicit consumer identification, useful for shared credentials or development environments.

The API contract does not prescribe which method is used. The server documentation should describe the supported identification mechanisms.

---

## 7. Recommendation

### Phased Implementation

Consumer registration delivers value incrementally. Each phase is independently useful and does not require subsequent phases.

#### Phase 1: Consumer Registry + Attribution

**Scope**: Consumer CRUD (`/consumers`) and `createdBy` population.

**Value**: Provides a central registry of which systems use Epistola. Documents can be traced back to the generating system. This is the minimum viable feature.

**API changes**:
- Add `ConsumerDto`, `CreateConsumerRequest`, `UpdateConsumerRequest`, `ConsumerListResponse` schemas
- Add consumer CRUD endpoints at `/consumers`
- Add `Consumers` tag
- Server populates `DocumentDto.createdBy` (no schema change needed — field exists)

**Effort**: Small — standard CRUD, follows existing patterns exactly.

#### Phase 2: Dependencies + Impact Analysis

**Scope**: Dependency declaration, impact queries, and 409 Conflict on delete.

**Value**: This is the high-value feature. Operators can now see which systems are affected before making changes. Templates cannot be accidentally deleted while consumers depend on them.

**API changes**:
- Add `TemplateDependencyDto`, `DeclareTemplateDependencyRequest`, `TemplateDependencyListResponse`, `TemplateDependentListResponse`, `DependentConflictResponse` schemas
- Add dependency management endpoints under `/tenants/{tenantId}/consumers/{consumerId}/dependencies/`
- Add impact query endpoint under `/tenants/{tenantId}/templates/{templateId}/dependents`
- Modify delete endpoints to return 409 when dependents exist
- Add `?force=true` query parameter to delete endpoints
- Add `Consumer Dependencies` tag

**Effort**: Medium — dependency model is simple, but the 409 Conflict integration touches existing delete endpoints.

#### Phase 3: Event System Integration

**Scope**: Enrich event `actor` with `consumerId`, add consumer/dependency event types.

**Value**: Full audit trail with consumer attribution. Every event records not just who performed the action, but which system they used.

**API changes**:
- Extend `actor` schema in event envelope with `consumerId`
- Add consumer and dependency event types
- Address platform-level events (consumers are not tenant-scoped)

**Depends on**: Event system implementation (see `docs/event_system.md`).

**Effort**: Small — schema additions to the event model.

---

## Appendix A: Platform-Level Resources

Consumer registration introduces the first platform-level resource besides tenants. This establishes a pattern for future platform-level resources that may be needed (e.g., global settings, platform-wide themes).

| Resource | Scope | Path Prefix | Management Role |
|---|---|---|---|
| Tenants | Platform | `/tenants` | `tenant_control` |
| Consumers | Platform | `/consumers` | `tenant_control` |
| Templates | Tenant | `/tenants/{tenantId}/templates` | `editor`, `manager` |
| Themes | Tenant | `/tenants/{tenantId}/themes` | `editor`, `manager` |
| Environments | Tenant | `/tenants/{tenantId}/environments` | `editor`, `manager` |

## Appendix B: Hybrid Scoping Coherence

The hybrid model (platform-level consumers, tenant-scoped dependencies) creates a cross-reference between scoping levels. This appendix addresses potential consistency concerns.

**Scenario: Consumer is deleted while dependencies exist**

When a consumer is deleted (`DELETE /consumers/{consumerId}`), all dependencies across all tenants are cascade-deleted. This is the only operation that crosses the scope boundary, and it flows top-down (platform → tenant), which is safe.

**Scenario: Tenant is deleted while dependencies reference it**

When a tenant is deleted (`DELETE /tenants/{tenantId}`), all dependencies within that tenant are deleted as part of the tenant cleanup. The consumer record itself is unaffected (it may have dependencies in other tenants).

**Scenario: Consumer exists but has no dependencies in a tenant**

This is valid. A consumer might be registered at the platform level but only have dependencies declared in some tenants. The dependency endpoints return empty lists for tenants where no dependencies have been declared.

**Scenario: Dependency references a non-existent consumer**

The `PUT` dependency endpoint validates that the consumer ID exists. If the consumer does not exist, the server returns 404. Dependencies cannot be created for unregistered consumers.

## Appendix C: Comparison with Auto-Detection

An alternative to explicit dependency declaration is automatic detection: the server tracks which consumer calls which template (via generation requests) and builds the dependency graph automatically.

| Aspect | Explicit Declaration | Auto-Detection |
|---|---|---|
| **Accuracy** | Declared by the consumer — may be incomplete or stale | Based on actual usage — always up to date |
| **Intent** | Captures purpose and scope (variants, environments) | Only captures observed usage patterns |
| **Forward-looking** | Can declare dependencies before first use | Only reflects past behavior |
| **Server complexity** | Low — simple CRUD | High — requires tracking every API call per consumer |
| **Privacy** | Consumer controls what it declares | Server tracks all activity |
| **Contract expressibility** | Full — standard REST endpoints | Minimal — auto-detection is server-internal |

The explicit model is chosen because it fits the contract-first approach (it's an API feature, not an observability feature), captures richer information (purpose, intended variants/environments), and has minimal server complexity. A server implementation could supplement explicit declarations with auto-detected usage data as a future enhancement.
