// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ProblemTypeUrisTest {

    private final ProblemTypeUris types = ProblemTypeUris.of("https://epistola.app/errors/");

    @Test
    void a_slug_round_trips_through_its_type_uri() {
        for (String slug : new String[] {"not-found", "validation-error", "data-model-validation-error"}) {
            URI type = types.typeFor(slug);
            assertEquals("https://epistola.app/errors/" + slug, type.toString());
            assertEquals(slug, types.slugFor(type));
        }
    }

    @Test
    void types_that_are_not_ours_have_no_slug() {
        assertNull(types.slugFor(ProblemTypeUris.BLANK_TYPE));
        assertNull(types.slugFor(URI.create("https://example.com/errors/not-found")));
        assertNull(types.slugFor(URI.create("https://epistola.app/errors/")), "the bare base is not a slug");
        assertNull(types.slugFor(null));
    }

    @Test
    void the_base_is_reported_back() {
        assertEquals("https://epistola.app/errors/", types.typeBase());
    }

    @Test
    void a_blank_base_is_rejected_where_it_is_supplied() {
        assertThrows(IllegalArgumentException.class, () -> ProblemTypeUris.of(""));
        assertThrows(IllegalArgumentException.class, () -> ProblemTypeUris.of("   "));
        assertThrows(IllegalArgumentException.class, () -> ProblemTypeUris.of(null));
    }
}
