package app.epistola.client.error

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
        assertEquals("forbidden", KnownProblemSlugs.FORBIDDEN)
        assertEquals("not-found", KnownProblemSlugs.NOT_FOUND)
        assertEquals("conflict", KnownProblemSlugs.CONFLICT)
        assertEquals("data-model-validation-error", KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR)
        assertEquals("rate-limited", KnownProblemSlugs.RATE_LIMITED)
    }
}
