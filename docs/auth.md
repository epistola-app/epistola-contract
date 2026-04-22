# Authentication Guide

The Epistola API uses JWT Bearer tokens for authentication. Two paths are supported depending on your environment.

## Authentication Methods

### Method 1: OAuth 2.0 (Recommended)

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
4. Administrator approves the consumer (`POST /consumers/{id}/approve`), setting allowed tenants, roles, and optional expiry
5. Application can now access resources within its allowed tenants

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
curl -X POST https://api.example.com/api/consumers/register \
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
curl -X PUT https://api.example.com/api/consumers/invoice-service/public-key \
  -H "Authorization: Bearer <jwt-signed-with-current-key>" \
  -H "Content-Type: application/vnd.epistola.v1+json" \
  -d '{ "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBI..." }'
```

After rotation, the old key is immediately invalidated.

---

## Authorization

### How Permissions Work

**Permissions are managed in Epistola, not in JWT claims.** When an administrator approves a consumer, they set:

- **Allowed tenants**: Which tenants the consumer can access (or `["*"]` for all)
- **Roles**: What operations the consumer can perform
- **Expiry**: When the approval expires (optional)

This applies to both OAuth and self-signed JWT consumers. JWT claims like `roles` or `allowed_tenants` are not used for authorization decisions.

### Roles

The API uses five independent roles that can be combined as needed:

| Role | Scope | Description |
|------|-------|-------------|
| `reader` | Tenant | Read-only access to resources within allowed tenants |
| `editor` | Tenant | Create and update resources within allowed tenants |
| `generator` | Tenant | Submit document generation jobs |
| `manager` | Tenant | Delete resources, cancel jobs within allowed tenants |
| `tenant_control` | Platform | Manage tenants and consumers |

Roles are **not hierarchical** — they are independent capabilities.

### Permission Matrix

| Operation | reader | editor | generator | manager | tenant_control |
|-----------|--------|--------|-----------|---------|----------------|
| **Templates, Variants, Versions, Themes, Environments** |
| List / Get | X | X | X | X | |
| Create / Update | | X | | X | |
| Delete | | | | X | |
| **Document Generation** |
| Submit generation job | | | X | X | |
| View jobs and results | X | X | X | X | |
| Cancel job / delete document | | | | X | |
| **Tenants** |
| List all tenants | | | | | X |
| Get tenant (within allowed_tenants) | X | X | X | X | X |
| Create / Update / Delete | | | | | X |
| **Consumers** |
| Register (self-signed JWT) | — | — | — | — | — |
| List / Get / Approve / Reject | | | | | X |
| Rotate own public key | *self* | *self* | *self* | *self* | |
| **Trackers** |
| Create / Poll | | | X | X | |
| List / Get | X | X | X | X | |
| Delete | | | | X | |

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
  "code": "UNAUTHORIZED",
  "message": "Invalid or expired access token"
}
```

Common causes:
- Missing `Authorization` header
- Expired JWT token
- Invalid token signature
- Unknown issuer (consumer not registered)

### 403 Forbidden

Returned when authenticated but lacking permission:

```json
{
  "code": "FORBIDDEN",
  "message": "Access denied to tenant 'acme-corp'"
}
```

Common causes:
- Consumer status is not `active` (pending, rejected, expired, inactive)
- Tenant not in consumer's `allowedTenants`
- Consumer's role lacks permission for the operation

---

## Best Practices

1. **Use OAuth for production** — Short-lived tokens from a managed IdP
2. **Use self-signed JWT for simple deployments** — No IdP dependency, but manage key rotation
3. **Set expiry on consumer approvals** — Forces periodic review of access
4. **Rotate keys regularly** — For self-signed JWT consumers, rotate at least every 90 days
5. **Keep JWTs short-lived** — 60 seconds is recommended for self-signed JWTs
6. **Use unique `jti` values** — Prevents replay attacks on self-signed JWTs
