// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import app.epistola.api.model.DataModelValidationError
import app.epistola.api.model.MissingReleaseDependency
import app.epistola.api.model.MissingReleaseDependencyReason
import app.epistola.api.model.UnreleasedCatalogDependency
import app.epistola.api.model.ValidationError
import org.springframework.http.HttpStatus
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `releaseDependenciesMissing lists each missing release as a 409`() {
        val missing = listOf(
            MissingReleaseDependency(
                registry = "exchange.epistola.app",
                namespace = "epistola",
                catalogKey = "system",
                releaseVersion = "3.2.0",
                reason = MissingReleaseDependencyReason.UNAVAILABLE,
            ),
        )
        val problem = ProblemDetails.releaseDependenciesMissing(missing, detail = "letters 1.4.0 renders with releases production does not serve")
        assertEquals(URI.create("https://epistola.app/errors/release-dependencies-missing"), problem.type)
        assertEquals(409, problem.status)
        assertEquals(missing, problem.properties?.get("missingDependencies"))
    }

    @Test
    fun `releaseDependenciesUnreleased lists each unreleased draft as a 409`() {
        val unreleased = listOf(
            UnreleasedCatalogDependency(
                registry = "exchange.epistola.app",
                namespace = "acme-corp",
                catalogKey = "branding",
                versionId = "r4t8w2nc",
                draftName = "House style 2027",
            ),
        )
        val problem = ProblemDetails.releaseDependenciesUnreleased(unreleased, detail = "letters uses drafts of other catalogs")
        assertEquals(URI.create("https://epistola.app/errors/release-dependencies-unreleased"), problem.type)
        assertEquals(409, problem.status)
        assertEquals(unreleased, problem.properties?.get("unreleasedDependencies"))
    }

    @Test
    fun `catalogSourceUnavailable is one type sent as a 503 or a 502`() {
        val unreachable = ProblemDetails.catalogSourceUnavailable(HttpStatus.SERVICE_UNAVAILABLE, detail = "registry.gemeente-x.nl could not be reached")
        assertEquals(URI.create("https://epistola.app/errors/catalog-source-unavailable"), unreachable.type)
        assertEquals(503, unreachable.status)

        val badAnswer = ProblemDetails.catalogSourceUnavailable(HttpStatus.BAD_GATEWAY)
        assertEquals(URI.create("https://epistola.app/errors/catalog-source-unavailable"), badAnswer.type)
        assertEquals(502, badAnswer.status)

        assertFailsWith<IllegalArgumentException> {
            ProblemDetails.catalogSourceUnavailable(HttpStatus.CONFLICT)
        }
    }
}
