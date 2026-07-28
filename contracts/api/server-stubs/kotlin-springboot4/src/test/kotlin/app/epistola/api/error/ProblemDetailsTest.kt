// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import app.epistola.api.model.DataModelValidationError
import app.epistola.api.model.ValidationError
import org.springframework.http.HttpStatus
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemDetailsTest {

    @Test
    fun `of sets the type discriminator and status`() {
        val problem = ProblemDetails.of(HttpStatus.NOT_FOUND, type = ProblemDetails.typeFor("not-found"))
        assertEquals(URI.create("https://epistola.app/errors/not-found"), problem.type)
        assertEquals(404, problem.status)
    }

    @Test
    fun `of defaults type to about blank and applies detail`() {
        val problem = ProblemDetails.of(HttpStatus.NOT_FOUND, detail = "Theme 'classic' was not found")
        assertEquals(ProblemDetails.BLANK_TYPE, problem.type)
        assertEquals("Theme 'classic' was not found", problem.detail)
    }

    @Test
    fun `typeFor builds an Epistola problem type URI from a slug`() {
        assertEquals(URI.create("https://epistola.app/errors/theme-not-found"), ProblemDetails.typeFor("theme-not-found"))
    }

    @Test
    fun `validation carries field level errors`() {
        val errors = listOf(ValidationError(field = "name", message = "must not be blank"))
        val problem = ProblemDetails.validation(
            HttpStatus.BAD_REQUEST,
            type = ProblemDetails.typeFor("validation-error"),
            errors = errors,
        )
        assertEquals(URI.create("https://epistola.app/errors/validation-error"), problem.type)
        assertEquals(errors, problem.properties?.get("errors"))
    }

    @Test
    fun `dataModelValidation carries per-example failures`() {
        val validationErrors = mapOf(
            "Example 1" to listOf(DataModelValidationError(path = "/name", message = "required property 'name' not found")),
        )
        val problem = ProblemDetails.dataModelValidation(
            HttpStatus.UNPROCESSABLE_ENTITY,
            type = ProblemDetails.typeFor("data-model-validation-error"),
            validationErrors = validationErrors,
        )
        assertEquals(URI.create("https://epistola.app/errors/data-model-validation-error"), problem.type)
        assertEquals(422, problem.status)
        assertEquals(validationErrors, problem.properties?.get("validationErrors"))
    }
}
