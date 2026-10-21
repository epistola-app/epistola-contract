// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.error

import app.epistola.client.model.MissingReleaseDependencyReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the generated [KnownProblemSlugs] (from the spec's `x-problem-types` extension)
 * against the hand-written [ProblemTypes]. Generation makes drift from the spec impossible;
 * these tests catch the remaining hand-written pieces drifting from the generated data.
 */
class ProblemRegistryTest {

    @Test
    fun `generated registry base matches the hand-written TYPE_BASE`() {
        assertEquals(ProblemTypes.TYPE_BASE, GENERATED_PROBLEM_TYPE_BASE)
    }

    @Test
    fun `the canonical slugs are present with their documented values`() {
        assertEquals("validation-error", KnownProblemSlugs.VALIDATION_ERROR)
        assertEquals("bad-request", KnownProblemSlugs.BAD_REQUEST)
        assertEquals("unauthorized", KnownProblemSlugs.UNAUTHORIZED)
        assertEquals("api-key-auth-disabled", KnownProblemSlugs.API_KEY_AUTH_DISABLED)
        assertEquals("forbidden", KnownProblemSlugs.FORBIDDEN)
        assertEquals("not-found", KnownProblemSlugs.NOT_FOUND)
        assertEquals("conflict", KnownProblemSlugs.CONFLICT)
        assertEquals("data-model-validation-error", KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR)
        assertEquals("rate-limited", KnownProblemSlugs.RATE_LIMITED)
        assertEquals("release-dependencies-missing", KnownProblemSlugs.RELEASE_DEPENDENCIES_MISSING)
        assertEquals("release-dependencies-unreleased", KnownProblemSlugs.RELEASE_DEPENDENCIES_UNRELEASED)
        assertEquals("catalog-read-only", KnownProblemSlugs.CATALOG_READ_ONLY)
        assertEquals("merge-conflicts-unresolved", KnownProblemSlugs.MERGE_CONFLICTS_UNRESOLVED)
        assertEquals("merge-refused", KnownProblemSlugs.MERGE_REFUSED)
        assertEquals("merge-not-applicable", KnownProblemSlugs.MERGE_NOT_APPLICABLE)
        assertEquals("environment-deployment-refused", KnownProblemSlugs.ENVIRONMENT_DEPLOYMENT_REFUSED)
        assertEquals("resource-modified", KnownProblemSlugs.RESOURCE_MODIFIED)
        assertEquals("catalog-source-unavailable", KnownProblemSlugs.CATALOG_SOURCE_UNAVAILABLE)
    }

    @Test
    fun `the missingDependencies member name is generated from the contract`() {
        assertEquals("missingDependencies", ProblemExtensionMembers.MISSING_DEPENDENCIES)
    }

    @Test
    fun `the unreleasedDependencies member name is generated from the contract`() {
        assertEquals("unreleasedDependencies", ProblemExtensionMembers.UNRELEASED_DEPENDENCIES)
    }

    @Test
    fun `the readOnlyReason member name is generated from the contract`() {
        assertEquals("readOnlyReason", ProblemExtensionMembers.READ_ONLY_REASON)
        assertEquals("unresolvedConflicts", ProblemExtensionMembers.UNRESOLVED_CONFLICTS)
    }

    @Test
    fun `the hand-written dependency reasons match the contract's enum`() {
        // Reasons are strings so an unknown one survives; the named constants must still be the
        // contract's values, which the generated enum carries.
        assertEquals(
            MissingReleaseDependencyReason.entries.map { it.value }.sorted(),
            listOf(MissingReleaseDependency.Reasons.UNAVAILABLE),
        )
    }
}
