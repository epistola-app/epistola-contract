// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

/**
 * The shared {@link Jsonb} the hand-written helpers use to read and write Epistola payloads
 * outside the JAX-RS entity providers — problem bodies in the exception mapper, NDJSON lines in
 * {@code ResultCollector}.
 *
 * <p>One instance for the life of the classloader: {@link JsonbBuilder#create()} builds a
 * reflection cache per instance, so creating one per response is the difference between a cheap
 * parse and an expensive one. {@link Jsonb} is documented as thread-safe.
 *
 * <p>The provider comes from the application server (Yasson on WildFly and Open Liberty), which is
 * why no JSON-B implementation is shipped with this client.
 */
public final class EpistolaJson {

    private static final class Holder {
        private static final Jsonb INSTANCE = JsonbBuilder.create();
    }

    /** The shared, thread-safe {@link Jsonb}. */
    public static Jsonb jsonb() {
        return Holder.INSTANCE;
    }

    private EpistolaJson() {
    }
}
