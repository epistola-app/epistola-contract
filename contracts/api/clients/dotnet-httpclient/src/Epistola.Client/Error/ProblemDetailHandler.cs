// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Epistola.Client.Model;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Epistola.Client.Error;

/// <summary>
/// An <b>opt-in</b> <see cref="DelegatingHandler"/> that turns <c>application/problem+json</c> error
/// responses into a typed <see cref="ProblemDetailException"/> (problem <c>type</c>, status,
/// validation errors) before the generated client raises its generic <c>ApiException</c>.
///
/// Opt-in by design — add it to the handler chain of the <see cref="HttpClient"/> you pass to the
/// generated APIs (see <c>EpistolaHttpClientBuilder.InstallProblemDetailHandler</c>).
///
/// Error responses that are <b>not</b> parseable problem+json (a different content type, an empty
/// body, or malformed JSON) pass through untouched, so behaviour is never worse than the generated
/// default. Successful responses (including the NDJSON collect stream) are never buffered here.
/// </summary>
public sealed class ProblemDetailHandler : DelegatingHandler
{
    /// <summary>The RFC 9457 problem media type.</summary>
    public const string ProblemJson = "application/problem+json";

    public ProblemDetailHandler()
    {
    }

    public ProblemDetailHandler(HttpMessageHandler innerHandler)
        : base(innerHandler)
    {
    }

    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        var response = await base.SendAsync(request, cancellationToken).ConfigureAwait(false);

        if (response.IsSuccessStatusCode || response.Content == null)
        {
            return response;
        }

        var mediaType = response.Content.Headers.ContentType?.MediaType;
        if (!string.Equals(mediaType, ProblemJson, StringComparison.OrdinalIgnoreCase))
        {
            return response;
        }

        var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(body))
        {
            return response;
        }

        var parsed = ParseProblem(body);
        if (parsed == null)
        {
            return response;
        }

        throw new ProblemDetailException(
            parsed.Problem,
            parsed.Errors,
            parsed.ValidationErrors,
            response.StatusCode,
            body,
            headers: null,
            missingFields: parsed.MissingFields,
            invalidFields: parsed.InvalidFields,
            missingDependencies: parsed.MissingDependencies,
            unreleasedDependencies: parsed.UnreleasedDependencies);
    }

    /// <summary>
    /// Parses a problem+json body into its base <see cref="ProblemDetail"/> plus the field-level
    /// <c>errors</c> array, the per-example <c>validationErrors</c> map, and the
    /// <c>missingFields</c> / <c>invalidFields</c> arrays a <c>template-data-invalid</c> problem
    /// carries, the <c>missingDependencies</c> array of a <c>release-dependencies-missing</c>
    /// problem, and the <c>unreleasedDependencies</c> array of a
    /// <c>release-dependencies-unreleased</c> problem. Returns <c>null</c> on any parse failure. Internal and self-contained so it can be
    /// unit-tested without a live server.
    /// </summary>
    internal static ParsedProblem? ParseProblem(string body)
    {
        try
        {
            var problem = JsonConvert.DeserializeObject<ProblemDetail>(body);
            if (problem == null)
            {
                return null;
            }

            var tree = JObject.Parse(body);

            var errors = new List<ValidationError>();
            if (tree["errors"] is JArray errorsArray)
            {
                errors = errorsArray.ToObject<List<ValidationError>>() ?? new List<ValidationError>();
            }

            var validationErrors = new Dictionary<string, List<DataModelValidationError>>();
            if (tree["validationErrors"] is JObject validationErrorsObj)
            {
                validationErrors = validationErrorsObj.ToObject<Dictionary<string, List<DataModelValidationError>>>()
                    ?? new Dictionary<string, List<DataModelValidationError>>();
            }

            return new ParsedProblem(
                problem,
                errors,
                validationErrors,
                Members<MissingDataField>(tree, "missingFields"),
                Members<InvalidDataField>(tree, "invalidFields"),
                Entries(tree, "missingDependencies", MissingDependency.FromMember),
                Entries(tree, "unreleasedDependencies", UnreleasedDependency.FromMember));
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>
    /// One extension member that is an array of objects, or an empty list when absent or malformed.
    /// A malformed member yields nothing rather than a guess: a bad problem body should not become a
    /// confident claim about a particular field, and must not hide the problem it decorates.
    /// </summary>
    private static List<T> Members<T>(JObject tree, string member)
    {
        if (tree[member] is not JArray array)
        {
            return new List<T>();
        }
        try
        {
            return array.ToObject<List<T>>() ?? new List<T>();
        }
        catch (JsonException)
        {
            return new List<T>();
        }
    }

    /// <summary>
    /// A dependency member (<c>missingDependencies</c>, <c>unreleasedDependencies</c>), entry by
    /// entry: unlike <see cref="Members{T}"/>, a malformed entry is skipped on its own, so one bad
    /// entry does not hide what the others name. Absent or not an array yields an empty list.
    /// </summary>
    private static List<T> Entries<T>(JObject tree, string member, Func<JToken?, T?> parse)
        where T : class
    {
        var result = new List<T>();
        if (tree[member] is not JArray array)
        {
            return result;
        }
        foreach (var entry in array)
        {
            var dependency = parse(entry);
            if (dependency != null)
            {
                result.Add(dependency);
            }
        }
        return result;
    }

    /// <summary>A parsed problem body: base <see cref="ProblemDetail"/> plus each extension collection.</summary>
    internal sealed class ParsedProblem
    {
        public ProblemDetail Problem { get; }

        public IReadOnlyList<ValidationError> Errors { get; }

        public IReadOnlyDictionary<string, List<DataModelValidationError>> ValidationErrors { get; }

        /// <summary>Absent fields from a <c>TemplateDataValidationProblemDetail</c>; empty when absent.</summary>
        public IReadOnlyList<MissingDataField> MissingFields { get; }

        /// <summary>Supplied values that break the contract, from the same problem; empty when absent.</summary>
        public IReadOnlyList<InvalidDataField> InvalidFields { get; }

        /// <summary>Dependency releases from a <c>ReleaseDependenciesMissingProblemDetail</c>; empty when absent.</summary>
        public IReadOnlyList<MissingDependency> MissingDependencies { get; }

        /// <summary>Dependency drafts from a <c>ReleaseDependenciesUnreleasedProblemDetail</c>; empty when absent.</summary>
        public IReadOnlyList<UnreleasedDependency> UnreleasedDependencies { get; }

        public ParsedProblem(
            ProblemDetail problem,
            IReadOnlyList<ValidationError> errors,
            IReadOnlyDictionary<string, List<DataModelValidationError>> validationErrors,
            IReadOnlyList<MissingDataField> missingFields,
            IReadOnlyList<InvalidDataField> invalidFields,
            IReadOnlyList<MissingDependency>? missingDependencies = null,
            IReadOnlyList<UnreleasedDependency>? unreleasedDependencies = null)
        {
            Problem = problem;
            Errors = errors;
            ValidationErrors = validationErrors;
            MissingFields = missingFields;
            InvalidFields = invalidFields;
            MissingDependencies = missingDependencies ?? new List<MissingDependency>();
            UnreleasedDependencies = unreleasedDependencies ?? new List<UnreleasedDependency>();
        }
    }
}
