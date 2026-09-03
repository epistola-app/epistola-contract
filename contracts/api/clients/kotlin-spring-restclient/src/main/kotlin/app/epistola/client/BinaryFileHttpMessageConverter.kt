// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client

import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import java.io.File

/**
 * Reads a binary response body into a [File].
 *
 * Every operation the contract declares as `format: binary` — downloading a document, rendering a
 * preview, fetching an asset's content — is generated as returning `java.io.File`, and Spring ships
 * no converter that can produce one. Without this, `downloadDocument` fails with
 * `UnknownContentTypeException: no suitable HttpMessageConverter found for response type
 * [class java.io.File] and content type [application/pdf]`, whatever converters the consumer
 * configures — with a plain `RestClient.builder()`, and with the generated convenience constructor
 * alike.
 *
 * This is specific to the *generated* methods. Fetching the same document with a hand-written call
 * (`restClient.get().uri(…).retrieve().body(ByteArray::class.java)`) has always worked, because
 * Spring converts byte arrays out of the box — so a consumer who wrote their own download never
 * met this, and one who called the generated method could not get past it.
 *
 * The body is streamed to a temporary file rather than buffered: documents are the largest thing
 * this client moves, and the generated signature hands back a [File] precisely so the caller need
 * not hold one in memory. **The caller owns that file and should delete it when finished**;
 * [File.deleteOnExit] is registered as a backstop for short-lived processes, not as a substitute.
 *
 * Reading only. Uploads are multipart and the generated code builds those itself.
 */
class BinaryFileHttpMessageConverter : HttpMessageConverter<File> {

    /**
     * Keyed on the target type, not the media type: this converter takes over only where the caller
     * asked for a [File], so it cannot shadow the JSON converters for anything else.
     */
    override fun canRead(clazz: Class<*>, mediaType: MediaType?): Boolean = File::class.java == clazz

    override fun canWrite(clazz: Class<*>, mediaType: MediaType?): Boolean = false

    override fun getSupportedMediaTypes(): List<MediaType> = listOf(MediaType.ALL)

    override fun read(clazz: Class<out File>, inputMessage: HttpInputMessage): File {
        val file = File.createTempFile("epistola-", ".download")
        file.deleteOnExit()
        file.outputStream().use { output -> inputMessage.body.copyTo(output) }
        return file
    }

    override fun write(t: File, contentType: MediaType?, outputMessage: HttpOutputMessage): Nothing = throw UnsupportedOperationException("BinaryFileHttpMessageConverter reads responses only")
}
