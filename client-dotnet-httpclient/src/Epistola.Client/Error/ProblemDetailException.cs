using System.Collections.Generic;
using System.Net;
using Epistola.Client.Client;
using Epistola.Client.Model;

namespace Epistola.Client.Error;

/// <summary>
/// An <see cref="ApiException"/> carrying a parsed
/// <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a> <see cref="ProblemDetail"/>
/// (<c>application/problem+json</c>) body.
///
/// Thrown by the opt-in <see cref="ProblemDetailHandler"/>. It extends the generated
/// <see cref="ApiException"/> on purpose: existing <c>catch (ApiException)</c> sites keep working
/// and consumers retain the inherited <see cref="ApiException.ErrorCode"/> /
/// <see cref="ApiException.Headers"/> accessors.
///
/// The machine-readable discriminator is the problem <see cref="Type"/> URI; switch on
/// <see cref="TypeSlug"/>. Field-level validation errors (the <c>ValidationProblemDetail</c> shape)
/// are surfaced via <see cref="Errors"/>; per-example data-model validation failures (the
/// <c>DataModelValidationProblemDetail</c> shape, <c>data-model-validation-error</c>) via
/// <see cref="ValidationErrors"/> — the generated <c>ProblemDetail</c>,
/// <c>ValidationProblemDetail</c>, and <c>DataModelValidationProblemDetail</c> models are
/// independent, so the base fields and each extension are carried separately.
/// </summary>
public class ProblemDetailException : ApiException
{
    /// <summary>The parsed base problem (<c>type</c>, <c>title</c>, <c>status</c>, <c>detail</c>, <c>instance</c>).</summary>
    public ProblemDetail Problem { get; }

    /// <summary>Field-level validation errors when the body was a <c>ValidationProblemDetail</c>, else empty.</summary>
    public IReadOnlyList<ValidationError> Errors { get; }

    /// <summary>
    /// Per-example data-model validation failures (example name → failures) when the body was a
    /// <c>DataModelValidationProblemDetail</c> (<c>data-model-validation-error</c>, 422), else empty.
    /// </summary>
    public IReadOnlyDictionary<string, List<DataModelValidationError>> ValidationErrors { get; }

    /// <summary>The HTTP status of the error response.</summary>
    public HttpStatusCode StatusCode { get; }

    public ProblemDetailException(
        ProblemDetail problem,
        IReadOnlyList<ValidationError> errors,
        IReadOnlyDictionary<string, List<DataModelValidationError>> validationErrors,
        HttpStatusCode statusCode,
        string? rawBody,
        Multimap<string, string>? headers)
        : base((int)statusCode, BuildMessage(statusCode, problem), rawBody, headers)
    {
        Problem = problem;
        Errors = errors;
        ValidationErrors = validationErrors;
        StatusCode = statusCode;
    }

    /// <summary>The problem <c>type</c> URI (<c>about:blank</c> when unspecified).</summary>
    public string Type => Problem.Type ?? ProblemTypes.BlankType;

    /// <summary>
    /// Kebab-case slug derived from <see cref="Type"/> by stripping <see cref="ProblemTypes.TypeBase"/>,
    /// or <c>null</c> for <c>about:blank</c> and non-Epistola types. Compare against <c>KnownProblemSlugs</c>.
    /// </summary>
    public string? TypeSlug => ProblemTypes.SlugFor(Problem.Type);

    /// <summary>Short human-readable summary of the problem type (RFC 9457 <c>title</c>).</summary>
    public string? Title => Problem.Title;

    /// <summary>The HTTP status carried in the problem body (RFC 9457 <c>status</c>).</summary>
    public int ProblemStatus => Problem.Status;

    /// <summary>Occurrence-specific explanation (RFC 9457 <c>detail</c>), if the server provided one.</summary>
    public string? Detail => Problem.Detail;

    /// <summary>True when this problem carried field-level validation errors.</summary>
    public bool IsValidationProblem => Errors.Count > 0;

    /// <summary>True when this problem carried per-example data-model validation failures.</summary>
    public bool IsDataModelValidationProblem => ValidationErrors.Count > 0;

    private static string BuildMessage(HttpStatusCode status, ProblemDetail problem)
    {
        var title = problem.Title ?? status.ToString();
        return string.IsNullOrEmpty(problem.Detail)
            ? $"{(int)status} {title}"
            : $"{(int)status} {title}: {problem.Detail}";
    }
}
