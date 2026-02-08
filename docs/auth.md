# Authentication Guide

The Epistola API supports dual authentication methods, allowing flexibility for different deployment environments.

## Authentication Methods

### Method 1: OAuth 2.0 JWT (Recommended)

Use the OAuth 2.0 Client Credentials flow to obtain a short-lived access token from your Identity Provider (IdP).

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

Response:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 300
}
```

#### API Request with JWT

```bash
curl https://api.example.com/api/tenants/acme-corp/templates \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Accept: application/vnd.epistola.v1+json"
```

#### Required JWT Claims

Your IdP must include these claims in the access token:

| Claim | Type | Description |
|-------|------|-------------|
| `roles` | `string[]` | Array of role names: `reader`, `editor`, `generator`, `admin` |
| `allowed_tenants` | `string[]` | Array of tenant slugs the client can access, or `["*"]` for all |

Example token payload:
```json
{
  "iss": "https://keycloak.example.com/realms/epistola",
  "sub": "billing-system",
  "aud": "epistola-api",
  "exp": 1735689600,
  "iat": 1735686000,
  "client_id": "billing-system",
  "roles": ["generator"],
  "allowed_tenants": ["acme-corp", "globex"]
}
```

### Method 2: API Keys

For environments without an Identity Provider, use static API keys.

#### API Request with API Key

```bash
curl https://api.example.com/api/tenants/acme-corp/templates \
  -H "X-API-Key: your-api-key-here" \
  -H "Accept: application/vnd.epistola.v1+json"
```

#### Obtaining API Keys

API keys are issued by system administrators. Each key is associated with:
- A client identifier
- One or more roles
- A list of allowed tenants

Contact your administrator to request an API key for your system.

#### Security Considerations

- API keys do not expire automatically - rotate them regularly
- Store keys securely (environment variables, secrets manager)
- Never commit keys to source control
- Use JWT authentication for production environments when possible

---

## Authorization

### Role-Based Permissions

The API uses five independent roles that can be combined as needed:

| Role | Scope | Description |
|------|-------|-------------|
| `reader` | Tenant | Read-only access to resources within allowed tenants |
| `editor` | Tenant | Create and update resources within allowed tenants |
| `generator` | Tenant | Submit document generation jobs |
| `manager` | Tenant | Delete resources, cancel jobs within allowed tenants |
| `tenant_control` | Platform | Manage tenants (list all, create, update, delete) |

Roles are **not hierarchical** - they are independent capabilities. Combine them based on your needs:
- `["reader", "generator"]` - Read and generate documents only
- `["tenant_control"]` - Provision tenants, no access to internal resources
- `["reader", "editor", "manager"]` - Full tenant operations, cannot create new tenants

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

### Tenant Access Control

Access to tenant-scoped resources is controlled via the `allowed_tenants` claim:

```json
{
  "allowed_tenants": ["acme-corp", "globex"]
}
```

- Clients can only access resources within their allowed tenants
- Attempting to access an unauthorized tenant returns `403 Forbidden`
- Use `["*"]` to grant access to all tenants (admin/super-user scenarios)

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
- Missing `Authorization` header or `X-API-Key` header
- Expired JWT token
- Invalid token signature
- Invalid or revoked API key

### 403 Forbidden

Returned when authenticated but lacking permission:

```json
{
  "code": "FORBIDDEN",
  "message": "Access denied to tenant 'acme-corp'"
}
```

Common causes:
- Tenant not in `allowed_tenants` claim
- Role lacks permission for the operation
- Attempting to access admin-only endpoints without admin role

---

## Configuration Examples

### Keycloak Client Configuration

1. Create a new client in Keycloak:
   - Client ID: `your-system-name`
   - Client Protocol: `openid-connect`
   - Access Type: `confidential`
   - Service Accounts Enabled: `ON`

2. Add client roles or realm roles for Epistola:
   - `reader`, `editor`, `generator`, or `admin`

3. Create a mapper for `allowed_tenants`:
   - Mapper Type: `Hardcoded claim`
   - Claim name: `allowed_tenants`
   - Claim value: `["tenant-slug-1", "tenant-slug-2"]`

4. Create a mapper for `roles`:
   - Mapper Type: `User Realm Role` or `User Client Role`
   - Token Claim Name: `roles`

### Azure AD (Entra ID) Configuration

1. Register an application in Azure AD
2. Create a client secret
3. Define app roles in the manifest:
   ```json
   {
     "appRoles": [
       {
         "allowedMemberTypes": ["Application"],
         "displayName": "Generator",
         "id": "unique-guid",
         "value": "generator"
       }
     ]
   }
   ```
4. Configure optional claims for `roles` and custom claims for `allowed_tenants`

---

## Best Practices

1. **Use JWT for production** - Short-lived tokens limit exposure if compromised
2. **Implement token refresh** - Handle token expiration gracefully in your client
3. **Apply least privilege** - Request only the roles your system needs
4. **Secure credential storage** - Use secrets managers, not environment files
5. **Monitor authentication failures** - Log and alert on repeated 401/403 responses
6. **Rotate credentials regularly** - Especially for API keys without expiration
