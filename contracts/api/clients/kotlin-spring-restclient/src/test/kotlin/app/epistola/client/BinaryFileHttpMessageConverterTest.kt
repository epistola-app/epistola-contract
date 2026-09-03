// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.MediaType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinaryFileHttpMessageConverterTest {

    private val converter = BinaryFileHttpMessageConverter()

    private fun message(body: ByteArray) = object : HttpInputMessage {
        override fun getBody(): InputStream = ByteArrayInputStream(body)
        override fun getHeaders(): HttpHeaders = HttpHeaders().apply { contentType = MediaType.APPLICATION_PDF }
    }

    @Test
    fun `every byte survives the round trip`() {
        // Not valid UTF-8 anywhere above 0x7F: a converter that went via a String would mangle it.
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(256) { it.toByte() } + "\n%%EOF\n".toByteArray()

        val file = converter.read(File::class.java, message(pdf))

        assertContentEquals(pdf, file.readBytes())
        file.delete()
    }

    @Test
    fun `an empty body is a file, not a failure`() {
        val file = converter.read(File::class.java, message(ByteArray(0)))

        assertTrue(file.exists())
        assertContentEquals(ByteArray(0), file.readBytes())
        file.delete()
    }

    @Test
    fun `it claims File responses and nothing else`() {
        // Keyed on the target type so it cannot shadow the JSON converters.
        assertTrue(converter.canRead(File::class.java, MediaType.APPLICATION_PDF))
        assertTrue(converter.canRead(File::class.java, MediaType.APPLICATION_OCTET_STREAM))
        assertFalse(converter.canRead(String::class.java, MediaType.APPLICATION_PDF))
        assertFalse(converter.canWrite(File::class.java, MediaType.APPLICATION_PDF))
    }
}
