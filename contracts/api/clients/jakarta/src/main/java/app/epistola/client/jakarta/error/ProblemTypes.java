// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import java.net.URI;

/**
 * Constants and helpers describing the Epistola RFC 9457 problem {@code type} URI scheme.
 *
 * <p>Intentionally duplicated from the server module's {@code app.epistola.api.error.ProblemDetails}
 * (the {@code TYPE_BASE} constant): the client is published as a standalone artifact and must not
 * depend on the server module. {@code ProblemRegistryTest} holds it against the value generated
 * from the spec, so the two cannot silently drift.
 *
 * <p>The machine-readable discriminator is the problem {@code type} URI — there is no separate
 * {@code code} member. Application-level errors use a {@code https://epistola.app/errors/{slug}}
 * type; framework errors keep RFC 9457's default {@code about:blank}.
 */
public final class ProblemTypes {

    /** Base URI for Epistola problem {@code type} values, e.g. {@code https://epistola.app/errors/not-found}. */
    public static final String TYPE_BASE = "https://epistola.app/errors/";

    /** The RFC 9457 default problem type, used when no specific type is supplied. */
    public static final URI BLANK_TYPE = URI.create("about:blank");

    /**
     * Extracts the kebab-case slug from an Epistola problem {@code type} URI (the part after
     * {@link #TYPE_BASE}), or {@code null} when {@code type} is {@code about:blank}, any
     * non-Epistola URI, or {@code null}.
     */
    public static String slugFor(URI type) {
        if (type == null) {
            return null;
        }
        String value = type.toString();
        if (!value.startsWith(TYPE_BASE)) {
            return null;
        }
        String slug = value.substring(TYPE_BASE.length());
        return slug.isEmpty() ? null : slug;
    }

    private ProblemTypes() {
    }
}

// KnownProblemSlugs is generated from the spec's x-problem-types extension by the
// generateProblemSlugs Gradle task (build/generated-problem-slugs). It lives in this
// package; see ProblemRegistryTest for the guard that keeps it aligned with TYPE_BASE.
