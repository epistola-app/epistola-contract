// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CatalogContainerLayoutTest {
    @Test
    fun `a repository names the catalog in full, its authority included`() {
        assertEquals(
            "epistola/catalogs/exchange.epistola.app/acme/letters",
            CatalogContainerLayout.repository("epistola/catalogs", "exchange.epistola.app", "acme", "letters"),
        )
        assertEquals("local/acme-hr/letters", CatalogContainerLayout.repository("", "local", "acme-hr", "letters"))
        assertNotEquals(
            CatalogContainerLayout.repository("cat", "acme.com", "acme", "letters"),
            CatalogContainerLayout.repository("cat", "exchange.epistola.app", "acme", "letters"),
        )
    }

    @Test
    fun `a release is tagged by its number, a patch release included`() {
        assertEquals(
            "cat/exchange.epistola.app/acme/letters:1.3.1-gemeente-x.1",
            CatalogContainerLayout.reference("cat", "exchange.epistola.app", "acme", "letters", "1.3.1-gemeente-x.1"),
        )
        // The longest release number is still a tag (OCI tags are at most 128 characters).
        val longest = "1.0." + "1".repeat(CatalogReleaseVersions.MAX_LENGTH - 4)
        assertEquals(longest, CatalogContainerLayout.tag(longest))
        assertTrue(Regex(CatalogContainerLayout.TAG_PATTERN).matches("0.0.1-" + "a".repeat(CatalogNamespaces.MAX_LENGTH) + ".1"))
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.tag("latest") }
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.tag("1.4.0+build.5") }
    }

    @Test
    fun `parts that break their rule make no repository`() {
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.repository("Bad/Prefix", "acme.com", "acme", "letters") }
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.repository("cat/", "acme.com", "acme", "letters") }
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.repository("cat", "localhost", "acme", "letters") }
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.repository("cat", "acme.com", "ab", "letters") }
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.repository("cat", "acme.com", "acme", "Letters") }
        val tooLong = "a".repeat(240) + ".com"
        assertFailsWith<IllegalArgumentException> { CatalogContainerLayout.repository("cat", tooLong, "acme", "letters") }
    }
}
