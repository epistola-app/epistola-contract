// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.multipart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.EntityPart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shape of the parts an upload sends.
 *
 * <p>What a server makes of an upload is decided here: a part without a filename is a form field
 * rather than a file, and the media type is what tells the server whether it was sent an image.
 *
 * <p>These assert on the parts as built. The bytes that leave the client are asserted on the wire
 * by the {@code image-upload} conformance scenario instead — RESTEasy's {@code EntityPart} reads its
 * content only inside a request, and the scenario holds all five clients to one answer anyway.
 */
class MultipartFormTest {

    @TempDir
    Path directory;

    @Test
    void a_file_part_carries_its_filename_and_the_media_type_its_extension_implies() throws IOException {
        Path file = Files.write(directory.resolve("logo.png"), new byte[] {1, 2, 3});

        List<EntityPart> parts = MultipartForm.create().file("file", file.toFile()).build();

        assertEquals(1, parts.size());
        EntityPart part = parts.get(0);
        assertEquals("file", part.getName());
        assertEquals("logo.png", part.getFileName().orElse(null));
        assertEquals("image/png", part.getMediaType().toString());
    }

    @Test
    void in_memory_content_takes_the_filename_and_media_type_it_is_given() {
        EntityPart part = MultipartForm.create()
                .file("file", "mark.svg", "image/svg+xml", new byte[] {1, 2, 3})
                .build()
                .get(0);

        assertEquals("mark.svg", part.getFileName().orElse(null));
        assertEquals("image/svg+xml", part.getMediaType().toString());
    }

    @Test
    void a_field_is_a_text_part_and_not_a_file() {
        List<EntityPart> parts = MultipartForm.create()
                .field("name", "Municipality mark")
                .field("sensitive", false)
                .build();

        assertEquals(List.of("name", "sensitive"), parts.stream().map(EntityPart::getName).toList());
        assertTrue(parts.get(0).getFileName().isEmpty(), "a text field must not look like a file part");
        assertTrue(parts.get(1).getFileName().isEmpty(), "a text field must not look like a file part");
    }

    @Test
    void a_null_value_is_left_out_rather_than_sent_empty() {
        List<EntityPart> parts = MultipartForm.create()
                .file("file", (java.io.File) null)
                .field("name", null)
                .field("mediaType", null)
                .build();

        assertTrue(parts.isEmpty(), "an unset optional field must not reach the wire at all");
    }

    /**
     * The JDK's own table is not enough: Java 17 has no entry for {@code .webp}, so relying on it
     * would upload a WebP image as {@code application/octet-stream} there and have the server reject
     * it as not an image, while the same code worked on a newer JDK.
     */
    @Test
    void the_media_type_of_every_uploadable_type_is_known_on_every_jdk() {
        Map.of(
                        "logo.png", "image/png",
                        "photo.jpg", "image/jpeg",
                        "photo.jpeg", "image/jpeg",
                        "mark.svg", "image/svg+xml",
                        "picture.webp", "image/webp",
                        "catalog.zip", "application/zip",
                        "LOGO.PNG", "image/png")
                .forEach((fileName, mediaType) -> assertEquals(mediaType, MultipartForm.mediaTypeOf(fileName), fileName));
    }

    @Test
    void an_unknown_extension_falls_back_to_octet_stream() {
        assertEquals("application/octet-stream", MultipartForm.mediaTypeOf("mystery.qqq"));
        assertEquals("application/octet-stream", MultipartForm.mediaTypeOf("no-extension"));
    }

    @Test
    void a_file_with_an_unknown_extension_still_becomes_a_file_part() throws IOException {
        Path file = Files.write(directory.resolve("archive.qqq"), new byte[] {7});

        EntityPart part = MultipartForm.create().file("file", file.toFile()).build().get(0);

        assertEquals("archive.qqq", part.getFileName().orElse(null));
        assertEquals("application/octet-stream", part.getMediaType().toString());
    }
}
