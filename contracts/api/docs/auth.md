# Authentication Guide

The Epistola API accepts authentication through the `Authorization` header. JWT Bearer tokens are recommended for new integrations, and static API keys remain supported.

## Authentication Methods

### Method 1: OAuth 2.0 Bearer JWT (Recommended)

Use the OAuth 2.0 Client Credentials flow to obtain a JWT from your Identity Provider.

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
curl https://api.example.com/api/tenants/acme-corp/templates \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Accept: application/vnd.epistola.v1+json"
```

#### How it works

1. Admin creates an OAuth client in the IdP (client_id + client_secret)
2. Application obtains a JWT token from the IdP
3. First request to Epistola auto-registers the consumer as `pending`
4. Tenant manager approves the consumer (`POST /tenants/{tenantId}/consumers/{id}/approve`), setting permissions and optional expiry
5. Application can now access resources within that tenant

### Method 2: Self-Signed JWT

For environments without an Identity Provider. The application generates its own short-lived JWT tokens, signed with a private key.

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
  -H "Content-Type: application/vnd.epistola.v1+json" \
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
curl https://api.example.com/api/tenants/acme-corp/templates \
  -H "Authorization: Bearer <self-signed-jwt>" \
  -H "Accept: application/vnd.epistola.v1+json"
```

#### Key Rotation

Rotate your public key while authenticated with the current key:

```bash
curl -X PUT https://api.example.com/api/tenants/acme-corp/consumers/invoice-service/public-key \
  -H "Authorization: Bearer <jwt-signed-with-current-key>" \
  -H "Content-Type: application/vnd.epistola.v1+json" \
  -d '{ "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBI..." }'
```

After rotation, the old key is immediately invalidated.

### Method 3: API Key

Tenant API keys are static credentials issued by an administrator. New API-key integrations should send the key through the `Authorization` header:

```bash
curl https://api.example.com/api/tenants/acme-corp/templates \
  -H "Authorization: ApiKey epk_..." \
  -H "Accept: application/vnd.epistola.v1+json"
```

The legacy `X-API-Key` header remains supported for existing integrations, but is deprecated:

```bash
curl https://api.example.com/api/tenants/acme-corp/templates \
  -H "X-API-Key: epk_..." \
  -H "Accept: application/vnd.epistola.v1+json"
```

The `ApiKey` authorization scheme name is case-insensitive as defined by HTTP authentication rules; examples use `ApiKey` for readability.

---

## Authorization

### How Permissions Work

**Permissions are managed in Epistola, not in JWT claims.** When an administrator approves a consumer, they set:

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
| `CONTENT_PUBLISHER` | `TEMPLATE_PUBLISH`, `STENCIL_PUBLISH` |
| `TENANT_ADMINISTRATOR` | `TENANT_SETTINGS`, `TENANT_USERS`, `CATALOG_MANAGE`, `BACKUP_CREATE`, `DIAGNOSTICS_VIEW`, `AUDIT_VIEW`, `TENANT_RESTORE` |

Platform roles:

| Suite platform role | Grants |
|---------------------|--------|
| `TENANT_MANAGER` | Create and delete tenants across the platform |
| `PLATFORM_OBSERVER` | Cross-tenant read-only diagnostics/status observation |

Important: `TENANT_ADMINISTRATOR` does not imply content publish or content edit. Grant
`CONTENT_AUTHOR` and/or `CONTENT_PUBLISHER` alongside administration when an administrator also
authors or approves content.

Legacy role names such as `reader`, `editor`, `generator`, `manager`, and `tenant_control` are not
part of the current Suite authorization vocabulary and should not be emitted by IdPs or API-key
provisioning flows.

---

## Consumer Lifecycle

```
Self-signed JWT:  POST /consumers/register → PENDING
OAuth:            First authenticated request → PENDING

PENDING → approve → ACTIVE → (expiresAt passes) → EXPIRED
PENDING → reject  → REJECTED
ACTIVE  → deactivate (via PATCH) → INACTIVE
INACTIVE → reactivate (via PATCH) → ACTIVE
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
- Expired JWT token
- Invalid token signature
- Unknown issuer (consumer not registered)
- Missing, malformed, disabled, revoked, or expired API key
- API-key authentication is disabled for the deployment. In that case the problem `type` is `https://epistola.app/errors/api-key-auth-disabled`; clients should switch to `Authorization: Bearer <jwt>` or show a deployment-policy message.

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
- Consumer status is not `active` (pending, rejected, expired, inactive)
- Tenant not in consumer's `allowedTenants`
- Consumer lacks the permission required for the operation

---

## Best Practices

1. **Use OAuth for production** — Short-lived tokens from a managed IdP
2. **Use self-signed JWT for simple deployments** — No IdP dependency, but manage key rotation
3. **Use `Authorization: ApiKey <key>` for static keys** — `X-API-Key` remains supported but is deprecated
4. **Set expiry on consumer approvals** — Forces periodic review of access
5. **Rotate keys regularly** — For self-signed JWT consumers, rotate at least every 90 days
6. **Keep JWTs short-lived** — 60 seconds is recommended for self-signed JWTs
7. **Use unique `jti` values** — Prevents replay attacks on self-signed JWTs
