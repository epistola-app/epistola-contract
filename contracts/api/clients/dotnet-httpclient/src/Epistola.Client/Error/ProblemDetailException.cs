// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

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
/// <see cref="ValidationErrors"/>; the dependency releases a <c>release-dependencies-missing</c>
/// problem lists via <see cref="MissingDependencies"/>; the dependency drafts a
/// <c>release-dependencies-unreleased</c> problem lists via <see cref="UnreleasedDependencies"/>; why a
/// <c>catalog-read-only</c> problem refused a write via <see cref="ReadOnlyReason"/> — the generated <c>ProblemDetail</c>,
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

    /// <summary>
    /// Absent fields reported by a <c>template-data-invalid</c> problem; empty for any other.
    ///
    /// <para>
    /// Each <c>Path</c> is a JSON Pointer into the request's <c>data</c> and <c>Schema</c> is the
    /// contract's schema for that field, so a caller can ask for exactly what is missing. An entry
    /// whose <c>Required</c> is <c>false</c> is informational: the resolved version's template reads
    /// the field, but leaving it out does not make the data invalid.
    /// </para>
    /// </summary>
    public IReadOnlyList<MissingDataField> MissingFields { get; }

    /// <summary>Supplied values the template's data contract rejected; empty for any other problem.</summary>
    public IReadOnlyList<InvalidDataField> InvalidFields { get; }

    /// <summary>
    /// The releases of other catalogs a <c>release-dependencies-missing</c> problem (409, from
    /// <c>DeployCatalogRelease</c>) reports cannot be had, in the order the server listed them; empty for
    /// any other problem.
    ///
    /// <para>
    /// Each entry's <see cref="MissingDependency.Reason"/> is kept as the string the server sent, so a
    /// reason a later contract adds is surfaced rather than dropped. An entry missing one of its five
    /// members, or carrying one that is not a string, is skipped.
    /// </para>
    /// </summary>
    public IReadOnlyList<MissingDependency> MissingDependencies { get; }

    /// <summary>
    /// The drafts of other catalogs a <c>release-dependencies-unreleased</c> problem (409, from
    /// <c>ReleaseCatalogDraft</c>) reports the released draft's dependency declarations name, in the
    /// order the server listed them; empty for any other problem. Release them first, or along with
    /// it (<c>releaseDependencies</c>). An entry missing one of its five members, or carrying one
    /// that is not a string, is skipped.
    /// </summary>
    public IReadOnlyList<UnreleasedDependency> UnreleasedDependencies { get; }

    /// <summary>
    /// Why the catalog cannot be changed, from a <c>catalog-read-only</c> problem (409, from any
    /// write to a catalog whose <c>CatalogDto.Editable</c> is false): <c>editing-disabled</c> (the
    /// installation only receives and runs releases) or <c>supplied-by-epistola</c> (the server
    /// supplies the catalog). Kept as the string the server sent, so a reason a later contract adds
    /// is surfaced; <c>null</c> for any other problem, and when the member is not a string.
    /// </summary>
    public string? ReadOnlyReason { get; }

    /// <summary>
    /// The conflicts a <c>merge-conflicts-unresolved</c> problem (409, from
    /// <c>MergeIntoCatalogDraft</c>, alpha) lists without a resolution, each as
    /// <c>resourceType/resourceKey</c>, in the order sent; empty for any other problem. An entry
    /// that is not a string is skipped.
    /// </summary>
    public IReadOnlyList<string> UnresolvedConflicts { get; }

    /// <summary>
    /// The version of the release a <c>merge-base-missing</c> problem (409, from
    /// <c>MergeIntoCatalogDraft</c>, alpha) asks to get first: the merge base, which this
    /// installation does not have. Read from the <c>version</c> member of that problem only, since
    /// other problems use the name for other things; <c>null</c> for any other problem, and when the
    /// member is not a string.
    /// </summary>
    public string? MissingBaseVersion { get; }

    /// <summary>
    /// That release's catalog fingerprint (<c>v5:&lt;hex&gt;</c>), from the <c>fingerprint</c>
    /// member of a <c>merge-base-missing</c> problem; <c>null</c> for any other problem.
    /// </summary>
    public string? MissingBaseFingerprint { get; }

    /// <summary>
    /// The versions of the releases a <c>catalog-version-behind</c> problem (409, from
    /// <c>ReleaseCatalogDraft</c>) says the draft is behind and the request did not acknowledge:
    /// name each in <c>AcknowledgedBehind</c> to release it anyway. Empty for any other problem.
    /// </summary>
    public IReadOnlyList<string> Behind { get; }

    /// <summary>
    /// The environments a <c>release-in-use</c> problem (409, from <c>DeleteCatalogRelease</c>)
    /// says deploy the release; empty for any other problem, and when none does.
    /// </summary>
    public IReadOnlyList<string> Environments { get; }

    /// <summary>
    /// The releases of other catalogs a <c>release-in-use</c> problem says pin the release, and
    /// render with it; empty for any other problem. A malformed entry is skipped.
    /// </summary>
    public IReadOnlyList<CatalogReleaseRef> PinnedBy { get; }

    /// <summary>
    /// The keys of the variants an <c>ambiguous-variant</c> problem (409, from generating or
    /// previewing a document) says match the request's attributes equally well: name one by
    /// <c>VariantKey</c>, or send attributes that tell them apart. Empty for any other problem.
    /// </summary>
    public IReadOnlyList<string> TiedVariants { get; }

    /// <summary>The HTTP status of the error response.</summary>
    public HttpStatusCode StatusCode { get; }

    public ProblemDetailException(
        ProblemDetail problem,
        IReadOnlyList<ValidationError> errors,
        IReadOnlyDictionary<string, List<DataModelValidationError>> validationErrors,
        HttpStatusCode statusCode,
        string? rawBody,
        Multimap<string, string>? headers,
        IReadOnlyList<MissingDataField>? missingFields = null,
        IReadOnlyList<InvalidDataField>? invalidFields = null,
        IReadOnlyList<MissingDependency>? missingDependencies = null,
        IReadOnlyList<UnreleasedDependency>? unreleasedDependencies = null,
        string? readOnlyReason = null,
        IReadOnlyList<string>? unresolvedConflicts = null,
        string? missingBaseVersion = null,
        string? missingBaseFingerprint = null,
        IReadOnlyList<string>? behind = null,
        IReadOnlyList<string>? environments = null,
        IReadOnlyList<CatalogReleaseRef>? pinnedBy = null,
        IReadOnlyList<string>? tiedVariants = null)
        // ApiException is generated and nullable-oblivious; its errorContent/headers are
        // optional and accept null at runtime, so bridge the nullable values with `!`.
        : base((int)statusCode, BuildMessage(statusCode, problem), rawBody!, headers!)
    {
        Problem = problem;
        Errors = errors;
        ValidationErrors = validationErrors;
        MissingFields = missingFields ?? new List<MissingDataField>();
        InvalidFields = invalidFields ?? new List<InvalidDataField>();
        MissingDependencies = missingDependencies ?? new List<MissingDependency>();
        UnreleasedDependencies = unreleasedDependencies ?? new List<UnreleasedDependency>();
        ReadOnlyReason = readOnlyReason;
        UnresolvedConflicts = unresolvedConflicts ?? new List<string>();
        MissingBaseVersion = missingBaseVersion;
        MissingBaseFingerprint = missingBaseFingerprint;
        Behind = behind ?? new List<string>();
        Environments = environments ?? new List<string>();
        PinnedBy = pinnedBy ?? new List<CatalogReleaseRef>();
        TiedVariants = tiedVariants ?? new List<string>();
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

    /// <summary>True when this problem described template data field by field.</summary>
    public bool IsTemplateDataProblem => MissingFields.Count > 0 || InvalidFields.Count > 0;

    /// <summary>True when this problem listed dependency releases a deployment is missing.</summary>
    public bool IsReleaseDependenciesMissingProblem => MissingDependencies.Count > 0;

    /// <summary>True when this problem listed dependency drafts a release would have to take along.</summary>
    public bool IsReleaseDependenciesUnreleasedProblem => UnreleasedDependencies.Count > 0;

    /// <summary>True when this problem said the catalog cannot be changed, and why.</summary>
    public bool IsCatalogReadOnlyProblem => ReadOnlyReason != null;

    /// <summary>True when this problem listed merge conflicts that need a resolution.</summary>
    public bool IsMergeConflictsUnresolvedProblem => UnresolvedConflicts.Count > 0;

    /// <summary>True when this problem named a merge base this installation does not have.</summary>
    public bool IsMergeBaseMissingProblem => MissingBaseVersion != null;

    /// <summary>True when this problem said the draft is behind releases the request did not acknowledge.</summary>
    public bool IsCatalogVersionBehindProblem => TypeSlug == KnownProblemSlugs.CATALOG_VERSION_BEHIND;

    /// <summary>True when this problem said the release cannot be deleted while something uses it.</summary>
    public bool IsReleaseInUseProblem => TypeSlug == KnownProblemSlugs.RELEASE_IN_USE;

    /// <summary>True when this problem said several variants match equally well.</summary>
    public bool IsAmbiguousVariantProblem => TypeSlug == KnownProblemSlugs.AMBIGUOUS_VARIANT;

    private static string BuildMessage(HttpStatusCode status, ProblemDetail problem)
    {
        var title = problem.Title ?? status.ToString();
        return string.IsNullOrEmpty(problem.Detail)
            ? $"{(int)status} {title}"
            : $"{(int)status} {title}: {problem.Detail}";
    }
}
