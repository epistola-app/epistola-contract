// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import app.epistola.client.jakarta.api.ApiException;
import app.epistola.client.jakarta.model.DataModelValidationError;
import app.epistola.client.jakarta.model.InvalidDataField;
import app.epistola.client.jakarta.model.MissingDataField;
import app.epistola.client.jakarta.model.ProblemDetail;
import app.epistola.client.jakarta.model.ValidationError;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An {@link ApiException} carrying a parsed
 * <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a> {@link ProblemDetail}
 * ({@code application/problem+json}) body.
 *
 * <p>Thrown by {@code ApiExceptionMapper}, which every generated API interface registers through
 * {@code @RegisterProvider} — so this arrives with no consumer wiring, in CDI and programmatic use
 * alike. It extends the generated {@link ApiException} on purpose: existing
 * {@code catch (ApiException e)} sites keep working, and error responses that are <em>not</em>
 * parseable problem+json still surface as a plain {@link ApiException}.
 *
 * <p>The machine-readable discriminator is the problem {@link #getType() type} URI; switch on
 * {@link #getTypeSlug()} and compare against {@code KnownProblemSlugs}. Field-level validation
 * errors (the contract's {@code ValidationProblemDetail} shape) are surfaced via
 * {@link #getErrors()}; per-example data-model validation failures (the
 * {@code DataModelValidationProblemDetail} shape, {@code data-model-validation-error}) via
 * {@link #getValidationErrors()} — the generated {@code ProblemDetail},
 * {@code ValidationProblemDetail} and {@code DataModelValidationProblemDetail} are independent
 * classes, so the base fields and each extension are carried separately.
 */
public class ProblemDetailException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final ProblemDetail problem;
    private final List<ValidationError> errors;
    private final Map<String, List<DataModelValidationError>> validationErrors;
    private final List<MissingDataField> missingFields;
    private final List<InvalidDataField> invalidFields;
    private final List<MissingDependency> missingDependencies;
    private final List<UnreleasedDependency> unreleasedDependencies;
    private final String readOnlyReason;
    private final List<String> unresolvedConflicts;
    private final int statusCode;
    private final String responseBody;
    private final String message;

    public ProblemDetailException(
            Response response,
            ProblemDetail problem,
            List<ValidationError> errors,
            Map<String, List<DataModelValidationError>> validationErrors,
            String responseBody) {
        this(response, problem, errors, validationErrors, null, null, responseBody);
    }

    /**
     * The same, for a problem that also describes template data field by field
     * ({@code template-data-invalid}).
     */
    public ProblemDetailException(
            Response response,
            ProblemDetail problem,
            List<ValidationError> errors,
            Map<String, List<DataModelValidationError>> validationErrors,
            List<MissingDataField> missingFields,
            List<InvalidDataField> invalidFields,
            String responseBody) {
        this(response, problem, errors, validationErrors, missingFields, invalidFields, null, responseBody);
    }

    /**
     * The same, for a problem that may also list missing release dependencies
     * ({@code release-dependencies-missing}).
     */
    public ProblemDetailException(
            Response response,
            ProblemDetail problem,
            List<ValidationError> errors,
            Map<String, List<DataModelValidationError>> validationErrors,
            List<MissingDataField> missingFields,
            List<InvalidDataField> invalidFields,
            List<MissingDependency> missingDependencies,
            String responseBody) {
        this(response, problem, errors, validationErrors, missingFields, invalidFields, missingDependencies, null,
                responseBody);
    }

    /**
     * The same, for a problem that may also list unreleased dependency drafts
     * ({@code release-dependencies-unreleased}).
     */
    public ProblemDetailException(
            Response response,
            ProblemDetail problem,
            List<ValidationError> errors,
            Map<String, List<DataModelValidationError>> validationErrors,
            List<MissingDataField> missingFields,
            List<InvalidDataField> invalidFields,
            List<MissingDependency> missingDependencies,
            List<UnreleasedDependency> unreleasedDependencies,
            String responseBody) {
        this(response, problem, errors, validationErrors, missingFields, invalidFields, missingDependencies,
                unreleasedDependencies, null, responseBody);
    }

    /**
     * The same, for a problem that may also say why a catalog cannot be changed
     * ({@code catalog-read-only}).
     */
    public ProblemDetailException(
            Response response,
            ProblemDetail problem,
            List<ValidationError> errors,
            Map<String, List<DataModelValidationError>> validationErrors,
            List<MissingDataField> missingFields,
            List<InvalidDataField> invalidFields,
            List<MissingDependency> missingDependencies,
            List<UnreleasedDependency> unreleasedDependencies,
            String readOnlyReason,
            String responseBody) {
        this(response, problem, errors, validationErrors, missingFields, invalidFields, missingDependencies,
                unreleasedDependencies, readOnlyReason, null, responseBody);
    }

    /**
     * The same, for a problem that may also list the unresolved conflicts of a merge
     * ({@code merge-conflicts-unresolved}).
     */
    public ProblemDetailException(
            Response response,
            ProblemDetail problem,
            List<ValidationError> errors,
            Map<String, List<DataModelValidationError>> validationErrors,
            List<MissingDataField> missingFields,
            List<InvalidDataField> invalidFields,
            List<MissingDependency> missingDependencies,
            List<UnreleasedDependency> unreleasedDependencies,
            String readOnlyReason,
            List<String> unresolvedConflicts,
            String responseBody) {
        super(response);
        this.readOnlyReason = readOnlyReason;
        this.unresolvedConflicts = unresolvedConflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(unresolvedConflicts);
        this.unreleasedDependencies = unreleasedDependencies == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(unreleasedDependencies);
        this.missingDependencies = missingDependencies == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(missingDependencies);
        this.missingFields = missingFields == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(missingFields);
        this.invalidFields = invalidFields == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(invalidFields);
        this.problem = problem;
        this.errors = errors == null ? Collections.emptyList() : Collections.unmodifiableList(errors);
        this.validationErrors = validationErrors == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(validationErrors);
        // No null guard: ApiException(Response) dereferences the response before this runs, and
        // the mapper that builds this always has one.
        this.statusCode = response.getStatus();
        this.responseBody = responseBody;
        this.message = buildMessage(this.statusCode, problem);
    }

    /**
     * {@link ApiException}'s constructor fixes the message to the bare status code; the problem
     * title and detail are what actually identify the failure in a log line.
     */
    @Override
    public String getMessage() {
        return message;
    }

    /** The parsed base problem ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}). */
    public ProblemDetail getProblem() {
        return problem;
    }

    /** The HTTP status of the response that carried this problem. */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * The raw problem body. Kept because the {@link Response} the mapper saw is closed by the
     * MicroProfile Rest Client implementation once the exception has been thrown.
     */
    public String getResponseBody() {
        return responseBody;
    }

    /** The problem {@code type} URI ({@code about:blank} when unspecified). */
    public URI getType() {
        return problem.getType();
    }

    /**
     * Kebab-case slug derived from {@link #getType()} by stripping {@link ProblemTypes#TYPE_BASE},
     * or {@code null} for {@code about:blank} and non-Epistola types. Compare against
     * {@code KnownProblemSlugs}.
     */
    public String getTypeSlug() {
        return ProblemTypes.slugFor(problem.getType());
    }

    /** Short human-readable summary of the problem type (RFC 9457 {@code title}). */
    public String getTitle() {
        return problem.getTitle();
    }

    /**
     * The HTTP status carried in the problem body. Usually equal to {@link #getStatusCode()}, but
     * named distinctly because the two come from different places.
     */
    public Integer getProblemStatus() {
        return problem.getStatus();
    }

    /** Occurrence-specific explanation (RFC 9457 {@code detail}), if the server provided one. */
    public String getDetail() {
        return problem.getDetail();
    }

    /** Field-level validation errors when the body was a {@code ValidationProblemDetail}, else empty. */
    public List<ValidationError> getErrors() {
        return errors;
    }

    /**
     * Per-example data-model validation failures (example name → failures) when the body was a
     * {@code DataModelValidationProblemDetail} ({@code data-model-validation-error}, 422), else empty.
     */
    public Map<String, List<DataModelValidationError>> getValidationErrors() {
        return validationErrors;
    }

    /** True when this problem carried field-level validation errors. */
    public boolean isValidationProblem() {
        return !errors.isEmpty();
    }

    /**
     * Absent fields reported by a {@code template-data-invalid} problem; empty for any other.
     *
     * <p>Each path is a JSON Pointer into the request's {@code data}. An entry whose
     * {@code required} is false is informational rather than a failure.
     */
    public List<MissingDataField> getMissingFields() {
        return missingFields;
    }

    /** Supplied values the template's data contract rejected; empty for any other problem. */
    public List<InvalidDataField> getInvalidFields() {
        return invalidFields;
    }

    /** True when this problem described template data field by field. */
    public boolean isTemplateDataProblem() {
        return !missingFields.isEmpty() || !invalidFields.isEmpty();
    }

    /** True when this problem carried per-example data-model validation failures. */
    public boolean isDataModelValidationProblem() {
        return !validationErrors.isEmpty();
    }

    /**
     * The releases of other catalogs a {@code release-dependencies-missing} problem (409, from
     * {@code deployCatalogRelease}) lists, in the order sent: each one the deployed release pins that
     * cannot be had, and why. Empty for any other problem.
     */
    public List<MissingDependency> getMissingDependencies() {
        return missingDependencies;
    }

    /** True when this problem listed missing release dependencies. */
    public boolean isReleaseDependenciesMissingProblem() {
        return !missingDependencies.isEmpty();
    }

    /**
     * The drafts of other catalogs a {@code release-dependencies-unreleased} problem (409, from
     * {@code releaseCatalogDraft}) lists, in the order sent: each one the draft's dependency
     * declarations name, which a release cannot pin. Release them first, or along with it
     * ({@code releaseDependencies}). Empty for any other problem.
     */
    public List<UnreleasedDependency> getUnreleasedDependencies() {
        return unreleasedDependencies;
    }

    /** True when this problem listed unreleased dependency drafts. */
    public boolean isReleaseDependenciesUnreleasedProblem() {
        return !unreleasedDependencies.isEmpty();
    }

    /**
     * Why the catalog cannot be changed, from a {@code catalog-read-only} problem (409, from any
     * write to a catalog whose {@code CatalogDto.editable} is false): {@code editing-disabled} (the
     * installation only receives and runs releases) or {@code supplied-by-epistola} (the server
     * supplies the catalog). Kept as the string the server sent, so a reason a later contract adds
     * is surfaced; {@code null} for any other problem, and when the member is not a string.
     */
    public String getReadOnlyReason() {
        return readOnlyReason;
    }

    /** True when this problem said the catalog cannot be changed, and why. */
    public boolean isCatalogReadOnlyProblem() {
        return readOnlyReason != null;
    }

    /**
     * The conflicts a {@code merge-conflicts-unresolved} problem (409, from
     * {@code mergeIntoCatalogDraft}, alpha) lists without a resolution, each as
     * {@code resourceType/resourceKey}, in the order sent. Empty for any other problem.
     */
    public List<String> getUnresolvedConflicts() {
        return unresolvedConflicts;
    }

    /** True when this problem listed merge conflicts that need a resolution. */
    public boolean isMergeConflictsUnresolvedProblem() {
        return !unresolvedConflicts.isEmpty();
    }

    private static String buildMessage(int statusCode, ProblemDetail problem) {
        StringBuilder text = new StringBuilder().append(statusCode).append(' ').append(problem.getTitle());
        if (problem.getDetail() != null) {
            text.append(": ").append(problem.getDetail());
        }
        return text.toString();
    }
}
