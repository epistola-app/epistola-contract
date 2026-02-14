# Event System Design

> **Status**: Draft
> **Date**: 2026-02-12
> **Audience**: Epistola contributors and API consumers

---

## 1. Problem Statement

Epistola currently provides no mechanism for API consumers to learn about changes in the system beyond what they explicitly poll for. The only near-real-time pattern today is job status polling (`GET /tenants/{tenantId}/documents/jobs/{requestId}`), which is scoped to a single generation request.

This leaves several important use-cases unserved:

- **Cache invalidation** — Consumers that cache template metadata, variant lists, or activation state have no way to know when that data becomes stale. They must choose between aggressive polling (wasteful) or long TTLs (stale data).
- **Workflow orchestration** — When a version is published or an activation changes, downstream systems (preview renderers, CDN warmers, audit loggers) need to react. Today, this requires out-of-band coordination.
- **Multi-instance coordination** — When multiple server instances exist behind a load balancer, an action on one instance (e.g., publishing a version) is invisible to consumers connected to other instances unless they re-fetch.
- **Audit and compliance** — Tracking who changed what and when requires either querying each resource individually or building a custom audit trail outside the API contract.

### Goals

1. Deliver a batched stream of domain events to interested consumers with low latency.
2. Support filtering so consumers receive only events relevant to them (by resource type, template, environment, etc.).
3. Work correctly when both the server and client are horizontally scaled to multiple instances.
4. Remain expressible in OpenAPI 3.1 and compatible with generated Kotlin clients (Spring RestClient) and server stubs (Spring Boot 4 interfaces).
5. Provide at-least-once delivery guarantees with idempotency support so consumers can safely retry.

### Non-goals

- Sub-second push latency (this is a document platform, not a trading system).
- Replacing the existing job polling endpoint (that continues to serve its purpose).
- Cross-tenant event streaming (events are always tenant-scoped).

---

## 2. Event Model (shared across all options)

Regardless of delivery mechanism, the event schema and semantics are the same.

### 2.1 Event Types

Events correspond to state changes in the domain model. Every mutating API operation produces exactly one event.

| Event Type | Trigger |
|---|---|
| `template.created` | `POST /tenants/{tenantId}/templates` |
| `template.updated` | `PATCH /tenants/{tenantId}/templates/{templateId}` |
| `template.deleted` | `DELETE /tenants/{tenantId}/templates/{templateId}` |
| `variant.created` | `POST /tenants/{tenantId}/templates/{templateId}/variants` |
| `variant.updated` | `PATCH /tenants/{tenantId}/templates/{templateId}/variants/{variantId}` |
| `variant.deleted` | `DELETE /tenants/{tenantId}/templates/{templateId}/variants/{variantId}` |
| `version.draft_updated` | `PATCH .../versions/{versionId}` |
| `version.published` | `POST .../versions/{versionId}/publish` |
| `version.archived` | `POST .../versions/{versionId}/archive` |
| `activation.changed` | `PUT .../variants/{variantId}/activations/{environmentId}` |
| `activation.removed` | `DELETE .../variants/{variantId}/activations/{environmentId}` |
| `theme.created` | `POST /tenants/{tenantId}/themes` |
| `theme.updated` | `PATCH /tenants/{tenantId}/themes/{themeId}` |
| `theme.deleted` | `DELETE /tenants/{tenantId}/themes/{themeId}` |
| `environment.created` | `POST /tenants/{tenantId}/environments` |
| `environment.updated` | `PATCH /tenants/{tenantId}/environments/{environmentId}` |
| `environment.deleted` | `DELETE /tenants/{tenantId}/environments/{environmentId}` |
| `generation.completed` | Job transitions to `COMPLETED` |
| `generation.failed` | Job transitions to `FAILED` |

### 2.2 Event Envelope

```yaml
EventEnvelope:
  type: object
  required:
    - id
    - type
    - tenantId
    - timestamp
    - sequenceNumber
    - resourceRef
  properties:
    id:
      type: string
      format: uuid
      description: Globally unique event identifier (for idempotency)
    type:
      type: string
      description: Dot-separated event type (e.g., "version.published")
    tenantId:
      type: string
      description: Tenant scope of this event
    timestamp:
      type: string
      format: date-time
      description: When the event occurred (ISO 8601)
    sequenceNumber:
      type: integer
      format: int64
      description: >
        Monotonically increasing per-tenant sequence number.
        Guarantees total ordering within a tenant. Consumers use
        this as a cursor for resumption.
    resourceRef:
      type: object
      required:
        - resourceType
        - resourceId
      properties:
        resourceType:
          type: string
          enum:
            - template
            - variant
            - version
            - activation
            - theme
            - environment
            - generation-job
        resourceId:
          type: string
          description: Slug or UUID of the affected resource
        parentRefs:
          type: object
          description: >
            Ancestor identifiers for hierarchical resources.
            For example, a version event includes templateId
            and variantId so consumers can filter without
            fetching the resource.
          additionalProperties:
            type: string
    actor:
      type: object
      description: Who caused the event (if available)
      properties:
        type:
          type: string
          enum: [user, api-key, system]
        identifier:
          type: string
    data:
      type: object
      description: >
        Event-type-specific payload. For "created" and "updated"
        events, contains the resource's state after the change.
        For "deleted" events, contains the resource's state
        before deletion.
```

**Example event:**

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "type": "version.published",
  "tenantId": "acme-corp",
  "timestamp": "2026-02-12T14:30:00Z",
  "sequenceNumber": 4207,
  "resourceRef": {
    "resourceType": "version",
    "resourceId": "3",
    "parentRefs": {
      "templateId": "invoice",
      "variantId": "english"
    }
  },
  "actor": {
    "type": "user",
    "identifier": "jane@acme-corp.com"
  },
  "data": {
    "versionId": 3,
    "status": "published",
    "publishedAt": "2026-02-12T14:30:00Z"
  }
}
```

### 2.3 Ordering and Idempotency

- **Sequence numbers** are monotonically increasing per tenant. They are assigned by the server at write time (database sequence) and form a total order within a tenant.
- **Event IDs** (UUIDs) allow consumers to deduplicate events they have already processed. Consumers should track the last processed event ID or sequence number.
- **No cross-tenant ordering guarantee** exists. Each tenant's event stream is independent.

### 2.4 Filtering

All options support server-side filtering to reduce payload size. Filters are applied as query parameters or subscription fields:

| Filter | Type | Example |
|---|---|---|
| `resourceType` | string (repeatable) | `resourceType=version&resourceType=activation` |
| `templateId` | string | `templateId=invoice` |
| `environmentId` | string | `environmentId=production` |
| `eventType` | string (repeatable) | `eventType=version.published` |

Filters are combined with AND logic (all must match). Repeatable filters use OR within the same key.

---

## 3. Design Options

### Option A: Long Polling with Event Feed

#### How it works

The client makes a `GET` request to an event feed endpoint. The server holds the connection open (up to a configurable timeout, e.g., 30 seconds) until:
1. New events matching the client's filters arrive, or
2. The timeout expires (server returns an empty batch with the current cursor).

The client immediately reconnects after receiving a response.

#### API Shape

```
GET /tenants/{tenantId}/events
  ?after={sequenceNumber}      # cursor: last seen sequence number
  &timeout=30                  # max seconds to hold connection (1-60, default 30)
  &resourceType=version        # filter (repeatable)
  &templateId=invoice          # filter
  &limit=100                   # max events per batch (1-1000, default 100)

Accept: application/vnd.epistola.v1+json

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "events": [ ... ],
  "cursor": {
    "sequenceNumber": 4210,
    "hasMore": false
  }
}
```

When catching up from a stale cursor, the server returns immediately with a full batch and `hasMore: true`. The client pages through history before entering long-poll mode.

#### Scalability

- **Server**: Each held connection occupies a thread (or virtual thread on Spring Boot 4/Loom). With virtual threads, thousands of held connections are feasible per instance. Events are written to a shared database event log; any instance can serve any tenant's feed.
- **Client**: Multiple client instances can each maintain their own cursor. No coordination needed — each instance tracks its own position.
- **Database**: Requires an append-only `events` table with a per-tenant sequence column and appropriate indexes (`tenant_id, sequence_number`). Old events can be retained for a configurable window (e.g., 7 days) and purged.

#### Delivery Guarantees

- **At-least-once**: If the client crashes after receiving a batch but before persisting the cursor, it will re-receive those events on reconnection.
- **Ordering**: Events arrive in sequence-number order. No gaps.
- **No data loss**: Events are durably stored server-side. A client that reconnects after downtime catches up from its last cursor.

#### Client/Server Complexity

| Aspect | Complexity |
|---|---|
| Server implementation | Medium — Thread holding logic, event log table, cursor management |
| Client implementation | Low — Standard HTTP request loop with cursor tracking |
| Generated client compatibility | **Full** — Regular GET request/response, works with Spring RestClient |
| Generated server compatibility | **Full** — Standard controller method returning a response DTO |
| OpenAPI expressibility | **Full** — Standard GET endpoint with query parameters |

#### Pros

- Simple client implementation: just a GET loop with a cursor.
- Full OpenAPI compatibility — generated client/server work without customisation.
- Graceful degradation: if the server does not support holding, it becomes regular polling.
- Cursor-based catch-up works across client restarts.
- Firewall and proxy friendly (standard HTTP request/response).

#### Cons

- Higher connection churn than SSE (reconnect after every batch or timeout).
- Holding connections still consumes server resources (though virtual threads mitigate this).
- Latency floor of the reconnection round-trip (typically <100ms on local networks).
- `timeout` parameter is unconventional — consumers need to understand it.

---

### Option B: Server-Sent Events (SSE)

#### How it works

The client opens a persistent HTTP connection. The server pushes events as they occur using the `text/event-stream` content type (SSE protocol). The connection stays open indefinitely; the server sends heartbeats (comments) to keep it alive.

#### API Shape

```
GET /tenants/{tenantId}/events/stream
  ?after={sequenceNumber}      # resume from cursor
  &resourceType=version        # filter (repeatable)
  &templateId=invoice          # filter

Accept: text/event-stream

200 OK
Content-Type: text/event-stream

: heartbeat

id: 4208
event: version.published
data: {"id":"a1b2...","type":"version.published",...}

id: 4209
event: activation.changed
data: {"id":"b2c3...","type":"activation.changed",...}

: heartbeat
```

The `id` field is set to the sequence number, enabling the browser/client to send `Last-Event-ID` on reconnection.

#### Scalability

- **Server**: Each connected client holds a persistent connection. With virtual threads (Spring Boot 4 + Loom), this scales to thousands per instance. The server needs a pub-sub mechanism (e.g., PostgreSQL `LISTEN/NOTIFY`, Redis Pub/Sub, or an in-memory event bus backed by a shared log) to fan events from any writer instance to all reader instances.
- **Client**: Each client instance maintains one SSE connection per tenant it cares about. No coordination between client instances.
- **Infrastructure**: Load balancers must support long-lived HTTP connections. Some reverse proxies (e.g., older Nginx configs) may buffer or terminate SSE connections prematurely.

#### Delivery Guarantees

- **At-least-once**: `Last-Event-ID` header enables automatic resume. Combined with the server-side event log, no events are lost across reconnections.
- **Ordering**: Events are pushed in sequence-number order per connection.
- **Gap risk**: If a connection drops and `Last-Event-ID` is not supported (or the gap is too large), the client falls back to catching up from the event log endpoint.

#### Client/Server Complexity

| Aspect | Complexity |
|---|---|
| Server implementation | High — SSE emitter lifecycle, heartbeats, cross-instance fan-out |
| Client implementation | Medium — SSE client library, reconnection logic, `Last-Event-ID` |
| Generated client compatibility | **Poor** — Spring RestClient has no built-in SSE support. A hand-written `SseClient` or third-party library (e.g., OkHttp SSE) would be needed alongside the generated client. |
| Generated server compatibility | **Poor** — OpenAPI Generator produces interface stubs returning DTOs; SSE requires returning `SseEmitter` or `Flux<ServerSentEvent>`, which cannot be expressed as a generated interface. Server implementation must bypass the generated stub. |
| OpenAPI expressibility | **Partial** — SSE can be described in OpenAPI 3.1 with `content-type: text/event-stream`, but the semantics (streaming, reconnection, event framing) are not captured. Code generators ignore or mishandle it. |

#### Pros

- Lowest latency: events arrive as they happen, no reconnection delay.
- Built-in reconnection with `Last-Event-ID` (browser-native, some libraries support it).
- Efficient: one long-lived connection per tenant instead of repeated requests.

#### Cons

- **Breaks the generated client** — Spring RestClient cannot consume SSE streams. Consumers would need a separate, hand-written SSE client.
- **Breaks the generated server** — SSE cannot be expressed as a return type in OpenAPI-generated Spring interfaces. The server implementation must work around generated stubs.
- Cross-instance fan-out adds infrastructure complexity (pub/sub layer).
- Load balancers, proxies, and firewalls can interfere with long-lived connections.
- Content type is `text/event-stream`, not `application/vnd.epistola.v1+json` — inconsistent with the rest of the API.

---

### Option C: Webhook Subscriptions

#### How it works

Consumers register a callback URL with the Epistola API. When events occur, the server batches them and sends POST requests to each subscriber's callback URL. Subscriptions include filters so only relevant events are delivered.

#### API Shape

**Registration:**

```
POST /tenants/{tenantId}/event-subscriptions
Content-Type: application/vnd.epistola.v1+json

{
  "callbackUrl": "https://my-app.example.com/epistola/events",
  "secret": "whsec_abc123...",
  "filters": {
    "resourceTypes": ["version", "activation"],
    "templateIds": ["invoice"]
  },
  "batchSettings": {
    "maxBatchSize": 50,
    "maxDelaySeconds": 5
  }
}

201 Created
{
  "id": "sub-001",
  "callbackUrl": "https://my-app.example.com/epistola/events",
  "status": "active",
  "createdAt": "2026-02-12T14:00:00Z"
}
```

**Delivery (server → consumer):**

```
POST https://my-app.example.com/epistola/events
Content-Type: application/vnd.epistola.v1+json
X-Epistola-Signature: sha256=...
X-Epistola-Delivery-Id: dlv-12345

{
  "subscriptionId": "sub-001",
  "events": [ ... ],
  "cursor": {
    "sequenceNumber": 4210
  }
}
```

**Management:**

```
GET    /tenants/{tenantId}/event-subscriptions           # List subscriptions
GET    /tenants/{tenantId}/event-subscriptions/{subId}    # Get subscription
PATCH  /tenants/{tenantId}/event-subscriptions/{subId}    # Update subscription
DELETE /tenants/{tenantId}/event-subscriptions/{subId}    # Delete subscription
POST   /tenants/{tenantId}/event-subscriptions/{subId}/test  # Send test event
```

#### Scalability

- **Server**: Requires a delivery worker (or set of workers) that reads from the event log, groups events by subscription, applies filters, batches, and POSTs to callback URLs. Failed deliveries need retry queues with exponential backoff. This is a significant operational component.
- **Client**: No polling loop needed — the client just exposes an HTTP endpoint. However, the client must handle authentication (validating `X-Epistola-Signature`), idempotency (deduplication of redelivered batches), and high availability of the callback endpoint.
- **Infrastructure**: The server now makes outbound HTTP requests, which introduces concerns around egress firewalls, DNS resolution, timeouts, and dead-letter queues for unreachable subscribers.

#### Delivery Guarantees

- **At-least-once**: Failed deliveries are retried with exponential backoff. The delivery ID allows deduplication.
- **Ordering**: Events within a batch are ordered. Batches themselves are delivered in order per subscription, but if a delivery fails and is retried, later batches may be held (head-of-line blocking) or delivered out of order depending on retry strategy.
- **Dead letters**: After N retries, the subscription is marked as `failing` and events go to a dead-letter queue. An alert mechanism notifies the subscriber.

#### Client/Server Complexity

| Aspect | Complexity |
|---|---|
| Server implementation | **Very high** — Subscription storage, delivery workers, retry queues, signature generation, dead-letter handling, subscription health monitoring |
| Client implementation | Medium — HTTP endpoint, signature validation, idempotency |
| Generated client compatibility | **Partial** — Subscription management CRUD works with generated client. But the callback receiver is on the consumer side and not part of the generated client. |
| Generated server compatibility | **Partial** — Subscription management endpoints generate normally. The outbound delivery worker is server-internal logic, not an API contract. |
| OpenAPI expressibility | **Partial** — Subscription CRUD endpoints are fully expressible. The callback delivery schema can be documented via OpenAPI 3.1 `webhooks` or `callbacks`, but code generators handle these inconsistently. |

#### Pros

- True push: consumers do not poll at all.
- Natural batching: server controls batch size and timing.
- Well-understood pattern (Stripe, GitHub, etc.).
- Subscription management is standard REST CRUD.

#### Cons

- **Highest server complexity** by a large margin: delivery workers, retry queues, dead letters, signature management.
- Consumer must expose a public HTTP endpoint, which is problematic for internal services, local development, and environments behind strict firewalls.
- Outbound HTTP from server introduces new failure modes (DNS, TLS, timeouts, unreachable hosts).
- Head-of-line blocking: one slow subscriber can back up event delivery (requires per-subscription isolation).
- Security surface: callback URL validation, SSRF prevention, secret rotation.
- Testing is difficult — requires tunneling (ngrok) or mock servers in CI.

---

### Option D: Polling with Event Log

#### How it works

The server exposes an append-only event log endpoint. Consumers poll it at a regular interval (e.g., every 5-10 seconds) using a cursor (sequence number). Each response returns a batch of events since the cursor.

This is the simplest option — no long-held connections, no push, just a regular paginated endpoint that happens to use cursor-based pagination instead of the offset-based pagination used elsewhere in the API.

#### API Shape

```
GET /tenants/{tenantId}/events
  ?after={sequenceNumber}      # cursor: last seen sequence number (0 = from beginning)
  &resourceType=version        # filter (repeatable)
  &templateId=invoice          # filter
  &limit=100                   # max events per batch (1-1000, default 100)

Accept: application/vnd.epistola.v1+json

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "events": [ ... ],
  "cursor": {
    "sequenceNumber": 4210,
    "hasMore": true
  }
}
```

When `hasMore` is `true`, the client should immediately request the next page. When `false`, the client waits for its configured poll interval before requesting again.

#### Scalability

- **Server**: Stateless — each request is an indexed database query. No held connections, no pub/sub. Horizontal scaling is trivial; any instance can serve any request.
- **Client**: Each client instance tracks its own cursor independently. Poll interval is client-controlled.
- **Database**: Same append-only `events` table as Option A. With proper indexing, the query `WHERE tenant_id = ? AND sequence_number > ? ORDER BY sequence_number LIMIT ?` is a simple index range scan.

#### Delivery Guarantees

- **At-least-once**: Same as Option A — client re-reads from last cursor on restart.
- **Ordering**: Sequence-number order, no gaps.
- **No data loss**: Events are durably stored. Retention window is server-configured.

#### Client/Server Complexity

| Aspect | Complexity |
|---|---|
| Server implementation | **Low** — Append-only table, single query endpoint, no background workers |
| Client implementation | **Low** — Timer + GET loop + cursor persistence |
| Generated client compatibility | **Full** — Regular GET endpoint, works perfectly with Spring RestClient |
| Generated server compatibility | **Full** — Standard controller method, fits generated interface pattern |
| OpenAPI expressibility | **Full** — Standard GET with query parameters and JSON response |

#### Pros

- Simplest to implement on both client and server.
- Full compatibility with OpenAPI code generation — no special handling needed.
- Stateless server: trivial horizontal scaling, no connection management.
- Uses `application/vnd.epistola.v1+json` content type like every other endpoint.
- Cursor-based catch-up works across restarts and outages.
- Easy to test, debug, and monitor (standard HTTP request/response).

#### Cons

- Higher latency: events are delayed by up to the poll interval (e.g., 5-10 seconds).
- More requests to the server compared to push-based approaches (though each is cheap).
- Client must implement a polling loop (trivial but still requires a timer/scheduler).
- No server feedback when events are available (unlike long polling, which returns immediately).

---

### Option E: gRPC Notification Channel + REST Event Log (Hybrid)

#### How it works

The REST event log from Option D remains the data plane — all event payloads, cursors, and delivery guarantees live there. A gRPC bidirectional streaming channel is added as an optional signaling layer. The gRPC channel carries lightweight notifications ("tenant X has new events up to sequence Y") but never event payloads.

Consumer flow:

1. **With gRPC available**: Consumer opens a bidirectional gRPC stream to receive notifications. On notification, it immediately fetches the REST event log from its last cursor. Between notifications it does nothing.
2. **Without gRPC (fallback)**: Consumer polls the REST event log on a timer, identical to Option D. No gRPC dependency.

The gRPC channel is explicitly best-effort. If the stream disconnects, the consumer falls back to polling until the stream reconnects. No events are lost because the REST event log is the source of truth.

#### API Shape

**REST endpoint (identical to Option D):**

```
GET /tenants/{tenantId}/events
  ?after={sequenceNumber}
  &resourceType=version
  &limit=100

Accept: application/vnd.epistola.v1+json

200 OK
Content-Type: application/vnd.epistola.v1+json

{
  "events": [ ... ],
  "cursor": {
    "sequenceNumber": 4210,
    "hasMore": false
  }
}
```

**gRPC notification service (protobuf):**

```protobuf
syntax = "proto3";

package epistola.notifications.v1;

option java_multiple_files = true;
option java_package = "com.epistola.notifications.v1";

service EventNotificationService {
  // Bidirectional stream: client subscribes, server pushes notifications
  rpc SubscribeToNotifications (stream ClientMessage) returns (stream ServerMessage);
}

// --- Client → Server ---

message ClientMessage {
  oneof message {
    Subscribe subscribe = 1;
    Heartbeat heartbeat = 2;
    Unsubscribe unsubscribe = 3;
  }
}

message Subscribe {
  string tenant_id = 1;
  repeated string resource_types = 2;  // empty = all types
}

message Heartbeat {
  // Sent periodically by the client to signal liveness
}

message Unsubscribe {
  string tenant_id = 1;
}

// --- Server → Client ---

message ServerMessage {
  oneof message {
    SubscriptionConfirmed subscription_confirmed = 1;
    EventNotification event_notification = 2;
    ServerHeartbeat server_heartbeat = 3;
  }
}

message SubscriptionConfirmed {
  string tenant_id = 1;
  int64 latest_sequence_number = 2;
}

message EventNotification {
  string tenant_id = 1;
  int64 latest_sequence_number = 2;
  repeated string affected_resource_types = 3;  // e.g., ["version", "activation"]
}

message ServerHeartbeat {
  // Sent periodically to keep the stream alive through load balancers
}
```

Notifications carry only three pieces of information: which tenant changed, the latest sequence number, and which resource types were affected. The consumer uses this to decide whether to fetch and what filters to apply. All actual event data comes from the REST endpoint.

#### Scalability

- **Server**: The REST event log is identical to Option D (stateless, indexed query). The gRPC layer adds a fan-out component: when an event is written, it must notify all connected consumers subscribed to that tenant. This requires an in-process pub/sub bus (single instance) or a distributed pub/sub layer (multi-instance, e.g., Redis Pub/Sub, PostgreSQL `LISTEN/NOTIFY`).
- **Client**: Each client instance opens one gRPC stream for all tenants it cares about (subscriptions are multiplexed over a single stream). If gRPC is unavailable, the client polls REST as in Option D.
- **Infrastructure**: Everything from Option D (database, REST server) plus a gRPC server (HTTP/2), a load balancer with HTTP/2 and streaming support, and optionally a pub/sub layer for multi-instance fan-out.

#### Delivery Guarantees

- **REST layer**: Identical to Option D — at-least-once, ordered, durable.
- **gRPC layer**: Best-effort. Notifications may be missed if the stream disconnects between server send and client receive. This is by design — the gRPC channel is an optimization, not a guarantee. Consumers that require guaranteed delivery use the REST poll interval as a safety net.
- **Combined**: At-least-once. The REST poll interval (e.g., every 60 seconds) acts as a fallback that catches anything the gRPC channel misses. The gRPC channel reduces the effective latency from the poll interval to near-zero for connected consumers.

#### Client/Server Complexity

| Aspect | Complexity |
|---|---|
| Server implementation | **High** — Option D's REST endpoint (low) + gRPC server, stream lifecycle management, fan-out layer |
| Client implementation | Medium — Option D's REST polling (low) + optional gRPC client, reconnection logic, fallback |
| Generated client compatibility | **Split** — REST client fully generated from OpenAPI. gRPC client generated from protobuf (separate artifact, separate generator). |
| Generated server compatibility | **Split** — REST server stubs fully generated from OpenAPI. gRPC service generated from protobuf (separate module). |
| OpenAPI expressibility | **Partial** — REST endpoint is fully expressed in OpenAPI. gRPC service is defined in protobuf, outside the OpenAPI spec. |

#### Pros

- **Very low latency when connected** — gRPC notifications arrive in ~10ms, triggering an immediate REST fetch.
- **Graceful degradation** — gRPC is optional. If unavailable (network, infrastructure, client library), the consumer falls back to Option D polling. No events are lost.
- **No schema drift** — gRPC carries only signaling (tenant ID, sequence number, resource types). All domain models live exclusively in OpenAPI. The protobuf contract is small and stable.
- **Consumer liveness** — unique to this option: the server knows which consumers are actively connected via heartbeats. This enables operational dashboards, alerting on disconnected consumers, and future features like consumer-specific backpressure.
- **REST event log remains source of truth** — all delivery guarantees, ordering, and durability come from the REST layer, which is already proven in Option D.

#### Cons

- **Two protocols** — consumers and server now speak both REST/HTTP and gRPC/HTTP2. Two sets of libraries, two deployment concerns, two sets of monitoring.
- **Two code generation pipelines** — OpenAPI Generator for REST (existing) and protoc/grpc-kotlin for gRPC (new module, new build configuration).
- **Infrastructure cost** — requires an HTTP/2-capable load balancer, a gRPC server process (can be co-located), and a pub/sub layer for multi-instance fan-out.
- **Marginal latency improvement over Option A** — Long polling (Option A) achieves ~100ms latency with zero new infrastructure. gRPC achieves ~10ms, but the 90ms difference is unlikely to matter for human-initiated document operations.
- **Operational complexity** — gRPC stream health monitoring, reconnection storms after server deploys, HTTP/2 load balancer configuration, protobuf versioning.

---

## 4. Comparison Matrix

| Criterion | A: Long Polling | B: SSE | C: Webhooks | D: Polling | E: gRPC + REST |
|---|---|---|---|---|---|
| **Event latency** | Low (~100ms) | Very low (~10ms) | Low-medium (batch delay) | Medium (poll interval) | Very low (~10ms) with gRPC; medium without |
| **Server complexity** | Medium | High | Very high | **Low** | High (REST low + gRPC fan-out) |
| **Client complexity** | Low | Medium | Medium | **Low** | Medium (REST low + optional gRPC) |
| **OpenAPI expressibility** | **Full** | Partial | Partial | **Full** | Partial (REST full, gRPC via protobuf) |
| **Generated client** | **Works** | **Broken** | Partial | **Works** | Split (REST works, gRPC separate artifact) |
| **Generated server** | **Works** | **Broken** | Partial | **Works** | Split (REST works, gRPC separate module) |
| **Horizontal scaling** | Good (virtual threads) | Good (with pub/sub) | Good (with workers) | **Excellent** (stateless) | Good (REST stateless, gRPC needs pub/sub) |
| **Delivery guarantee** | At-least-once | At-least-once | At-least-once | At-least-once | At-least-once (via REST) |
| **Event ordering** | Guaranteed | Guaranteed | Per-batch | Guaranteed | Guaranteed (via REST) |
| **Firewall friendly** | Yes | Mostly | Requires inbound | **Yes** | Mostly (gRPC needs HTTP/2) |
| **Infrastructure needs** | Database | Database + pub/sub | Database + workers + queues | **Database only** | Database + pub/sub + gRPC server + HTTP/2 LB |
| **Content type** | `vnd.epistola.v1` | `text/event-stream` | `vnd.epistola.v1` | `vnd.epistola.v1` | `vnd.epistola.v1` (REST) + protobuf (gRPC) |
| **Catchup from outage** | Built-in (cursor) | Built-in (Last-Event-ID) | Replay from dead letter | Built-in (cursor) | Built-in (cursor via REST) |
| **Observability** | Standard HTTP metrics | Custom SSE metrics | Delivery status tracking | **Standard HTTP metrics** | HTTP metrics (REST) + gRPC metrics |
| **Consumer liveness** | No | No | No | No | **Yes** (heartbeat-based) |

---

## 5. Recommendation

**Option D (Polling with Event Log)** as the initial implementation, with two designed-in upgrade paths for different latency requirements.

### Rationale

#### Contract-first fit

Epistola is a contract-first API where the OpenAPI spec is the single source of truth and all client/server code is generated from it. This is the project's defining architectural constraint.

Options B (SSE) and C (Webhooks) fundamentally conflict with this constraint:
- SSE requires content types and streaming semantics that OpenAPI generators cannot produce. Both the Kotlin client (Spring RestClient) and server stubs (Spring Boot 4 interfaces) would need manual, non-generated code.
- Webhooks require the consumer to implement a callback server, which is outside the generated client's scope. The webhook delivery mechanism itself is entirely server-internal and untestable via the contract.

Options A, D, and E's REST layer all fit the contract-first model. They use standard HTTP GET with JSON responses, work with the generated client, and can be expressed as regular OpenAPI endpoints. Option E's gRPC layer is defined separately in protobuf and does not interfere with the OpenAPI contract.

#### Complexity budget

Epistola is in its `0.1.x` phase. Introducing a delivery worker fleet (webhooks), a pub/sub layer (SSE), connection-holding semantics (long polling), or a gRPC server is premature. The event log approach requires:
- One database table.
- One new endpoint.
- Zero infrastructure beyond what already exists.

This is proportionate to the current project maturity.

#### Upgrade paths

The API shape for Options A and D is intentionally identical — same endpoint, same parameters, same response schema. Option E builds on D by adding a separate gRPC channel alongside the existing REST endpoint. This gives two independent upgrade paths from Option D:

**Path D → A (Long Polling) — Primary upgrade path**

1. Add the optional `timeout` query parameter to the existing event feed endpoint.
2. Existing clients continue to work (they just don't send `timeout`).
3. Clients that want lower latency opt in by adding `timeout=30`.
4. Achieves ~100ms latency with zero new infrastructure, protocols, or dependencies.
5. Fully backwards compatible, additive change.

**Path D → E (gRPC Hybrid) — Future consideration**

1. Add a `grpc-kotlin-protobuf/` module with the `EventNotificationService` protobuf contract.
2. Deploy a gRPC server alongside the existing REST API.
3. Existing REST consumers are unaffected — gRPC is purely additive.
4. Consumers that want ~10ms latency opt in by connecting to the gRPC notification stream.
5. Requires: new module, protobuf contract, fan-out layer, HTTP/2 load balancer.
6. Adds consumer liveness detection (heartbeat-based).

#### Why not jump to Option E?

The 90ms latency difference between Option A (~100ms) and Option E (~10ms) does not justify the infrastructure cost for a `0.1.x` document template platform where events are human-initiated. Option A achieves sub-second latency by adding a single query parameter — no new protocols, no new build pipelines, no new load balancer requirements.

Option E becomes justified when Epistola needs capabilities that long polling genuinely cannot serve:
- **Real-time collaboration** — multiple users editing templates simultaneously need sub-50ms notification of each other's changes.
- **Presence and liveness** — knowing which consumers are actively connected (heartbeat-based) for operational dashboards or consumer-specific backpressure.
- **Streaming progress** — document generation progress updates where polling overhead becomes a bottleneck at scale.

Until those requirements emerge, Option A covers the latency needs with dramatically less complexity.

#### Latency is acceptable

For Epistola's current use-cases (cache invalidation, workflow orchestration, audit), a 5-10 second delay is entirely acceptable. Template publishes and activation changes are human-initiated operations that happen at most a few times per hour. When sub-second latency becomes a requirement, the long-polling upgrade (Path D → A) addresses it without breaking existing consumers. If true real-time needs emerge beyond that, the gRPC upgrade (Path D → E) is available.

### Implementation outline (not part of this design — for future planning)

1. Add `EventEnvelope` and `EventFeedResponse` schemas to `spec/components/schemas/events.yaml`.
2. Add `spec/paths/events.yaml` with the event feed endpoint.
3. Reference both from `epistola-api.yaml`.
4. Add an `Events` tag.
5. Run `make lint && make build` to verify generation.
6. Server implementation: append events to a database table on each mutating operation, expose the query endpoint.

---

## Appendix A: Cursor-Based vs Offset-Based Pagination

The existing API uses offset-based pagination (`page` + `size` + `totalElements` + `totalPages`). The event feed uses cursor-based pagination (`after` + `limit` + `hasMore`). This is intentional:

- **Offset pagination** is appropriate for relatively stable datasets (template lists, version lists) where items are rarely inserted or deleted while paginating.
- **Cursor pagination** is required for append-only logs where new entries are continuously added. Offset-based pagination over a growing log would cause items to shift between pages, leading to duplicates or missed events.

The two patterns coexist in the API. The event feed's cursor-based response uses a different shape (`cursor.sequenceNumber` + `cursor.hasMore`) from the offset-based shape (`page` + `size` + `totalElements` + `totalPages`) to make the distinction explicit.

## Appendix B: Event Retention and Purging

Events should be retained for a configurable window (recommended default: 7 days). Consumers that have not polled within the retention window will receive a `410 Gone` response with the earliest available sequence number, allowing them to reset their cursor and catch up from the oldest available event.

```
GET /tenants/{tenantId}/events?after=100

410 Gone
{
  "code": "CURSOR_EXPIRED",
  "message": "Requested cursor is older than the retention window",
  "earliestAvailable": 5000
}
```

## Appendix C: Role Requirements

The event feed endpoint should require the `reader` role (or higher). Events may contain resource state in their `data` field, so access should be gated by the same authorization rules that apply to reading those resources.

Suggested `x-required-roles`: `[reader, editor, generator, manager]`

This matches the pattern used by other read endpoints (e.g., `listVersions`, `listGenerationJobs`).

## Appendix D: Protobuf Contract and OpenAPI Spec Relationship

If Option E is adopted, the project would maintain two interface definition languages (IDLs): OpenAPI for the REST API and protobuf for the gRPC notification channel. This appendix describes how they coexist without overlap or drift.

### Scope separation

| Concern | IDL | Scope |
|---|---|---|
| Domain models (DTOs, requests, responses) | OpenAPI | All event payloads, resource schemas, error responses |
| Event delivery (event log endpoint) | OpenAPI | `GET /tenants/{tenantId}/events` — full event data |
| Notification signaling | Protobuf | `EventNotificationService` — tenant ID, sequence number, affected types only |

The protobuf contract contains **no domain models**. It defines only the signaling messages (`Subscribe`, `EventNotification`, `Heartbeat`, etc.) which carry identifiers and metadata, never event payloads. This means:

- Changes to the OpenAPI schema (new event types, new fields on DTOs) require **no changes** to the protobuf contract.
- Changes to the protobuf contract (new signaling fields, new message types) require **no changes** to the OpenAPI spec.
- There is no shared model that could drift between the two IDLs.

### Code generation

| Aspect | OpenAPI (existing) | Protobuf (Option E) |
|---|---|---|
| **IDL file** | `epistola-api.yaml` | `proto/epistola/notifications/v1/notifications.proto` |
| **Generator** | OpenAPI Generator | protoc + grpc-kotlin |
| **Client artifact** | `client-kotlin-spring-restclient` | `grpc-kotlin-protobuf` (new module) |
| **Server artifact** | `server-kotlin-springboot4` | `grpc-kotlin-protobuf` (shared) |
| **Generated output** | REST client/server interfaces + DTOs | gRPC stubs + protobuf message classes |
| **Build tool** | Gradle + OpenAPI Generator plugin | Gradle + protobuf plugin |

### Versioning independence

The OpenAPI spec and protobuf contract are versioned independently:

- **OpenAPI**: Versioned via `info.version` in `epistola-api.yaml` and the `application/vnd.epistola.v1+json` media type. Follows the existing release process.
- **Protobuf**: Versioned via the package name (`epistola.notifications.v1`). New versions introduce a new package (`v2`) rather than modifying the existing one.

Because the protobuf contract is small and stable (only signaling messages), it is expected to change rarely compared to the OpenAPI spec.

### Build integration

If added, the protobuf module would be a new Gradle subproject:

```
epistola-contract/
├── client-kotlin-spring-restclient/    # OpenAPI → Kotlin client
├── server-kotlin-springboot4/          # OpenAPI → Spring server stubs
├── grpc-kotlin-protobuf/               # Protobuf → gRPC stubs (new)
│   ├── build.gradle.kts
│   └── src/main/proto/
│       └── epistola/notifications/v1/
│           └── notifications.proto
├── epistola-api.yaml
└── Makefile
```

The module would produce a single artifact containing both client stubs and server interfaces, published to Maven Central alongside the existing artifacts. Consumers that want gRPC notification support add the dependency; those that don't simply ignore it.
