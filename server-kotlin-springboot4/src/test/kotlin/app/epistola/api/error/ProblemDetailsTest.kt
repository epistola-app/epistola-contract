package app.epistola.api.error

import app.epistola.api.model.ValidationError
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProblemDetailsTest {

    @Test
    fun `of always sets the required code extension member`() {
        val problem = ProblemDetails.of(HttpStatus.NOT_FOUND, code = "THEME_NOT_FOUND")
        assertEquals("THEME_NOT_FOUND", problem.properties?.get("code"))
        assertEquals(404, problem.status)
    }

    @Test
    fun `of defaults type to about blank and applies detail`() {
        val problem = ProblemDetails.of(HttpStatus.NOT_FOUND, code = "THEME_NOT_FOUND", detail = "Theme 'classic' was not found")
        assertEquals(URI.create("about:blank"), problem.type)
        assertEquals("Theme 'classic' was not found", problem.detail)
    }

    @Test
    fun `of applies an explicit type uri`() {
        val problem = ProblemDetails.of(HttpStatus.NOT_FOUND, code = "THEME_NOT_FOUND", type = ProblemDetails.typeFor("not-found"))
        assertEquals(URI.create("https://epistola.app/errors/not-found"), problem.type)
    }

    @Test
    fun `validation carries field level errors`() {
        val errors = listOf(ValidationError(field = "name", message = "must not be blank"))
        val problem = ProblemDetails.validation(HttpStatus.BAD_REQUEST, code = "VALIDATION_ERROR", errors = errors)
        assertEquals("VALIDATION_ERROR", problem.properties?.get("code"))
        assertEquals(errors, problem.properties?.get("errors"))
    }

    @Test
    fun `ensureCode stamps a fallback only when code is absent`() {
        val framework = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)
        assertNull(framework.properties?.get("code"))
        ProblemDetails.ensureCode(framework, fallback = "BAD_REQUEST")
        assertEquals("BAD_REQUEST", framework.properties?.get("code"))
    }

    @Test
    fun `ensureCode does not overwrite an existing code`() {
        val problem = ProblemDetails.of(HttpStatus.NOT_FOUND, code = "THEME_NOT_FOUND")
        ProblemDetails.ensureCode(problem, fallback = "NOT_FOUND")
        assertEquals("THEME_NOT_FOUND", problem.properties?.get("code"))
    }
}
