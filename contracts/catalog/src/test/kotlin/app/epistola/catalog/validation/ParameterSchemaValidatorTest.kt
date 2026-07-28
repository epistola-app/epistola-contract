// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.catalog.validation

import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_DEFAULT_TYPE_MISMATCH
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_NAME_RESERVED
import app.epistola.catalog.validation.TemplateValidationCodes.PARAMETER_REQUIRED_UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParameterSchemaValidatorTest {
    @Test
    fun `validates a standalone schema without a synthetic template document`() {
        val report = ParameterSchemaValidator.validate(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "sys" to mapOf("type" to "string"),
                    "count" to mapOf("type" to "integer", "default" to "one"),
                ),
                "required" to listOf("missing"),
            ),
        )

        assertEquals(
            listOf(
                PARAMETER_NAME_RESERVED,
                PARAMETER_DEFAULT_TYPE_MISMATCH,
                PARAMETER_REQUIRED_UNKNOWN,
            ),
            report.findings.map(TemplateValidationFinding::code),
        )
        assertTrue(report.findings.all { it.path.startsWith("parameterSchema.") })
    }
}
