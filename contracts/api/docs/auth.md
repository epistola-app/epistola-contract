# Authentication Guide

The Epistola API accepts authentication through the `Authorization` header. **API keys are the supported method.** The two Bearer JWT methods, OAuth and self-signed JWT, are **experimental**: Epistola Suite may not implement the consumer model they rely on yet, and the flow may still change. The spec marks them, and the Consumers API they use, with `x-experimental: true`.

## Authentication Methods

### Method 1: API Key

Tenant API keys are static credentials issued by a tenant manager. Each key belongs to one tenant and carries the tenant roles it was issued with. Send the key through the `Authorization` header:

```bash
curl https://api.example.com/api/tenants/acme-corp/catalogs \
  -H "Authorization: ApiKey epk_..." \
  -H "Accept: application/vnd.epistola.v2+json"
```

`Authorization` is the only header that carries an API key; the contract defines no other.

The `ApiKey` authorization scheme name is case-insensitive as defined by HTTP authentication rules; examples use `ApiKey` for readability.

A consumer cannot rotate its own API key. A tenant manager issues a new key and revokes the old one.

### Method 2: OAuth 2.0 Bearer JWT (Experimental)

> **Experimental.** Epistola Suite may not implement this flow yet, and it may still change.

Use the OAuth 2.0 Client Credentials flow to obtain a JWT from your Identity Provider, with Epistola acting as the resource server.

#### Supported Identity Providers

- Keycloak
- Azure AD (Entra ID)
- Any OAuth 2.0 / OpenID Connect compliant provider

#### Token Request

```bash
curl -X POST https://your-idp.example.com/realms/epistola/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=your-client-id" \
  -d "client_secret=your-client-secret"
```

#### API Request

```bash
curl https://api.example.com/api/tenants/acme-corp/catalogs \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Accept: application/vnd.epistola.v2+json"
```

#### How it works

1. Admin creates an OAuth client in the IdP (client_id + client_secret)
2. Application obtains a JWT token from the IdP
3. First request to Epistola auto-registers the consumer as `pending`
4. Tenant manager approves the consumer (`POST /tenants/{tenantKey}/consumers/{id}/approve`), setting permissions and optional expiry
5. Application can now access resources within that tenant

### Method 3: Self-Signed JWT (Experimental)

> **Experimental.** Epistola Suite may not implement this flow yet, and it may still change.

The key-pair counterpart of an API key: the application proves its identity with a private key it never shares, instead of a shared secret. It generates its own short-lived JWT tokens, signed with that private key.

#### Setup

1. Generate a key pair (RSA 2048+ or Ed25519):

```bash
# Ed25519 (recommended)
openssl genpkey -algorithm Ed25519 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem

# RSA 2048
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem
```

2. Register with Epistola:

```bash
curl -X POST https://api.example.com/api/tenants/acme-corp/consumers/register \
  -H "Content-Type: application/vnd.epistola.v2+json" \
  -d '{
    "id": "invoice-service",
    "name": "Invoice Service",
    "contact": "billing-team@acme-corp.com",
    "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBI..."
  }'
```

3. Wait for administrator approval (consumer starts in `pending` status)

4. Create short-lived JWTs for each request:

```json
{
  "iss": "invoice-service",
  "iat": 1745312400,
  "exp": 1745312460,
  "jti": "550e8400-e29b-41d4-a716-446655440000"
}
```

- `iss`: Your consumer ID (must match the registered ID)
- `iat`: Issued at (current timestamp)
- `exp`: Expiry (recommended: 60 seconds from `iat`)
- `jti`: Unique token ID (UUID) for replay protection

5. Sign and send:

```bash
curl https://api.example.com/api/tenants/acme-corp/catalogs \
  -H "Authorization: Bearer <self-signed-jwt>" \
  -H "Accept: application/vnd.epistola.v2+json"
```

#### Key Rotation

Rotate your public key while authenticated with the current key:

```bash
curl -X PUT https://api.example.com/api/tenants/acme-corp/consumers/invoice-service/public-key \
  -H "Authorization: Bearer <jwt-signed-with-current-key>" \
  -H "Content-Type: application/vnd.epistola.v2+json" \
  -d '{ "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBI..." }'
```

After rotation, the old key is immediately invalidated.

---

## Authorization

### How Permissions Work

**API keys** carry their permissions themselves: the tenant manager who issues a key chooses its tenant and its tenant roles, and optionally an expiry.

**JWT consumers** (experimental) get their permissions from Epistola, not from JWT claims. When an administrator approves a consumer, they set:

- **Allowed tenants**: Which tenants the consumer can access (or `["*"]` for all)
- **Permissions**: What operations the consumer can perform
- **Expiry**: When the approval expires (optional)

This applies to both OAuth and self-signed JWT consumers. JWT claims like `roles` or `allowed_tenants` are not used for authorization decisions.

### Roles and Permissions

Epistola Suite uses coarse tenant/platform roles to derive fine-grained permissions. The OpenAPI
contract documents the enforced permission on each operation with `x-required-permissions`; platform
operations use `x-required-platform-roles`.

Tenant roles are composable and non-hierarchical:

| Suite role | Grants |
|------------|--------|
| `CONTENT_VIEWER` | `TEMPLATE_VIEW`, `DOCUMENT_VIEW`, `THEME_VIEW`, `STENCIL_VIEW`, `REFERENCE_VIEW`, `CATALOG_VIEW`, `BACKUP_VIEW` |
| `CONTENT_AUTHOR` | `TEMPLATE_EDIT`, `THEME_EDIT`, `STENCIL_EDIT`, `REFERENCE_EDIT` |
| `DOCUMENT_GENERATOR` | `DOCUMENT_GENERATE` |
| `CONTENT_PUBLISHER` | `CATALOG_RELEASE` (release and discard catalog drafts), `ENVIRONMENT_DEPLOY` (deploy and undeploy releases of catalogs, released ones and drafts, in environments), `CATALOG_PUBLISH` (publish released releases to a release registry, alpha: the release leaves the installation) |
| `TENANT_ADMINISTRATOR` | `TENANT_SETTINGS`, `TENANT_USERS`, `CATALOG_MANAGE`, `BACKUP_CREATE`, `DIAGNOSTICS_VIEW`, `AUDIT_VIEW`, `TENANT_RESTORE` |

`BACKUP_VIEW`, `BACKUP_CREATE`, `DIAGNOSTICS_VIEW`, `AUDIT_VIEW` and `TENANT_RESTORE` grant Suite
features outside this API: no operation in the contract requires them. Every other permission
above is the `x-required-permissions` of at least one operation, and every permission an operation
requires is in this table.

Platform roles:

| Suite platform role | Grants |
|---------------------|--------|
| `TENANT_MANAGER` | Create and delete tenants across the platform |
| `PLATFORM_OBSERVER` | Cross-tenant read-only diagnostics/status observation |

Important: `TENANT_ADMINISTRATOR` does not imply releasing, deploying or editing content. Grant
`CONTENT_AUTHOR` and/or `CONTENT_PUBLISHER` alongside administration when an administrator also
authors content, or releases and deploys it.

Legacy role names such as `reader`, `editor`, `generator`, `manager`, and `tenant_control` are not
part of the current Suite authorization vocabulary and should not be emitted by IdPs or API-key
provisioning flows.

---

## Consumer Lifecycle (Experimental)

> **Experimental.** The Consumers API that manages this lifecycle may not be implemented by Epistola Suite yet.

```
Self-signed JWT:  POST /tenants/{tenantKey}/consumers/register → pending
OAuth:            First authenticated request → pending
API key:          Issued by a tenant manager → active

pending → approve → active → (expiresAt passes) → expired
pending → reject  → rejected
active  → deactivate (via PATCH) → inactive
inactive → reactivate (via PATCH) → active
```

Consumers in any status other than `active` cannot access API resources (except the registration endpoint).

---

## Error Responses

### 401 Unauthorized

Returned when authentication fails:

```json
{
  "type": "https://epistola.app/errors/unauthorized",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid or expired access token"
}
```

Common causes:
- Missing `Authorization` header
- Missing, malformed, disabled, revoked, or expired API key
- API-key authentication is disabled for the deployment. In that case the problem `type` is `https://epistola.app/errors/api-key-auth-disabled`; clients should switch to `Authorization: Bearer <jwt>` or show a deployment-policy message.
- Expired JWT token
- Invalid token signature
- Unknown issuer (consumer not registered)

### 403 Forbidden

Returned when authenticated but lacking permission:

```json
{
  "type": "https://epistola.app/errors/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "Access denied to tenant 'acme-corp'"
}
```

Common causes:
- The API key belongs to a different tenant, or lacks the role the operation requires
- Consumer status is not `active` (pending, rejected, expired, inactive)
- Tenant not in consumer's `allowedTenants`
- Consumer lacks the permission required for the operation

---

## Best Practices

1. **Use an API key for production** — the supported method; the JWT methods are experimental
2. **Send it as `Authorization: ApiKey <key>`**, the one header the contract defines for it
3. **Issue each key with the fewest roles it needs** — a service that generates and downloads documents needs `DOCUMENT_GENERATOR` and `CONTENT_VIEWER`, not `TENANT_ADMINISTRATOR`
4. **Set an expiry on keys** — and rotate by issuing a new key before revoking the old one

When trying the experimental JWT methods:

5. **Set expiry on consumer approvals** — Forces periodic review of access
6. **Rotate keys regularly** — For self-signed JWT consumers, rotate at least every 90 days
7. **Keep JWTs short-lived** — 60 seconds is recommended for self-signed JWTs
8. **Use unique `jti` values** — Prevents replay attacks on self-signed JWTs
