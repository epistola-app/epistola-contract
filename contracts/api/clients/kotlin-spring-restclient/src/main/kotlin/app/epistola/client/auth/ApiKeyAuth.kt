// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.auth

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor

/**
 * Static API-key authentication helper.
 *
 * Sends the key as `Authorization: ApiKey <key>`, the only way the API accepts an API key.
 */
class ApiKeyAuth private constructor(
    private val apiKey: String,
) {
    companion object {
        fun of(apiKey: String): ApiKeyAuth = ApiKeyAuth(requireApiKey(apiKey))

        private fun requireApiKey(apiKey: String): String {
            val trimmed = apiKey.trim()
            require(trimmed.isNotEmpty()) { "apiKey must not be blank" }
            return trimmed
        }
    }

    fun interceptor(): ClientHttpRequestInterceptor = ApiKeyAuthInterceptor(apiKey)

    private class ApiKeyAuthInterceptor(
        private val apiKey: String,
    ) : ClientHttpRequestInterceptor {
        override fun intercept(
            request: HttpRequest,
            body: ByteArray,
            execution: ClientHttpRequestExecution,
        ) = execution.execute(
            request.apply {
                headers.set(HttpHeaders.AUTHORIZATION, "ApiKey $apiKey")
            },
            body,
        )
    }
}
