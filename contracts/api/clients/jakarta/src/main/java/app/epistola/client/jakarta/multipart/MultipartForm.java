// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.multipart;

import jakarta.ws.rs.core.EntityPart;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the parts of a {@code multipart/form-data} request body.
 *
 * <p>The upload operations come in two shapes. The generated convenience method takes one argument
 * per form field and calls this; the method it delegates to takes {@code List<EntityPart>} directly,
 * for content that is not a file on disk or a media type this class cannot infer.
 *
 * <pre>{@code
 * imagesApi.uploadImage("acme-corp", "main", MultipartForm.create()
 *         .file("file", "logo.png", "image/png", bytes)
 *         .field("name", "Logo")
 *         .build());
 * }</pre>
 *
 * <p>A {@code null} value is left out, so an optional field the caller did not set is absent from
 * the body rather than sent empty or as the text {@code "null"}.
 *
 * <p>Building parts needs a Jakarta REST 3.1 implementation on the classpath, which every Jakarta EE
 * 10 server provides. Outside one, add a multipart provider — see the README.
 */
public final class MultipartForm {

    /**
     * Media types for the file extensions the contract's upload operations accept, so that what the
     * client declares does not depend on the JDK it runs on.
     *
     * <p>{@link URLConnection#guessContentTypeFromName} is the obvious answer and is the fallback
     * below, but its table grew over time: Java 17 has no entry for {@code .webp}, so a WebP image
     * would upload as {@code application/octet-stream} there and be rejected as not an image, while
     * the same code on Java 21 uploaded it correctly.
     */
    private static final Map<String, String> CONTRACT_MEDIA_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "svg", "image/svg+xml",
            "webp", "image/webp",
            "zip", "application/zip");

    private static final String FALLBACK_MEDIA_TYPE = "application/octet-stream";

    private final List<EntityPart> parts = new ArrayList<>();

    /** A new, empty form. */
    public static MultipartForm create() {
        return new MultipartForm();
    }

    /**
     * Adds {@code file} as a file part, named after the file itself and with the media type its
     * extension implies. A null file adds nothing.
     */
    public MultipartForm file(String name, File file) {
        if (file != null) {
            add(EntityPart.withName(name)
                    .fileName(file.getName())
                    .mediaType(mediaTypeOf(file.getName()))
                    .content(file));
        }
        return this;
    }

    /** Adds {@code content} as a file part with the filename and media type given. */
    public MultipartForm file(String name, String fileName, String mediaType, byte[] content) {
        if (content != null) {
            add(EntityPart.withName(name).fileName(fileName).mediaType(mediaType).content(content));
        }
        return this;
    }

    /**
     * Adds the stream as a file part with the filename and media type given. The stream is read when
     * the request is sent, and closed by the caller.
     */
    public MultipartForm file(String name, String fileName, String mediaType, InputStream content) {
        if (content != null) {
            add(EntityPart.withName(name).fileName(fileName).mediaType(mediaType).content(content));
        }
        return this;
    }

    /** Adds a text field carrying {@code value.toString()}. A null value adds nothing. */
    public MultipartForm field(String name, Object value) {
        if (value != null) {
            add(EntityPart.withName(name).content(String.valueOf(value)));
        }
        return this;
    }

    /** The parts added so far, in the order they were added. */
    public List<EntityPart> build() {
        return List.copyOf(parts);
    }

    /** The media type a filename implies, falling back to {@code application/octet-stream}. */
    static String mediaTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot == -1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String declared = CONTRACT_MEDIA_TYPES.get(extension);
        if (declared != null) {
            return declared;
        }
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed != null ? guessed : FALLBACK_MEDIA_TYPE;
    }

    private void add(EntityPart.Builder part) {
        try {
            parts.add(part.build());
        } catch (IOException e) {
            throw new UncheckedIOException("could not build the multipart request body", e);
        }
    }

    private MultipartForm() {
    }
}
