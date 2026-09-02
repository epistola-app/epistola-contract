// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * Converts between an Epistola problem {@code type} URI and its kebab-case slug.
 *
 * <p>The contract's machine-readable discriminator is the {@code type} URI — there is no separate
 * {@code code} member. Application-level errors use a {@code <base><slug>} URI; framework errors
 * keep RFC 9457's default {@code about:blank}.
 *
 * <p>The base itself is generated per module from the spec's {@code x-problem-types} registry, so
 * it is supplied rather than hard-coded here: this class owns the conversion, not the value. The
 * clients derive slugs from responses and the server builds URIs from slugs, which are the two
 * directions of one mapping.
 */
public final class ProblemTypeUris {

    /** The RFC 9457 default problem type, used when no specific type applies. */
    public static final URI BLANK_TYPE = URI.create("about:blank");

    private final String typeBase;

    private ProblemTypeUris(String typeBase) {
        this.typeBase = typeBase;
    }

    /**
     * @param typeBase the base URI problem types are built on, e.g.
     *                 {@code https://epistola.app/errors/}
     */
    public static ProblemTypeUris of(String typeBase) {
        if (typeBase == null || typeBase.isBlank()) {
            throw new IllegalArgumentException("typeBase must not be blank");
        }
        return new ProblemTypeUris(typeBase);
    }

    /** The base URI problem types are built on. */
    public String typeBase() {
        return typeBase;
    }

    /** Builds a problem {@code type} URI from a kebab-case slug. */
    public URI typeFor(String slug) {
        return URI.create(typeBase + slug);
    }

    /**
     * The kebab-case slug of an Epistola problem {@code type} URI (the part after the base), or
     * {@code null} when {@code type} is {@code about:blank}, any non-Epistola URI, or {@code null}.
     *
     * <p>Callers switch on the result, so it stays a plain nullable {@code String} rather than an
     * enum: the API can introduce problem types without forcing a client release.
     */
    public @Nullable String slugFor(@Nullable URI type) {
        if (type == null) {
            return null;
        }
        String value = type.toString();
        if (!value.startsWith(typeBase)) {
            return null;
        }
        String slug = value.substring(typeBase.length());
        return slug.isEmpty() ? null : slug;
    }
}
