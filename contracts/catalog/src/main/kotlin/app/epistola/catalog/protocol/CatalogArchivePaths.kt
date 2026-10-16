// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * The form of a path into a catalog, introduced by wire v8 and defined once for every producer and
 * consumer (#95).
 *
 * A manifest's `detailUrl`, and the deprecated `contentUrl` of an image or font face, name a file
 * in the same catalog. From wire v8 each is a **relative path below the manifest's own directory**:
 * an optional leading `./`, then one or more segments separated by `/`. A segment is RFC 3986
 * unreserved characters (letters, digits, `-`, `.`, `_`, `~`) and percent-encodings, except an
 * encoded `.`, `/` or `\` (`%2e`, `%2f`, `%5c` in any case), which would let a segment decode to
 * something the rule forbids. The rule rejects a scheme (`https:`), a leading `/` (and so `//host`),
 * a backslash, an empty segment, and a `.` or `..` segment.
 *
 * A reader resolves the path against the archive root for a zip, and against the manifest's URL
 * for a catalog read from a URL. Either way it cannot leave the catalog.
 *
 * Older wire versions did not constrain these members, and stay readable: the migrator's wire check
 * enforces the rule for a document written at v8, and the catalog validator for a catalog read at
 * v8. Models deliberately do not enforce it; consumers rebind manifests stored under earlier
 * versions. Independently of the rule, [app.epistola.catalog.archive.CatalogArchiveReader] never
 * resolves a path outside the archive, at any wire version.
 */
object CatalogArchivePaths {
    private const val SEGMENT: String = "(?!\\.{1,2}(?:/|$))(?:[A-Za-z0-9._~-]|%(?!2[EeFf]|5[Cc])[0-9A-Fa-f]{2})+"

    /** Regular expression a path into the catalog must match in full. */
    const val PATTERN: String = "^(?:\\./)?$SEGMENT(?:/$SEGMENT)*$"

    private val path = Regex(PATTERN)

    /** Whether [value] is a relative path below the manifest's directory. */
    fun isValid(value: String): Boolean = path.matches(value)
}
