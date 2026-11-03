// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.spec

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Holds the authorization vocabulary together: the spec's `x-authorization` extension, the
 * permissions and platform roles each operation declares (`x-required-permissions`,
 * `x-required-platform-roles`), and the role tables in `docs/auth.md`.
 *
 * `x-authorization` is the data a server checks itself against: it ships in this artifact with the
 * rest of the spec (`openapi/epistola-contract.yaml`). Nothing generates code from the per-operation
 * declarations, so a renamed permission, one left behind by a removed feature, or a table that
 * names a permission no role grants would otherwise go unnoticed. That has happened: the role table
 * listed backup permissions after the features were withdrawn.
 */
class AuthorizationVocabularyTest {

    private val methods = setOf("get", "put", "post", "delete", "patch", "head", "options")

    @Suppress("UNCHECKED_CAST")
    private val spec: Map<String, Any> = run {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        resource.use { Yaml().load<Map<String, Any>>(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private val vocabulary: Map<String, Any> =
        assertNotNull(spec["x-authorization"] as? Map<String, Any>, "spec has no x-authorization")

    @Suppress("UNCHECKED_CAST")
    private fun entries(key: String): List<Map<String, Any>> = assertNotNull(vocabulary[key] as? List<Map<String, Any>>, "x-authorization has no $key")

    private val tenantRoles = entries("tenantRoles").map { it["name"] as String }.toSet()
    private val platformRoles = entries("platformRoles").map { it["name"] as String }.toSet()
    private val permissions = entries("permissions")
    private val permissionNames = permissions.map { it["name"] as String }.toSet()

    /** Each operation's id, with what it declares under [key]. */
    @Suppress("UNCHECKED_CAST")
    private fun declared(key: String): Map<String, List<String>> {
        val paths = spec["paths"] as Map<String, Map<String, Any>>
        return paths.values
            .flatMap { item -> item.filterKeys { it in methods }.values }
            .map { it as Map<String, Any> }
            .filter { it.containsKey(key) }
            .associate { (it["operationId"] as String) to (it[key] as List<String>) }
    }

    @Test
    fun `every permission an operation requires is in the vocabulary`() {
        val unknown = declared("x-required-permissions").flatMap { (operation, required) ->
            required.filterNot { it in permissionNames }.map { "$operation: $it" }
        }
        assertEquals(emptyList(), unknown, "operations require permissions x-authorization does not list")
    }

    @Test
    fun `every platform role an operation requires is in the vocabulary`() {
        val unknown = declared("x-required-platform-roles").flatMap { (operation, required) ->
            required.filterNot { it in platformRoles }.map { "$operation: $it" }
        }
        assertEquals(emptyList(), unknown, "operations require platform roles x-authorization does not list")
    }

    @Test
    fun `a permission is outside the API exactly when no operation requires it`() {
        val required = declared("x-required-permissions").values.flatten().toSet()
        val mislabelled = permissions.mapNotNull { permission ->
            val name = permission["name"] as String
            val outsideApi = permission["outsideApi"] == true
            when {
                outsideApi && name in required -> "$name is marked outsideApi, but an operation requires it"
                !outsideApi && name !in required -> "$name is required by no operation; mark it outsideApi or remove it"
                else -> null
            }
        }
        assertEquals(emptyList(), mislabelled)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `every permission is granted by at least one declared tenant role`() {
        val wrong = permissions.mapNotNull { permission ->
            val grantedBy = (permission["grantedBy"] as? List<String>).orEmpty()
            when {
                grantedBy.isEmpty() -> "${permission["name"]} is granted by no role"
                grantedBy.any { it !in tenantRoles } -> "${permission["name"]} is granted by an undeclared role: $grantedBy"
                else -> null
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `a platform role grants in every tenant only declared permissions`() {
        val unknown = entries("platformRoles").flatMap { role ->
            (role["grantsInEveryTenant"] as? List<String>).orEmpty()
                .filterNot { it in permissionNames }
                .map { "${role["name"]}: $it" }
        }
        assertEquals(emptyList(), unknown)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the role tables in auth md match the vocabulary`() {
        val expected = tenantRoles.associateWith { role ->
            permissions.filter { role in (it["grantedBy"] as List<String>) }.map { it["name"] as String }.toSet()
        }
        val documented = tableRows("Suite role").mapValues { (_, grants) -> backticked(grants) }
        assertEquals(expected, documented, "docs/auth.md's tenant-role table disagrees with x-authorization")

        val expectedPlatformRoles = entries("platformRoles").associate { role ->
            (role["name"] as String) to (role["grantsInEveryTenant"] as? List<String>).orEmpty().toSet()
        }
        val documentedPlatformRoles = tableRows("Suite platform role").mapValues { (_, grants) -> backticked(grants) }
        assertEquals(expectedPlatformRoles, documentedPlatformRoles, "docs/auth.md's platform-role table disagrees with x-authorization")
    }

    @Test
    fun `the vocabulary is not empty`() {
        assertTrue(tenantRoles.isNotEmpty() && platformRoles.isNotEmpty() && permissionNames.isNotEmpty())
    }

    /** The rows of the `docs/auth.md` table whose header's first cell is [header], first cell to second. */
    private fun tableRows(header: String): Map<String, String> {
        val lines = Files.readAllLines(Path.of("../../docs/auth.md"))
        val start = lines.indexOfFirst { it.startsWith("| $header |") }
        assertTrue(start >= 0, "docs/auth.md has no table headed '$header'")
        return lines.drop(start + 2)
            .takeWhile { it.startsWith("|") }
            .map { row -> row.split("|").map(String::trim) }
            .associate { cells -> cells[1].trim('`') to cells[2] }
    }

    /** The `MACRO_CASE` names a table cell lists in backticks, ignoring other code in it. */
    private fun backticked(cell: String): Set<String> = Regex("`([A-Z][A-Z_]+)`").findAll(cell).map { it.groupValues[1] }.toSet()
}
