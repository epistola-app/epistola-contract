// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UserAgentTest {

    private final UserAgent userAgent = UserAgent.of(" ", "/");

    @Test
    void formatting_and_parsing_are_inverses() {
        // The point of holding both halves here: the clients format this header and the server
        // parses it, and nothing used to check that the two agreed.
        List<UserAgent.Product> products = List.of(
                new UserAgent.Product("epistola-contract", "1.1.0"),
                new UserAgent.Product("zaakafhandelcomponent", "3.4.0"),
                new UserAgent.Product("gzac", "5.0.0"));

        String formatted = userAgent.format(products);

        assertEquals("epistola-contract/1.1.0 zaakafhandelcomponent/3.4.0 gzac/5.0.0", formatted);
        assertEquals(products, userAgent.parse(formatted));
    }

    @Test
    void a_single_product_needs_no_separator() {
        assertEquals(
                "epistola-contract/1.1.0",
                userAgent.format(List.of(new UserAgent.Product("epistola-contract", "1.1.0"))));
    }

    @Test
    void an_empty_product_list_formats_to_an_empty_value() {
        assertEquals("", userAgent.format(List.of()));
    }

    @Test
    void parsing_tolerates_what_a_real_header_may_contain() {
        assertEquals(List.of(), userAgent.parse(null));
        assertEquals(List.of(), userAgent.parse(""));
        assertEquals(List.of(), userAgent.parse("   "));

        // RFC 9110 allows runs of whitespace between product tokens.
        assertEquals(
                List.of(new UserAgent.Product("a", "1"), new UserAgent.Product("b", "2")),
                userAgent.parse("  a/1   b/2  "));

        // A token with no version is kept with an empty version rather than dropped, so an
        // unexpected header is still partially usable.
        assertEquals(List.of(new UserAgent.Product("curl", "")), userAgent.parse("curl"));
    }

    @Test
    void a_version_containing_the_separator_survives_the_round_trip() {
        List<UserAgent.Product> products = List.of(new UserAgent.Product("app", "1.0/beta"));
        assertEquals(products, userAgent.parse(userAgent.format(products)), "split on the first separator only");
    }

    @Test
    void a_products_version_can_be_looked_up_by_name() {
        List<UserAgent.Product> products = userAgent.parse("epistola-contract/1.1.0 gzac/5.0.0");

        assertEquals("1.1.0", UserAgent.versionOf(products, "epistola-contract"));
        assertEquals("5.0.0", UserAgent.versionOf(products, "gzac"));
        assertNull(UserAgent.versionOf(products, "absent"));
    }

    @Test
    void empty_separators_are_rejected_where_they_are_supplied() {
        assertTrue(
                assertThrowsIllegalArgument(() -> UserAgent.of("", "/"))
                        .getMessage()
                        .contains("separators"));
        assertThrowsIllegalArgument(() -> UserAgent.of(" ", ""));
    }

    private static IllegalArgumentException assertThrowsIllegalArgument(Runnable action) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, action::run);
    }
}
