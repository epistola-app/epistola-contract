# Epistola Server Interfaces

Generated Spring Boot 4 server interfaces for the Epistola API, with additional utilities for client identity parsing and consumer identity resolution.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("app.epistola.contract:server-springboot4:0.3.0")
}
```

## Generated Interfaces

The module generates Spring `@RestController` interfaces for all API endpoint groups. Implement these interfaces in your application:

```kotlin
@RestController
class MyGenerationApi(private val mediator: Mediator) : GenerationApi {

    override fun generateDocument(
        tenantId: String,
        generateDocumentRequest: GenerateDocumentRequest,
    ): ResponseEntity<GenerationJobResponse> {
        val result = mediator.send(GenerateDocument(tenantId, generateDocumentRequest))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result)
    }

    // ... other methods
}
```

Available interfaces:
- `SystemApi` — ping/pong
- `TenantsApi` — tenant CRUD
- `ConsumersApi` — consumer registration, approval, lifecycle
- `TemplatesApi`, `VariantsApi`, `VersionsApi` — template management
- `ThemesApi`, `EnvironmentsApi`, `StencilsApi`, `CatalogsApi` — supporting resources
- `GenerationApi` — document generation + result collection
- `AttributesApi` — variant selection criteria

## Client Identity Parsing

The `ClientInfo` utility parses client identity from incoming request headers (`User-Agent` and `X-EP-Node-Id`).

```kotlin
@GetMapping("/tenants/{tenantId}/templates")
fun listTemplates(
    @PathVariable tenantId: String,
    request: HttpServletRequest,
): ResponseEntity<TemplateListResponse> {
    val client = ClientInfo.from(request)

    log.info("Request from node=${client.nodeId}, contract=${client.contractVersion}")
    log.info("Full stack: ${client.products.joinToString { "${it.name}/${it.version}" }}")

    // Look up a specific product version
    val pluginVersion = client.productVersion("valtimo-epistola-plugin")

    // ...
}

// Or use the extension function
val client = request.clientInfo()
```

### Parsed Fields

```kotlin
data class ClientInfo(
    val products: List<Product>,   // all product/version pairs from User-Agent
    val nodeId: String?,           // X-EP-Node-Id header value
) {
    val contractVersion: String?   // extracted from first "epistola-contract/X.Y.Z" token
    fun productVersion(name: String): String?  // look up specific product version
}
```

### User-Agent Format

The `User-Agent` header follows RFC 9110 product tokens:

```
epistola-contract/0.3.0 valtimo-epistola-plugin/1.2.0 gzac/5.0.0
```

The first token is always `epistola-contract/{version}` (set by the client library).

## Consumer Identity Resolution

The `ConsumerResolver` extracts consumer identity from JWT claims. Works for both OAuth and self-signed JWT consumers.

```kotlin
// In a Spring Security filter or controller
val claims: Map<String, Any?> = extractClaimsFromJwt(token)

// Resolve consumer ID (checks client_id → azp → iss in order)
val consumerId = ConsumerResolver.resolveConsumerId(claims)
// → "invoice-service"

// Resolve node ID from header
val nodeId = ConsumerResolver.resolveNodeId(request)
// → "invoice-service-pod-7f8b9c"
```

### Claim Resolution Order

| Priority | Claim | Source |
|---|---|---|
| 1 | `client_id` | OAuth 2.0 Client Credentials |
| 2 | `azp` | Authorized party (Keycloak, OIDC) |
| 3 | `iss` | Issuer (self-signed JWT) |

The first non-blank value is used. Throws `IllegalArgumentException` if none are present.

### Usage in a Security Filter

```kotlin
@Component
class ConsumerAuthFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ") ?: run {
            response.sendError(401)
            return
        }

        val claims = validateAndParseJwt(token)  // your JWT validation logic
        val consumerId = ConsumerResolver.resolveConsumerId(claims)
        val nodeId = ConsumerResolver.resolveNodeId(request)

        // Set on security context for downstream use
        SecurityContext.setConsumer(consumerId, nodeId)

        filterChain.doFilter(request, response)
    }
}
```

## OpenAPI Spec

The bundled OpenAPI spec is included in the JAR at `/openapi.yaml`. This can be served for API documentation:

```kotlin
@Configuration
class OpenApiConfig {
    @Bean
    fun openApiResource() = ClassPathResource("openapi.yaml")
}
```
