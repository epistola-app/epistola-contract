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

## Client Identity

Every request must include `User-Agent` and `X-EP-Node-Id` headers. The `ClientIdentity` class manages these automatically.

```kotlin
val identity = ClientIdentity.builder()
    .nodeId("invoice-service-pod-7f8b9c")              // required: identifies this instance
    .product("valtimo-epistola-plugin", "1.2.0")       // optional: additional software stack info
    .product("gzac", "5.0.0")                          // optional: more products
    .build()

// Produces headers:
// User-Agent: epistola-contract/0.3.0 valtimo-epistola-plugin/1.2.0 gzac/5.0.0
// X-EP-Node-Id: invoice-service-pod-7f8b9c

// Access values
identity.userAgent       // "epistola-contract/0.3.0 ..."
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
