# Epistola Jakarta EE Client

Java client library for the [Epistola](https://github.com/epistola-app/epistola-contract) document
generation API, for applications running on a **Jakarta EE application server** — WildFly, Open
Liberty, Payara, Quarkus — where Spring is not on the classpath and never will be.

Generated from the OpenAPI contract with [OpenAPI Generator](https://openapi-generator.tech/)
(`java` / `microprofile`), with hand-written glue for identity, authentication, error handling,
result collection and client-side validation. It is the Jakarta counterpart of the Kotlin Spring
client and carries the same conventions.

## What it puts in your WAR

One 20 KB jar: `app.epistola.contract:protocol-java`, the wire-protocol logic this client shares
with the Kotlin client and the Epistola server module — partition routing, poll backoff, the
`User-Agent` grammar, problem type URIs. It is first-party, has no dependencies of its own, and is
not a container API.

```
$ ./gradlew dependencies --configuration runtimeClasspath
runtimeClasspath - Runtime classpath of source set 'main'.
\--- app.epistola.contract:protocol-java:1.1.0
```

Every API this client uses — JAX-RS, JSON-B, JSON-P, MicroProfile Rest Client, MicroProfile
Config — is supplied by the application server, and is declared `compileOnly` here. There is **no
bundled REST implementation**, so you do not have to write the exclusion every vendor client
eventually earns:

```kotlin
implementation(libs.some.vendor.client) {
    exclude(group = "org.jboss.resteasy")   // not needed for this one
}
```

`DependencyHygieneTest` asserts this on every build: nothing on the runtime classpath but
`protocol-java`, no Spring, no `javax.*`, no JAX-RS/JSON/servlet implementation. It also asserts
that `protocol-java` stays a leaf, since anything it grew would land in your WAR too.

## Installation

```kotlin
dependencies {
    implementation("app.epistola.contract:client-jakarta:<version>")

    // Already present in a Jakarta EE project; listed for completeness.
    providedCompile("jakarta.platform:jakarta.jakartaee-api:10.0.0")
    providedCompile("org.eclipse.microprofile.rest.client:microprofile-rest-client-api:3.0.1")
    providedCompile("org.eclipse.microprofile.config:microprofile-config-api:3.1")
}
```

```xml
<dependency>
    <groupId>app.epistola.contract</groupId>
    <artifactId>client-jakarta</artifactId>
    <version>VERSION</version>
</dependency>
```

Built against **Jakarta EE 10 / MicroProfile 6** APIs and compiled to **Java 17** bytecode. Those
are the widest common denominator across the four servers; an EE 11 / MicroProfile 7 container runs
it unchanged.

Packages: `app.epistola.client.jakarta.api` (operations), `app.epistola.client.jakarta.model`
(DTOs), and the hand-written helpers under `app.epistola.client.jakarta.{identity,auth,error,collect,validation}`.

## Quick start — injected (the usual way)

Every generated interface is a `@RegisterRestClient`, so CDI injection is all you need:

```java
@ApplicationScoped
public class InvoiceService {

    @Inject
    @RestClient
    GenerationApi generationApi;

    public UUID submit(String tenantId, Map<String, Object> data) {
        GenerateDocumentRequest request = new GenerateDocumentRequest()
                .catalogId("default")
                .templateId("monthly-invoice")
                .variantId("english")
                .correlationId("order-7890")
                .data(data);

        return generationApi.generateDocument(tenantId, request).getRequestId();
    }
}
```

Configuration, in `microprofile-config.properties` or as environment variables:

```properties
# MicroProfile Rest Client's own property, one per interface you inject
app.epistola.client.jakarta.api.GenerationApi/mp-rest/url=https://epistola.example.com/api

# Identity — required on every request; node-id defaults to the hostname
epistola.client.node-id=${HOSTNAME}
epistola.client.user-agent.products=zaakafhandelcomponent/3.4.0

# Authentication — an API key, or a self-signed JWT; not both
epistola.client.api-key=epk_...
# epistola.client.jwt.consumer-id=invoice-service
# epistola.client.jwt.private-key-path=/run/secrets/epistola-key.pem
# epistola.client.jwt.token-lifetime=PT60S
```

Note the base URL includes the `/api` path segment.

A `RestClientListener` registered through `META-INF/services` applies the identity and
authentication filters to Epistola clients as they are built, so there is nothing to wire up. It
touches only the rest-client interfaces this library ships (`…jakarta.api` and `…jakarta.collect`) —
your application's other rest clients are left alone, credentials included. A client built through
`EpistolaRestClients` is skipped too: the two routes are alternatives, and the explicit one wins.

## Quick start — programmatic

For a batch job, a test, or a `@Startup` singleton that assembles its own collaborators:

```java
EpistolaRestClients clients = EpistolaRestClients.builder()
        .baseUri("https://epistola.example.com/api")
        .identity(ClientIdentity.builder()
                .nodeId("my-pod-123")            // defaults to the hostname
                .product("my-app", "1.0.0")      // appended to User-Agent
                .build())
        .jwtSigner(JwtSigner.builder()
                .consumerId("invoice-service")
                .privateKey(JwtSigner.loadPrivateKey(Path.of("private.pem")))
                .build())                        // or .apiKey("epk_...")
        .build();

TemplatesApi templates = clients.api(TemplatesApi.class);
TemplateDto template = templates.getTemplate("my-tenant", "default", "monthly-invoice");
```

`clients.restClientBuilder()` returns the configured `RestClientBuilder` for anything this does not
cover — a proxy, an SSL context, a provider of your own.

## Client identity

Every request must carry `User-Agent` and `X-EP-Node-Id`. The first `User-Agent` token is always
`epistola-contract/{contractVersion}`, read from a resource the build writes from the spec:

```
User-Agent: epistola-contract/1.1.0 zaakafhandelcomponent/3.4.0
X-EP-Node-Id: my-pod-123
```

## Authentication

`JwtSigner` mints short-lived self-signed JWTs (RSA-2048+ → RS256, EC P-256 → ES256) with `iss`,
`iat`, `exp` and a fresh `jti` per request. It signs with the JDK's own `java.security` primitives
rather than a JOSE library, so it adds nothing to your deployment.

Static tenant API keys are sent as `Authorization: ApiKey <key>`. The legacy `X-API-Key` header
remains supported by the API but is deprecated and is not sent by this client.

For OAuth 2.0 client credentials, or any other scheme, register a `ClientRequestFilter` of your own
and configure neither `epistola.client.api-key` nor `epistola.client.jwt.consumer-id`.

## Error handling

`application/problem+json` responses arrive as a typed `ProblemDetailException`, with no
registration on your part — every generated interface declares
`@RegisterProvider(ApiExceptionMapper.class)`. Switch on the stable `typeSlug` and compare against
`KnownProblemSlugs` (generated from the contract's `x-problem-types` registry):

```java
try {
    templates.getTemplate("my-tenant", "default", "unknown");
} catch (ProblemDetailException e) {
    String slug = e.getTypeSlug();          // null for about:blank and non-Epistola types
    if (KnownProblemSlugs.NOT_FOUND.equals(slug)) {
        log.warn("not found: {}", e.getDetail());
    } else if (KnownProblemSlugs.VALIDATION_ERROR.equals(slug)) {
        e.getErrors().forEach(err -> log.warn("{}: {}", err.getField(), err.getMessage()));
    } else if (KnownProblemSlugs.API_KEY_AUTH_DISABLED.equals(slug)) {
        log.error("this deployment requires JWT authentication");
    } else {                                // always keep a fallback: the API can add problem
        log.error("{} {}", e.getProblemStatus(), e.getTitle());   // types without a client release
    }
}
```

The slug is a plain `String`, so on Java 21 a `switch` with `case null, default ->` reads better; the
`if`/`else` above is what compiles on the Java 17 this client targets.

`ProblemDetailException` extends the generated `ApiException`, so `catch (ApiException e)` keeps
working. Error responses that are not parseable problem+json stay a plain `ApiException`.

Some Epistola Suite deployments disable API-key authentication entirely; that arrives as
`KnownProblemSlugs.API_KEY_AUTH_DISABLED`, which is the signal to guide the caller to JWT auth.

## Document generation & result collection

Asynchronous generation is the production path, so result collection is not optional.
`ResultCollector` polls `/generation/collect`: NDJSON streamed one result at a time (constant
memory), compression, adaptive polling, a sequence-based acknowledgement cursor that survives a
restart, and partition-aware routing helpers.

```java
@Inject
@RestClient
GenerationCollectApi collectApi;

@Inject
ManagedExecutorService executor;

ResultCollector collector = ResultCollector.builder()
        .collectApi(collectApi)
        .tenantId("acme-corp")
        .registerShutdownHook(false)          // the container owns the lifecycle
        .handler(result -> {
            switch (result.getStatus()) {
                case COMPLETED -> download(result.getDocumentId(), result.getCorrelationId());
                case FAILED -> logFailure(result.getCorrelationId(), result.getError());
            }
        })
        .errorHandler(e -> log.warn("collect failed, backing off", e))
        .build();

executor.submit(collector::start);            // blocks; stop() ends it
```

Configure it like any other client:

```properties
app.epistola.client.jakarta.collect.GenerationCollectApi/mp-rest/url=https://epistola.example.com/api
```

Inside an application server, run `start()` on a `ManagedExecutorService` or drive `collectOnce()`
from a `@Schedule` timer — never on an unmanaged `new Thread(...)`.

**If the handler throws**, the acknowledgement cursor does not advance and the batch is redelivered
on the next poll. Make the handler idempotent.

**Compression**: gzip is handled by the JDK. Add `net.jpountz.lz4:lz4-java` or
`com.github.luben:zstd-jni` to your own build and they are offered in `Accept-Encoding` and used
automatically. The decompressor is chosen by sniffing the stream's magic bytes rather than trusting
`Content-Encoding`, because an application server may have decoded gzip already.

**Routing**: `collector.routingKeyToMe(key)` returns a routing key that lands on one of this node's
partitions, so a submission's result comes back to the node that made it. Pass the returned value as
the request's `routingKey`.

```java
String routingKey = collector.routingKeyToMe("order-7890");
generationApi.generateDocument(tenantId, request.routingKey(routingKey));
collector.kick();                              // a result is expected shortly
```

## Client-side validation

Two independent layers, both opt-in.

**Contract constraints** — `ModelValidation` is generated from the spec's `minLength`, `maxLength`,
`pattern`, `minimum`, `maximum` and `minItems`, so a malformed request is rejected locally instead
of costing a round trip. Each overload returns its argument:

```java
tenants.createTenant(ModelValidation.validate(new CreateTenantRequest().id("acme-corp").name("Acme")));
```

**Template JSON Schema** — `ValidatingGenerationApi` fetches the template's own schema (cached with
a TTL) and validates the generation data against it before submitting. A batch is validated in full
and every violation is reported at once, each path prefixed with its item index:

```java
ValidatingGenerationApi generation = new ValidatingGenerationApi(generationApi, templatesApi);
generation.generateDocument("my-tenant", request);   // TemplateDataValidationException on failure
```

This layer needs an optional dependency, deliberately not shipped:

```kotlin
implementation("com.networknt:json-schema-validator:1.5.7")
```

## Building

The client is generated from the bundled spec, so bundle it first:

```bash
make bundle                        # from the repository root
make build-jakarta                 # or: cd contracts/api/clients/jakarta && ./gradlew build
```

Generated sources land in `build/generated` (APIs and models), `build/generated-problem-slugs`
(`KnownProblemSlugs`) and `build/generated-validation` (`ModelValidation`); none of it is committed.

### Deployment test

A jar that resolves is not a jar that deploys. `WildFlyDeploymentTest` builds a WAR containing this
client plus the smoke application in `src/smokeApp` — a CDI bean that injects a generated
`@RestClient` interface — deploys it into a real WildFly, and calls it:

```bash
./gradlew deploymentTest -PdeploymentTest      # needs Docker
```

It is excluded from `./gradlew build` and from CI because it pulls a WildFly image.

## Relationship to the other clients

| | Artifact | HTTP | JSON |
| --- | --- | --- | --- |
| Kotlin / Spring Boot 3 | `app.epistola.contract:client-spring3-restclient` | Spring `RestClient` | Jackson |
| **Jakarta EE** | **`app.epistola.contract:client-jakarta`** | **MicroProfile Rest Client** | **JSON-B** |
| .NET 8 | `Epistola.Contract.Client` | `HttpClient` | `System.Text.Json` |
| Python | `epistola-client` | `urllib3` | Pydantic |

All four are generated from the same spec and carry the same conventions: identity headers, the
problem-type registry, the result-collection protocol, contract-constraint validation.

Release history: [CHANGELOG.md](../../../../CHANGELOG.md).
