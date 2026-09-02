// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the generated {@link KnownProblemSlugs} (from the spec's {@code x-problem-types}
 * extension) against the hand-written {@link ProblemTypes}. Generation makes drift from the spec
 * impossible; these tests catch the remaining hand-written pieces drifting from the generated data.
 */
class ProblemRegistryTest {

    @Test
    void generated_registry_base_matches_the_hand_written_type_base() {
        assertEquals(ProblemTypes.TYPE_BASE, KnownProblemSlugs.GENERATED_PROBLEM_TYPE_BASE);
    }

    @Test
    void the_canonical_slugs_are_present_with_their_documented_values() {
        assertEquals("validation-error", KnownProblemSlugs.VALIDATION_ERROR);
        assertEquals("bad-request", KnownProblemSlugs.BAD_REQUEST);
        assertEquals("unauthorized", KnownProblemSlugs.UNAUTHORIZED);
        assertEquals("api-key-auth-disabled", KnownProblemSlugs.API_KEY_AUTH_DISABLED);
        assertEquals("forbidden", KnownProblemSlugs.FORBIDDEN);
        assertEquals("not-found", KnownProblemSlugs.NOT_FOUND);
        assertEquals("conflict", KnownProblemSlugs.CONFLICT);
        assertEquals("data-model-validation-error", KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR);
        assertEquals("rate-limited", KnownProblemSlugs.RATE_LIMITED);
    }

    @Test
    void every_registered_slug_round_trips_through_the_type_uri() {
        assertTrue(KnownProblemSlugs.ALL.size() >= 9, "the registry should list every problem type");
        for (String slug : KnownProblemSlugs.ALL) {
            assertEquals(
                    slug,
                    ProblemTypes.slugFor(java.net.URI.create(ProblemTypes.TYPE_BASE + slug)),
                    "slugFor should recover '" + slug + "' from its type URI");
        }
    }

    @Test
    void non_epistola_types_have_no_slug() {
        assertEquals(null, ProblemTypes.slugFor(ProblemTypes.BLANK_TYPE));
        assertEquals(null, ProblemTypes.slugFor(java.net.URI.create("https://example.com/errors/not-found")));
        assertEquals(null, ProblemTypes.slugFor(java.net.URI.create(ProblemTypes.TYPE_BASE)));
        assertEquals(null, ProblemTypes.slugFor(null));
    }

    @Test
    void the_registry_lists_each_slug_once() {
        assertEquals(
                KnownProblemSlugs.ALL.size(),
                List.copyOf(new java.util.LinkedHashSet<>(KnownProblemSlugs.ALL)).size(),
                "x-problem-types should not register a slug twice");
    }
}
