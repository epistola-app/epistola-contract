// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.protocol

/**
 * The rules for the block style presets a catalog declares ([CatalogInfo.presets], wire v8,
 * ADR 0007), defined once for every producer and consumer.
 *
 * A preset key is what a block's `stylePreset` names and what a theme's `blockStylePresets` styles
 * it under: a leading lowercase letter, then lowercase letters and digits in hyphen-separated parts,
 * at most [KEY_MAX_LENGTH] characters, unique within one catalog. Themes have always used keys of
 * this shape. A preset has a nonblank display name of at most [NAME_MAX_LENGTH] characters and an
 * optional description of at most [DESCRIPTION_MAX_LENGTH].
 *
 * [CatalogInfo] and [CatalogPresetDeclaration] deliberately do not enforce these rules: consumers
 * rebind stored manifests. The migrator's wire check and the catalog validator enforce them.
 */
object CatalogPresets {
    /** Regular expression a preset key must match in full. */
    const val KEY_PATTERN: String = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"

    /** Longest preset key. */
    const val KEY_MAX_LENGTH: Int = 50

    /** Longest preset display name. */
    const val NAME_MAX_LENGTH: Int = 100

    /** Longest preset description. */
    const val DESCRIPTION_MAX_LENGTH: Int = 500

    private val keyRegex = Regex(KEY_PATTERN)

    /** Whether [key] is a valid preset key. */
    @JvmStatic
    fun isValidKey(key: String): Boolean = key.length <= KEY_MAX_LENGTH && keyRegex.matches(key)

    /** Every rule [presets] break, in a stable order: by index, then key, name, description and duplication. */
    internal fun violations(presets: List<CatalogPresetDeclaration>): List<PresetViolation> = buildList {
        val seen = mutableSetOf<String>()
        presets.forEachIndexed { index, preset ->
            if (!isValidKey(preset.key)) {
                add(
                    PresetViolation(
                        index,
                        "key",
                        PresetRule.KEY_INVALID,
                        "preset key must be at most $KEY_MAX_LENGTH characters matching $KEY_PATTERN",
                    ),
                )
            }
            if (preset.name.isBlank() || preset.name.length > NAME_MAX_LENGTH) {
                add(PresetViolation(index, "name", PresetRule.NAME_INVALID, "preset name must be nonblank and at most $NAME_MAX_LENGTH characters"))
            }
            if ((preset.description?.length ?: 0) > DESCRIPTION_MAX_LENGTH) {
                add(
                    PresetViolation(
                        index,
                        "description",
                        PresetRule.DESCRIPTION_TOO_LONG,
                        "preset description must be at most $DESCRIPTION_MAX_LENGTH characters",
                    ),
                )
            }
            if (!seen.add(preset.key)) {
                add(PresetViolation(index, "key", PresetRule.DUPLICATE, "preset '${preset.key}' is declared more than once"))
            }
        }
    }
}

/** The preset rule a [PresetViolation] breaks. */
internal enum class PresetRule { KEY_INVALID, NAME_INVALID, DESCRIPTION_TOO_LONG, DUPLICATE }

/** One broken preset rule, for the preset at [index] and its [member]. */
internal data class PresetViolation(
    val index: Int,
    val member: String,
    val rule: PresetRule,
    val message: String,
)
