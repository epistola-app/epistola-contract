# Epistola Kotlin Client

Generated Kotlin client for the Epistola API using Spring RestClient, with additional utilities for authentication, identity management, and generation result collection.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("app.epistola.contract:client-spring3-restclient:0.3.0")

    // Optional: for lz4 compression support
    implementation("net.jpountz.lz4:lz4-java:1.8.0")
    // Optional: for zstd compression support
    implementation("com.github.luben:zstd-jni:1.5.6-4")
}
```

## Quick Start

```kotlin
// 1. Set up client identity (required on all requests)
val identity = ClientIdentity.builder()
    .nodeId("my-pod-123")                              // Kubernetes pod name or hostname
    .product("my-app", "1.0.0")                        // your application name + version
    .build()

// 2. Set up authentication (choose one)

// Option A: Self-signed JWT (no IdP needed)
val signer = JwtSigner.builder()
    .consumerId("invoice-service")                     // your registered consumer ID
    .privateKey(JwtSigner.loadPrivateKey(Path.of("private.pem")))
    .build()

// Option B: OAuth — use your IdP's token in a custom interceptor
// Option C: API key — use Authorization: ApiKey <key>
val apiKeyAuth = ApiKeyAuth.of("epk_...")

// 3. Create RestClient with interceptors
val restClient = RestClient.builder()
    .baseUrl("https://epistola.example.com/api")
    .requestInterceptor(identity.interceptor())        // User-Agent + X-EP-Node-Id
    .requestInterceptor(signer.interceptor())          // Authorization: Bearer <jwt>
    .build()

// 4. Use generated API clients
val generationApi = GenerationApi(restClient)
val consumersApi = ConsumersApi(restClient)
val systemApi = SystemApi(restClient)
```

## JSON configuration

Call `epistolaMessageConverters()` when building the `RestClient`. It installs a Jackson converter
that **omits properties you never set** instead of writing them as `null`, and reads
`application/problem+json` with the same mapper.

This is not cosmetic. The generated request models are plain nullable properties with no way to
distinguish "not set" from "explicitly null", so whatever the serializer does with an unset property
becomes the request's meaning:

- On the API's `PATCH` operations a null is an instruction — `description` and `contact` are
  documented "null to clear", `expiresAt` as "null to remove expiry". A default mapper turns
  `UpdateConsumerRequest(name = "…")` into a rename *and* an erase of everything you left alone.
- Some fields do not accept null at all. `GenerateDocumentRequest.attributes` is typed `array` with
  no null in the union, so a default mapper produces a body that a server validating against the
  contract rejects outright.

The trade is that clearing a field is not expressible — but it never was, since you could not clear
one field without clearing every other you had not set. Expressing both needs models that carry the
distinction, which is a larger change.

`EpistolaJson.objectMapper` is the same mapper, exposed for anywhere you serialize these models
yourself.

### Binary operations return `Resource`, not `File`

Every operation the contract declares as `format: binary` — downloading a document, rendering a
preview, fetching or uploading an asset's content, importing a catalog archive — is generated as
`org.springframework.core.io.Resource`, both for responses and for multipart request parts. Spring
converts `Resource` out of the box, on the response side and the multipart side alike, so these
calls work with a completely default `RestClient` — no converter to install, nothing to opt into.

This used to be `java.io.File`, which Spring has no converter for at all: every one of these calls
failed outright, always, with `UnknownContentTypeException`, whatever you configured. `Resource` is
also the more useful type for a caller who already has the bytes — `ByteArrayResource(bytes)` needs
no temporary file, where `File` forced you to write one just to hand it back.

One thing to know about the upload side: a multipart file part's `Content-Disposition` only gets a
`filename` attribute if the `Resource` reports one. `ByteArrayResource` returns `null` from
`getFilename()` unless you override it, and a part with no filename is indistinguishable from a
plain form field to a multipart parser expecting a file — override it:

```kotlin
val archive = object : ByteArrayResource(bytes) {
    override fun getFilename() = "catalog.zip"
}
catalogsApi.importCatalog(tenantId, archive)
```

`FileSystemResource(file)` needs no such override — a real file always has a name.

## Client Identity

Every request must include `User-Agent` and `X-EP-Node-Id` headers. The `ClientIdentity` class manages these automatically.

```kotlin
val identity = ClientIdentity.builder()
    .nodeId("invoice-service-pod-7f8b9c")              // required: identifies this instance
    .product("valtimo-epistola-plugin", "1.2.0")       // optional: additional software stack info
    .product("gzac", "5.0.0")                          // optional: more products
    .build()

// Produces headers:
// User-Agent: epistola-contract/x.y.z valtimo-epistola-plugin/1.2.0 gzac/5.0.0
// X-EP-Node-Id: invoice-service-pod-7f8b9c

// Access values
identity.userAgent       // "epistola-contract/x.y.z ..."
identity.nodeId          // "invoice-service-pod-7f8b9c"
ClientIdentity.contractVersion  // "0.3.0" (from build)
```

If `nodeId` is not set, it defaults to the local hostname.

## Authentication

### Self-Signed JWT

For environments without an Identity Provider. Your application signs short-lived JWTs with a private key registered with Epistola.

**Setup (one-time):**

```bash
# Generate a key pair
openssl genrsa -out private.pem 2048
openssl rsa -in private.pem -pubout -out public.pem

# Register with Epistola (the consumer starts in 'pending' status)
curl -X POST https://epistola.example.com/api/tenants/acme-corp/consumers/register \
  -H "Content-Type: application/vnd.epistola.v1+json" \
  -d '{
    "id": "invoice-service",
    "name": "Invoice Service",
    "publicKey": "'"$(cat public.pem)"'"
  }'

# Wait for tenant manager to approve your registration
```

**Usage:**

```kotlin
val signer = JwtSigner.builder()
    .consumerId("invoice-service")                     // must match registered ID
    .privateKey(JwtSigner.loadPrivateKey(Path.of("private.pem")))
    .tokenLifetime(Duration.ofSeconds(60))             // default: 60s
    .build()

// As interceptor (recommended — auto-creates fresh JWT per request)
val restClient = RestClient.builder()
    .baseUrl("https://epistola.example.com/api")
    .requestInterceptor(signer.interceptor())
    .build()

// Or create tokens manually
val jwt: String = signer.createToken()
```

### API Key

Static tenant API keys can be sent through the standard `Authorization` header:

```kotlin
val restClient = RestClient.builder()
    .baseUrl("https://epistola.example.com/api")
    .requestInterceptor(identity.interceptor())
    .requestInterceptor(ApiKeyAuth.of("epk_...").interceptor()) // Authorization: ApiKey <key>
    .build()
```

The legacy `X-API-Key` header remains supported for existing integrations, but is deprecated.
Some Epistola Suite deployments may disable API-key authentication entirely. When that happens,
the API returns a Problem Details response with `type` slug `api-key-auth-disabled`; switch on the
slug in `ProblemDetailException` and guide the caller to JWT auth.

Supports RSA (2048+) and EC (P-256) keys. Each token includes `iss`, `iat`, `exp`, and a unique `jti` for replay protection.

### OAuth

For environments with an IdP (Keycloak, Azure AD, etc.). Use your IdP's token directly.

```kotlin
// Example with a custom interceptor that fetches tokens from your IdP
val restClient = RestClient.builder()
    .baseUrl("https://epistola.example.com/api")
    .requestInterceptor(identity.interceptor())
    .requestInterceptor { request, body, execution ->
        request.headers.setBearerAuth(myOAuthTokenProvider.getToken())
        execution.execute(request, body)
    }
    .build()
```

## Error Handling

The API reports errors as [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457)
(`Content-Type: application/problem+json`). The machine-readable discriminator is the problem
**`type`** URI — clients switch on `type`, not on a status code or a separate error code.

By default Spring RestClient throws a raw `RestClientResponseException` whose body you would have
to parse yourself. The opt-in `installProblemDetailHandler()` builder extension parses the
problem body and throws a typed **`ProblemDetailException`** instead:

```kotlin
import app.epistola.client.error.installProblemDetailHandler

val restClient = RestClient.builder()
    .baseUrl("https://epistola.example.com/api")
    .requestInterceptor(identity.interceptor())
    .requestInterceptor(signer.interceptor())
    .installProblemDetailHandler()                     // <-- opt-in
    .build()
```

`ProblemDetailException` extends `RestClientResponseException`, so existing
`catch (e: RestClientResponseException)` sites keep working. Switch on the `type` slug:

```kotlin
import app.epistola.client.error.ProblemDetailException
import app.epistola.client.error.KnownProblemSlugs

try {
    tenantsApi.getTenant("acme")
} catch (e: ProblemDetailException) {
    when (e.typeSlug) {
        KnownProblemSlugs.NOT_FOUND                   -> log.warn("Tenant missing: ${e.detail}")
        KnownProblemSlugs.FORBIDDEN                   -> throw AccessDeniedException(e.detail)
        KnownProblemSlugs.VALIDATION_ERROR            -> e.errors.forEach { println("${it.field}: ${it.message}") }
        KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR ->
            e.validationErrors.forEach { (example, failures) ->
                failures.forEach { println("$example ${it.path}: ${it.message}") }
            }
        else                                          -> throw e   // unknown / framework error
    }
}
```

`ProblemDetailException` exposes `type`, `typeSlug`, `title`, `problemStatus`, `detail`,
`errors` (field-level validation errors, empty unless it's a validation problem),
`validationErrors` (per-example data-model failures, empty unless it's a
`data-model-validation-error` problem), `isValidationProblem`, `isDataModelValidationProblem`, and
`extensions`. See [error-types.md](../../docs/error-types.md) for the full list of problem `type`
slugs.

Error responses that are **not** `application/problem+json` (e.g. an HTML page from a proxy or
gateway, or an empty body) still surface as a plain `RestClientResponseException` /
`HttpClientErrorException` / `HttpServerErrorException` — the handler is additive and never
hides information.

### Extension members outside `errors` and `validationErrors`

A problem body can carry members this contract doesn't give a dedicated name — the API may add
one to any existing or future problem type without that being a breaking change. `extensions` is a
`Map<String, Any?>` of everything the five RFC 9457 base fields don't already cover:

```kotlin
} catch (e: ProblemDetailException) {
    if (e.typeSlug == "catalog-schema-too-old") {
        val version = e.extensions["version"] as? Int
        val baselineVersion = e.extensions["baselineVersion"] as? Int
        log.error("Bundled catalog schema is version $version; server requires $baselineVersion+")
    }
}
```

`typeSlug` needs no registry entry to work for a problem type either — it strips
[error-types.md](../../docs/error-types.md)'s registered base URI generically, so both `typeSlug`
and `extensions` are available for a problem type the moment the server starts sending it, ahead of
any client release that adds a named constant for it.

## Generating Documents

```kotlin
val api = GenerationApi(restClient)

// Single document
val job = api.generateDocument("acme-corp", GenerateDocumentRequest(
    catalogId = "default",
    templateId = "invoice",
    data = mapOf("customer" to "Jane Smith", "amount" to 99.99),
    correlationId = "order-123",
    routingKey = "order-123",          // controls which node collects the result
))
println("Job submitted: ${job.requestId}")

// Batch generation
val batch = api.generateBatch("acme-corp", GenerateBatchRequest(
    routingKey = "order-456",          // all items route to same node
    items = listOf(
        BatchGenerationItem(catalogId = "default", templateId = "invoice", data = invoiceData),
        BatchGenerationItem(catalogId = "default", templateId = "packing-slip", data = packingData),
    ),
))
```

### Downloading the result

`downloadDocument`, `previewDocument`, and an asset's `downloadAssetContent` all return
`org.springframework.core.io.Resource` — stream it, don't buffer it, unless the document is known to
be small:

```kotlin
val document = api.downloadDocument("acme-corp", documentId)
document.inputStream.use { input -> input.copyTo(outputStream) }
```

### Routing Keys

The `routingKey` determines which consumer node receives the result via the collect endpoint. Results with the same routing key always go to the same node (consistent hashing).

```kotlin
// Use a business key for locality
routingKey = "customer-456"     // all results for this customer → same node

// Use the collector to ensure results come back to THIS node
val myKey = collector.routingKeyToMe("order-123")
routingKey = myKey              // guaranteed to route to this node's partitions
```

## Collecting Generation Results

The `ResultCollector` polls the `/generation/collect` endpoint for completed/failed generation results. It uses NDJSON streaming (constant memory), compressed responses, and adaptive polling.

```kotlin
val collector = ResultCollector.builder()
    .restClient(restClient)
    .tenantId("acme-corp")
    .batchSize(100)                                    // max results per poll
    .minInterval(Duration.ofSeconds(1))                // poll interval when results are flowing
    .maxInterval(Duration.ofSeconds(30))               // max backoff when idle
    .handler { result ->
        when (result.status) {
            "COMPLETED" -> {
                println("Document ready: ${result.documentId}")
                println("Correlation: ${result.correlationId}")
                downloadDocument(result.documentId!!)
            }
            "FAILED" -> {
                println("Generation failed: ${result.error}")
            }
        }
    }
    .errorHandler { e ->
        logger.error("Collection failed", e)
    }
    .build()

// Start the adaptive poll loop (blocks current thread)
collector.start()

// Or poll manually for custom scheduling
val result = collector.collectOnce()
println("Got ${result.count} results, hasMore=${result.hasMore}")
```

### Adaptive Polling

The collector automatically adjusts its polling frequency:

- **hasMore = true** → poll immediately (catching up)
- **Got results** → reset to `minInterval` (1s default)
- **Empty response** → exponential backoff: 1s → 2s → 4s → ... → 30s max
- **Error** → backoff with jitter

### Partition Routing Helpers

After the first poll, the collector knows which partitions are assigned to this node. Use this to compute routing keys that target your own node:

```kotlin
// After first poll, partition assignment is available
collector.collectOnce()

// Which partition would this key land on?
collector.partitionFor("order-123")       // → 7

// Would this key come to me?
collector.isMyPartition("order-123")      // → true/false

// Get a routing key that guarantees delivery to this node
collector.routingKeyToMe("order-123")     // → "3:order-123" (prefixed to target my partition)
```

### Metrics

Implement `ResultCollector.MetricsListener` for observability:

```kotlin
val collector = ResultCollector.builder()
    .restClient(restClient)
    .tenantId("acme-corp")
    .handler { result -> process(result) }
    .metricsListener(object : ResultCollector.MetricsListener {
        override fun onPoll(count: Int, hasMore: Boolean, durationMs: Long, error: Exception?) {
            meterRegistry.counter("epistola.collect.polls").increment()
            meterRegistry.counter("epistola.collect.results").increment(count.toDouble())
            meterRegistry.timer("epistola.collect.duration").record(durationMs, TimeUnit.MILLISECONDS)
        }
        override fun onPartitionChange(
            oldAssignment: ResultCollector.PartitionAssignment?,
            newAssignment: ResultCollector.PartitionAssignment,
        ) {
            logger.info("Partition assignment changed: ${newAssignment.mine}")
        }
    })
    .build()
```

### Compression

The collector negotiates compression with the server:

- **gzip** — always available (built-in)
- **lz4** — add `net.jpountz.lz4:lz4-java` to your classpath (fastest decompression)
- **zstd** — add `com.github.luben:zstd-jni` to your classpath (best compression ratio)

The collector auto-detects available decompressors and advertises them to the server via `Accept-Encoding`.

### Shutdown

The collector registers a JVM shutdown hook by default for graceful stop:

```kotlin
// Disable if managing lifecycle yourself
val collector = ResultCollector.builder()
    .registerShutdownHook(false)
    .build()

// Manual stop
collector.stop()  // signals loop to exit after current poll
```

## Ping / Health Check

```kotlin
val systemApi = SystemApi(restClient)

// Basic health check (works without auth)
val pong = systemApi.ping(PingRequest(
    name = "Invoice Service",
    contact = "billing-team@acme-corp.com",
))
println("Server status: ${pong.status}")  // UP or DEGRADED

// Authenticated ping includes server details + partition assignment
println("Server version: ${pong.details?.serverVersion}")
println("My partitions: ${pong.details?.partitions?.mine}")
```

## Client-Side Validation

The client includes generated `.validate()` extension functions for request DTOs:

```kotlin
import app.epistola.client.validation.validate

val request = CreateTenantRequest(id = "acme-corp", name = "Acme Corporation")
request.validate()  // throws IllegalArgumentException if constraints are violated
```

## Full Example

```kotlin
fun main() {
    // Identity + auth
    val identity = ClientIdentity.builder()
        .nodeId(System.getenv("HOSTNAME") ?: "local")
        .product("invoice-service", "2.1.0")
        .build()

    val signer = JwtSigner.builder()
        .consumerId("invoice-service")
        .privateKey(JwtSigner.loadPrivateKey(Path.of("/secrets/private.pem")))
        .build()

    val restClient = RestClient.builder()
        .baseUrl(System.getenv("EPISTOLA_URL") ?: "http://localhost:8080/api")
        .requestInterceptor(identity.interceptor())
        .requestInterceptor(signer.interceptor())
        .build()

    // Start collecting results in a background thread
    val collector = ResultCollector.builder()
        .restClient(restClient)
        .tenantId("acme-corp")
        .handler { result ->
            if (result.status == "COMPLETED") {
                println("Document ${result.documentId} ready for ${result.correlationId}")
            }
        }
        .build()

    Thread({ collector.start() }, "result-collector").apply { isDaemon = true }.start()

    // Submit generation requests
    val api = GenerationApi(restClient)
    val job = api.generateDocument("acme-corp", GenerateDocumentRequest(
        catalogId = "default",
        templateId = "invoice",
        data = mapOf("customer" to "Jane Smith"),
        routingKey = collector.routingKeyToMe("order-001") ?: "order-001",
    ))
    println("Submitted: ${job.requestId}")

    // Keep running...
    Thread.sleep(Long.MAX_VALUE)
}
```
