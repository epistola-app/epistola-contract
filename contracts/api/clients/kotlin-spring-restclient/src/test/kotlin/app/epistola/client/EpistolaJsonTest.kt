// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client

import app.epistola.client.model.GenerateDocumentRequest
import app.epistola.client.model.UpdateConsumerRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpistolaJsonTest {

    @Test
    fun `a property the caller never set is omitted, not written as null`() {
        // attributes is typed `array` in the contract with no null in the union, so writing it as
        // null is a request no server validating against the spec accepts.
        val json = EpistolaJson.objectMapper.writeValueAsString(
            GenerateDocumentRequest(catalogId = "default", templateId = "invoice", data = mapOf("x" to 1)),
        )

        assertFalse(json.contains("attributes"), json)
        assertFalse(json.contains("null"), json)
        assertTrue(json.contains("\"catalogId\":\"default\""), json)
    }

    @Test
    fun `a partial update carries only the field it set`() {
        // description and contact are documented "null to clear" — writing them as null turns a
        // rename into a rename plus an erase.
        val json = EpistolaJson.objectMapper.writeValueAsString(UpdateConsumerRequest(name = "Billing Service"))

        assertEquals("""{"name":"Billing Service"}""", json)
    }

    @Test
    fun `the converter reads problem bodies as well as the vendor type`() {
        val supported = EpistolaJson.epistolaMessageConverter().supportedMediaTypes.map { it.toString() }

        assertTrue(supported.contains(ContractMediaTypes.VENDOR_JSON), "$supported")
        assertTrue(supported.contains("application/problem+json"), "$supported")
    }
}
