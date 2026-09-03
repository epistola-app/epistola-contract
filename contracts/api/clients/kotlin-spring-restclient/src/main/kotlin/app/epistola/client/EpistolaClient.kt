// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client

import app.epistola.client.auth.ApiKeyAuth
import app.epistola.client.auth.JwtSigner
import app.epistola.client.error.installProblemDetailHandler
import app.epistola.client.identity.ClientIdentity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * The blessed setup: identity headers, JSON configuration, RFC 9457 problem parsing, and API-key
 * or self-signed-JWT authentication, assembled in one call instead of four.
 *
 * The pieces this assembles — [ClientIdentity], [ApiKeyAuth], [JwtSigner],
 * [epistolaMessageConverters], [installProblemDetailHandler] — stay independently usable and
 * independently documented; nothing here replaces them, and building a `RestClient` by hand from
 * them remains entirely supported. This exists because assembling them by hand is exactly where a
 * piece goes missing without anything failing loudly: wiring [epistolaMessageConverters] without
 * also calling [installProblemDetailHandler] compiles, runs, and every error response silently
 * comes back as a raw `RestClientResponseException` instead of a [app.epistola.client.error.ProblemDetailException] —
 * no exception, no log line, just a `catch` block that quietly stops matching. [builder] installs
 * both, every time, because there is no configuration in which a consumer of this entry point wants
 * only one of them.
 *
 * ```kotlin
 * val restClient = EpistolaClient.builder("https://epistola.example.com/api")
 *     .apiKey("epk_...")                              // or .jwtSigner(signer)
 *     .identity(ClientIdentity.builder().nodeId("pod-1").build())
 *     .build()
 *
 * val templatesApi = TemplatesApi(restClient)
 * ```
 *
 * ### Two timeout profiles from one builder
 *
 * A [Builder] holds the shared configuration — base URL, auth, identity, connect timeout — and
 * [Builder.build] can be called more than once, with a different [Builder.readTimeout] set in
 * between, for the two profiles a long-running consumer typically needs against the same backend:
 *
 * ```kotlin
 * val builder = EpistolaClient.builder(baseUrl).apiKey(apiKey).identity(identity)
 *
 * // Result-collector polling, preview rendering, catalog import: no read timeout at all, because
 * // a slow render or a large transfer is not a failure and should not be cut off mid-flight.
 * val longRunning = builder.readTimeout(null).build()
 *
 * // Everything else: bounded, so a wedged connection surfaces as an error instead of hanging.
 * val shortCalls = builder.readTimeout(Duration.ofSeconds(30)).build()
 * ```
 */
object EpistolaClient {

    /** A [Builder] with no base URL yet — call [Builder.baseUrl] before [Builder.build]. */
    fun builder(): Builder = Builder()

    /** A [Builder] with [baseUrl] already set. */
    fun builder(baseUrl: String): Builder = Builder().baseUrl(baseUrl)

    /** A [Builder] with [baseUrl] and a static [apiKey] already set — the common case in one call. */
    fun builder(baseUrl: String, apiKey: String): Builder = Builder().baseUrl(baseUrl).apiKey(apiKey)

    class Builder internal constructor() {
        private var baseUrl: String? = null
        private var identity: ClientIdentity? = null
        private var apiKey: String? = null
        private var jwtSigner: JwtSigner? = null
        private var connectTimeout: Duration = Duration.ofSeconds(10)
        private var readTimeout: Duration? = Duration.ofSeconds(30)

        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }

        /**
         * `User-Agent` + `X-EP-Node-Id`. Every request the contract accepts requires both; see
         * [ClientIdentity] for what happens if this is left unset.
         */
        fun identity(identity: ClientIdentity) = apply { this.identity = identity }

        /** `Authorization: ApiKey <key>`. Mutually exclusive with [jwtSigner] — whichever is called last wins. */
        fun apiKey(apiKey: String) = apply {
            this.apiKey = apiKey
            this.jwtSigner = null
        }

        /** `Authorization: Bearer <jwt>`, minted fresh per request. Mutually exclusive with [apiKey]. */
        fun jwtSigner(jwtSigner: JwtSigner) = apply {
            this.jwtSigner = jwtSigner
            this.apiKey = null
        }

        /** Time to establish the TCP connection (default 10s). Enforced in both timeout profiles. */
        fun connectTimeout(connectTimeout: Duration) = apply { this.connectTimeout = connectTimeout }

        /**
         * Time to wait for the response once the request is sent (default 30s). Pass `null` for no
         * read timeout at all — the JDK's own default, which is to wait indefinitely — for a
         * long-running operation (polling, rendering, a large transfer) that a fixed timeout would
         * cut off mid-flight rather than fail.
         */
        fun readTimeout(readTimeout: Duration?) = apply { this.readTimeout = readTimeout }

        /**
         * Builds a [RestClient] from the configuration so far. Safe to call more than once on the
         * same [Builder] — each call reads the builder's current state into an independent client,
         * which is what makes the two-timeout-profile pattern above work from one builder.
         */
        fun build(): RestClient {
            val url = requireNotNull(baseUrl) { "baseUrl is required" }

            // Not SimpleClientHttpRequestFactory: it wraps java.net.HttpURLConnection, which
            // rejects PATCH outright with "Invalid HTTP method: PATCH" — every one of the
            // contract's thirteen PATCH operations, including updateConsumer, would fail before
            // a request left the process. java.net.http.HttpClient has no such restriction.
            val httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
                readTimeout?.let { setReadTimeout(it) }
            }

            val restClientBuilder = RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory)
                .epistolaMessageConverters()
                .installProblemDetailHandler()

            identity?.let { restClientBuilder.requestInterceptor(it.interceptor()) }
            val signer = jwtSigner
            val key = apiKey
            when {
                signer != null -> restClientBuilder.requestInterceptor(signer.interceptor())
                key != null -> restClientBuilder.requestInterceptor(ApiKeyAuth.of(key).interceptor())
            }

            return restClientBuilder.build()
        }
    }
}
