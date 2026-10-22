// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * The release number rule of wire v8, defined once for every producer and consumer.
 *
 * A release is numbered with a SemVer core version, `MAJOR.MINOR.PATCH` without leading zeros
 * (`1.4.0`), or, for a **patch release**, that core followed by a pre-release label that names a
 * namespace and a sequence number: `MAJOR.MINOR.PATCH-<namespace>.<N>` (`1.3.1-gemeente-x.1`), at
 * most [MAX_LENGTH] characters in all. Nothing else: no other pre-release (`1.4.0-rc.1`), no build
 * part (`1.4.0+build.5`). It applies to `release.version`, a catalog dependency's `releaseVersion`,
 * `release.base.version`, and the release segment of a carried release's path.
 *
 * **The patch rule** (contract ADR 0003, amendment of 2026-10-22). A catalog's name, its number and
 * its fingerprint are a binding, "this name at this number is this content", and the holder of the
 * catalog's namespace issues the plain numbers. Someone who does not hold it -- a municipality
 * fixing a supplier's catalog -- may only issue a patch release labelled with a namespace they hold
 * themselves, `<namespace>` being a [CatalogNamespaces] slug and `<N>` counting from 1. As a SemVer
 * pre-release, `1.3.1-gemeente-x.1` sorts above `1.3.0` and below the holder's `1.3.1`, so the
 * holder's next release supersedes it (and merges it in), and the two never collide: the holder
 * never uses a label, the patcher never issues a plain number. The format cannot know who holds a
 * namespace, so it checks only the shape; whether a producer may issue a number is the
 * installation's and the registry's to decide.
 *
 * The plain numbers are the API's `ReleaseVersion` ([CORE_PATTERN]); the API does not yet report a
 * patch release.
 *
 * Wire v7 and earlier accepted full SemVer, where a pre-release meant a pre-release; an archive at
 * those versions keeps its rule, and the v7-to-v8 migration reports a release numbered with a
 * pre-release or build part as a finding ([isCore]), never reading it as a patch release.
 * [ReleaseInfo] and [CatalogDependency] deliberately do not enforce the rule, so that manifests
 * stored under earlier wire versions still bind; the migrator's wire check and the catalog
 * validator enforce it.
 */
object CatalogReleaseVersions {
    /** Longest release number, a patch label included. */
    const val MAX_LENGTH: Int = 50

    /** A release number without a patch label: `MAJOR.MINOR.PATCH`, without leading zeros. The API's `ReleaseVersion`. */
    const val CORE_PATTERN: String = "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"

    /**
     * A release number: `MAJOR.MINOR.PATCH`, optionally followed by a patch label
     * `-<namespace>.<N>`: a namespace by its rule ([CatalogNamespaces.PATTERN], 3 to 63 characters)
     * and a sequence number from 1, without leading zeros.
     */
    const val PATTERN: String =
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(?=[a-z0-9-]{3,63}\\.)([a-z][a-z0-9]*(?:-[a-z0-9]+)*)\\.([1-9][0-9]*))?$"

    private val releaseVersion = Regex(PATTERN)
    private val coreVersion = Regex(CORE_PATTERN)

    /** Whether [value] is a valid wire-v8 release number, a patch release included. */
    @JvmStatic
    fun isValid(value: String): Boolean = value.length <= MAX_LENGTH && releaseVersion.matches(value)

    /** Whether [value] is a release number without a patch label: what the namespace's holder issues. */
    @JvmStatic
    fun isCore(value: String): Boolean = value.length <= MAX_LENGTH && coreVersion.matches(value)

    /**
     * The namespace a patch release [value] is labelled with (`gemeente-x` for `1.3.1-gemeente-x.1`),
     * or null when [value] is a release number without a label, or not a valid release number.
     */
    @JvmStatic
    fun patchNamespace(value: String): String? = releaseVersion.matchEntire(value)
        ?.takeIf { value.length <= MAX_LENGTH }
        ?.groupValues?.get(4)
        ?.takeIf { it.isNotEmpty() }
}
