// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.error

import app.epistola.api.model.CatalogReadOnlyReason
import app.epistola.api.model.CatalogReleaseRefDto
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
    fun `helpFor links the type's page with the operation as its context`() {
        assertEquals(URI.create("https://epistola.app/errors/not-found?operation=getTemplate"), ProblemDetails.helpFor("not-found", "getTemplate"))
        assertEquals(URI.create("https://epistola.app/errors/not-found"), ProblemDetails.helpFor("not-found", null))
    }

    @Test
    fun `helpFor never carries a value, only an operation id`() {
        // Anything not shaped like an operationId is left out rather than escaped into the link.
        assertEquals(URI.create("https://epistola.app/errors/not-found"), ProblemDetails.helpFor("not-found", "acme-corp/invoice"))
        assertEquals(URI.create("https://epistola.app/errors/not-found"), ProblemDetails.helpFor("not-found", "getTemplate&templateKey=x"))
        assertEquals(URI.create("https://epistola.app/errors/not-found"), ProblemDetails.helpFor("not-found", ""))
    }

    @Test
    fun `genericSlugFor gives each status its generic registered type`() {
        assertEquals("bad-request", ProblemDetails.genericSlugFor(400))
        assertEquals("not-found", ProblemDetails.genericSlugFor(404))
        assertEquals("method-not-allowed", ProblemDetails.genericSlugFor(405))
        assertEquals("not-acceptable", ProblemDetails.genericSlugFor(406))
        assertEquals("content-too-large", ProblemDetails.genericSlugFor(413))
        assertEquals("unsupported-media-type", ProblemDetails.genericSlugFor(415))
        assertEquals("internal-error", ProblemDetails.genericSlugFor(500))
        assertEquals("operation-not-implemented", ProblemDetails.genericSlugFor(501))
        // No generic type: every 422 and 503 the registry has is specific.
        assertEquals(null, ProblemDetails.genericSlugFor(422))
        assertEquals(null, ProblemDetails.genericSlugFor(503))
        val registered = KnownProblemSlugs::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.also { field -> field.isAccessible = true }.get(null) as String }
            .toSet()
        for (status in 100..599) {
            val slug = ProblemDetails.genericSlugFor(status) ?: continue
            assertEquals(true, slug in registered, "$status maps to $slug, which is not registered")
        }
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
                version = "3.2.0",
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
                releaseId = "r4t8w2nc",
                releaseName = "House style 2027",
            ),
        )
        val problem = ProblemDetails.releaseDependenciesUnreleased(unreleased, detail = "letters uses drafts of other catalogs")
        assertEquals(URI.create("https://epistola.app/errors/release-dependencies-unreleased"), problem.type)
        assertEquals(409, problem.status)
        assertEquals(unreleased, problem.properties?.get("unreleasedDependencies"))
    }

    @Test
    fun `catalogReadOnly says why the catalog cannot be changed, as a 409 with the wire value`() {
        val problem = ProblemDetails.catalogReadOnly(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA, detail = "system is supplied by Epistola")
        assertEquals(URI.create("https://epistola.app/errors/catalog-read-only"), problem.type)
        assertEquals(409, problem.status)
        assertEquals("supplied-by-epistola", problem.properties?.get("readOnlyReason"))
    }

    @Test
    fun `catalogVersionBehind lists the versions to acknowledge as a 409`() {
        val problem = ProblemDetails.catalogVersionBehind(listOf("1.5.0", "1.6.0"))
        assertEquals(URI.create("https://epistola.app/errors/catalog-version-behind"), problem.type)
        assertEquals(409, problem.status)
        assertEquals(listOf("1.5.0", "1.6.0"), problem.properties?.get("behind"))
    }

    @Test
    fun `releaseInUse names the environments that deploy it and the releases that pin it as a 409`() {
        val pin = CatalogReleaseRefDto(registry = "exchange.epistola.app", namespace = "acme-corp", catalogKey = "invoices", version = "2.1.0", releaseId = "q8w2e4rt")
        val problem = ProblemDetails.releaseInUse(listOf("production"), listOf(pin))
        assertEquals(URI.create("https://epistola.app/errors/release-in-use"), problem.type)
        assertEquals(409, problem.status)
        assertEquals(listOf("production"), problem.properties?.get("environments"))
        assertEquals(listOf(pin), problem.properties?.get("pinnedBy"))
    }

    @Test
    fun `ambiguousVariant lists the tied variants as a 409`() {
        val problem = ProblemDetails.ambiguousVariant(listOf("english", "english-legal"))
        assertEquals(URI.create("https://epistola.app/errors/ambiguous-variant"), problem.type)
        assertEquals(409, problem.status)
        assertEquals(listOf("english", "english-legal"), problem.properties?.get("tiedVariants"))
    }

    @Test
    fun `mergeConflictsUnresolved lists each conflict without a resolution as a 409`() {
        val problem = ProblemDetails.mergeConflictsUnresolved(listOf("stencil/footer", "template/invoice"))
        assertEquals(URI.create("https://epistola.app/errors/merge-conflicts-unresolved"), problem.type)
        assertEquals(409, problem.status)
        assertEquals(listOf("stencil/footer", "template/invoice"), problem.properties?.get("unresolvedConflicts"))
        assertEquals("merge-refused", ProblemDetails.KnownSlugs.MERGE_REFUSED)
        assertEquals("merge-preview-outdated", ProblemDetails.KnownSlugs.MERGE_PREVIEW_OUTDATED)
        assertEquals("merge-not-applicable", ProblemDetails.KnownSlugs.MERGE_NOT_APPLICABLE)
    }

    @Test
    fun `mergeBaseMissing names the release to get first as a 409`() {
        val fingerprint = "v5:" + "9".repeat(64)
        val problem = ProblemDetails.mergeBaseMissing("1.0.0", fingerprint, detail = "Install 1.0.0 of letters first")
        assertEquals(URI.create("https://epistola.app/errors/merge-base-missing"), problem.type)
        assertEquals(409, problem.status)
        assertEquals("1.0.0", problem.properties?.get("version"))
        assertEquals(fingerprint, problem.properties?.get("fingerprint"))
        assertEquals("merge-base-missing", ProblemDetails.KnownSlugs.MERGE_BASE_MISSING)
    }

    @Test
    fun `catalogRenderingDefaultsTooNew names what the release renders with and what is supported as a 400`() {
        val problem = ProblemDetails.catalogRenderingDefaultsTooNew("9", 6, detail = "Upgrade Epistola to receive letters 1.1.0")
        assertEquals(URI.create("https://epistola.app/errors/catalog-rendering-defaults-too-new"), problem.type)
        assertEquals(400, problem.status)
        assertEquals("9", problem.properties?.get("renderingDefaultsVersion"))
        assertEquals(6, problem.properties?.get("supportedVersion"))
        assertEquals("catalog-rendering-defaults-too-new", ProblemDetails.KnownSlugs.CATALOG_RENDERING_DEFAULTS_TOO_NEW)
    }

    @Test
    fun `forbidden names the missing permission and its tenant as a 403`() {
        val problem = ProblemDetails.forbidden(requiredPermission = "TEMPLATE_EDIT", tenantKey = "acme-corp")
        assertEquals(URI.create("https://epistola.app/errors/forbidden"), problem.type)
        assertEquals(403, problem.status)
        assertEquals(mapOf("requiredPermission" to "TEMPLATE_EDIT", "tenantKey" to "acme-corp"), problem.properties)
        assertEquals("forbidden", ProblemDetails.KnownSlugs.FORBIDDEN)
    }

    @Test
    fun `forbidden writes only the members it is given`() {
        assertEquals(null, ProblemDetails.forbidden().properties)
        assertEquals(
            mapOf("requiredPlatformRole" to "TENANT_MANAGER"),
            ProblemDetails.forbidden(requiredPlatformRole = "TENANT_MANAGER").properties,
        )
        assertEquals(
            mapOf("tenantKey" to "acme-corp", "reason" to "Only the release board deploys to production environments."),
            ProblemDetails.forbidden(tenantKey = "acme-corp", reason = "Only the release board deploys to production environments.").properties,
        )
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
