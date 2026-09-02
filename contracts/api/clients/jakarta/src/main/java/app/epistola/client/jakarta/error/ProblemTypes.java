// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import app.epistola.protocol.ProblemTypeUris;
import java.net.URI;

/**
 * Constants and helpers describing the Epistola RFC 9457 problem {@code type} URI scheme.
 *
 * <p>The base is generated from the spec's {@code x-problem-types} registry and the conversion
 * comes from {@code protocol-java}, so this client and the server module share one implementation
 * of a mapping they use in opposite directions.
 *
 * <p>The machine-readable discriminator is the problem {@code type} URI — there is no separate
 * {@code code} member. Application-level errors use a {@code https://epistola.app/errors/{slug}}
 * type; framework errors keep RFC 9457's default {@code about:blank}.
 */
public final class ProblemTypes {

    /**
     * Base URI for Epistola problem {@code type} values, e.g.
     * {@code https://epistola.app/errors/not-found}. Generated from the spec's
     * {@code x-problem-types} registry.
     */
    public static final String TYPE_BASE = KnownProblemSlugs.GENERATED_PROBLEM_TYPE_BASE;

    /** The RFC 9457 default problem type, used when no specific type is supplied. */
    public static final URI BLANK_TYPE = ProblemTypeUris.BLANK_TYPE;

    // The conversion itself is shared with the Kotlin client and the server stubs; only the base
    // differs per module, and that is generated.
    private static final ProblemTypeUris URIS = ProblemTypeUris.of(TYPE_BASE);

    /**
     * Extracts the kebab-case slug from an Epistola problem {@code type} URI (the part after
     * {@link #TYPE_BASE}), or {@code null} when {@code type} is {@code about:blank}, any
     * non-Epistola URI, or {@code null}.
     */
    public static String slugFor(URI type) {
        return URIS.slugFor(type);
    }

    private ProblemTypes() {
    }
}

// KnownProblemSlugs is generated from the spec's x-problem-types extension by the
// generateProblemSlugs Gradle task (build/generated-problem-slugs). It lives in this
// package; see ProblemRegistryTest for the guard that keeps it aligned with TYPE_BASE.
