// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * The release number rule of wire v8, defined once for every producer and consumer.
 *
 * A release is numbered with a SemVer core version, `MAJOR.MINOR.PATCH` without leading zeros and
 * without a pre-release or build part (`1.4.0`, never `1.4.0-beta.1` or `1.4.0+build.5`), at most
 * [MAX_LENGTH] characters. It is the same rule as the API's `ReleaseVersion`, so every release an
 * archive carries can be reported by the API. It applies to `release.version` and to a catalog
 * dependency's `releaseVersion`.
 *
 * Wire v7 and earlier accepted full SemVer; an archive at those versions keeps its rule, and the
 * v7 to v8 migration reports a release numbered with a pre-release or build part as a finding.
 * [ReleaseInfo] and [CatalogDependency] deliberately do not enforce the rule, so that manifests
 * stored under earlier wire versions still bind; the migrator's wire check and the catalog
 * validator enforce it.
 */
object CatalogReleaseVersions {
    /** Longest release number. */
    const val MAX_LENGTH: Int = 50

    /** A release number: `MAJOR.MINOR.PATCH`, without leading zeros. */
    const val PATTERN: String = "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"

    private val releaseVersion = Regex(PATTERN)

    /** Whether [value] is a valid wire-v8 release number. */
    fun isValid(value: String): Boolean = value.length <= MAX_LENGTH && releaseVersion.matches(value)
}
