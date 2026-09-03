// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient

/**
 * The Jackson configuration this client's payloads require, and the message converter that applies
 * it.
 *
 * A default [ObjectMapper] writes `null` for every property a request object left unset. That is
 * harmless for a field the contract types `[string, "null"]`, and a contract violation for one it
 * types `array` — `GenerateDocumentRequest.attributes` is the latter, so a request that simply did
 * not select variants by attribute was rejected by any server validating against the spec. Nothing
 * in the client caused it; it was whatever mapper the consumer happened to wire in.
 *
 * [epistolaMessageConverters] also installs [BinaryFileHttpMessageConverter], without which every
 * binary download fails outright.
 *
 * Use [epistolaMessageConverter] when building the `RestClient`:
 *
 * ```
 * val restClient = RestClient.builder()
 *     .baseUrl("https://epistola.example.com/api")
 *     .messageConverters { it.add(EpistolaJson.epistolaMessageConverter()) }
 *     .requestInterceptor(identity.interceptor())
 *     .build()
 * ```
 */
object EpistolaJson {

    /**
     * The shared, thread-safe [ObjectMapper]: Kotlin and `java.time` support, and — the part that
     * matters on the wire — properties left unset are omitted rather than written as `null`.
     */
    val objectMapper: ObjectMapper by lazy {
        jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }
    }

    /**
     * A converter bound to [objectMapper] and to the contract's media types, including
     * `application/problem+json` so that error bodies are read by the same mapper.
     */
    fun epistolaMessageConverter(): MappingJackson2HttpMessageConverter = MappingJackson2HttpMessageConverter(objectMapper).apply {
        supportedMediaTypes = listOf(
            MediaType.parseMediaType(ContractMediaTypes.VENDOR_JSON),
            MediaType.APPLICATION_JSON,
            MediaType.APPLICATION_PROBLEM_JSON,
        )
    }
}

/**
 * Installs [EpistolaJson.epistolaMessageConverter] on this builder, replacing the Jackson converter
 * Spring configures by default. Prefer this over adding a bare
 * `MappingJackson2HttpMessageConverter`: that one writes unset properties as `null`, which the
 * contract does not allow for every field.
 */
fun RestClient.Builder.epistolaMessageConverters(): RestClient.Builder = messageConverters { converters ->
    converters.removeIf { it is MappingJackson2HttpMessageConverter }
    converters.add(EpistolaJson.epistolaMessageConverter())
    // Spring has no converter that produces a java.io.File, which is what every `format: binary`
    // operation is generated as returning.
    converters.add(BinaryFileHttpMessageConverter())
}
