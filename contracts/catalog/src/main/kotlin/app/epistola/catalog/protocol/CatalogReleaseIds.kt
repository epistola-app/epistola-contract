// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * The release id rule of wire v8 (`release.releaseId`), defined once for every producer and
 * consumer.
 *
 * A release id is lowercase letters, digits and single hyphens, [MIN_LENGTH] to [MAX_LENGTH]
 * characters (`k7m2x9qa`, or a UUID). It is the same rule as the API's `CatalogReleaseId`, so the
 * id an archive carries is the id the API addresses the release by. It never contains a dot, so it
 * cannot be mistaken for a release number. It is unique among the releases of one catalog name
 * (registry, namespace and key) and kept when the release moves between installations.
 *
 * [ReleaseInfo] deliberately does not enforce the rule, so that stored manifests still bind; the
 * migrator's wire check and the catalog validator enforce it.
 */
object CatalogReleaseIds {
    /** Shortest release id. */
    const val MIN_LENGTH: Int = 4

    /** Longest release id. */
    const val MAX_LENGTH: Int = 36

    /** A release id: lowercase letters and digits, in groups joined by single hyphens. */
    const val PATTERN: String = "^[a-z0-9]+(-[a-z0-9]+)*$"

    private val releaseId = Regex(PATTERN)

    /** Whether [value] is a valid wire-v8 release id. */
    fun isValid(value: String): Boolean = value.length in MIN_LENGTH..MAX_LENGTH && releaseId.matches(value)
}
