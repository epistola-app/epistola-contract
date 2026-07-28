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
            headers: null);
    }

    /// <summary>
    /// Parses a problem+json body into its base <see cref="ProblemDetail"/> plus the field-level
    /// <c>errors</c> array and the per-example <c>validationErrors</c> map. Returns <c>null</c> on any
    /// parse failure. Internal and self-contained so it can be unit-tested without a live server.
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

            return new ParsedProblem(problem, errors, validationErrors);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>A parsed problem body: base <see cref="ProblemDetail"/> plus the two extension collections.</summary>
    internal sealed class ParsedProblem
    {
        public ProblemDetail Problem { get; }

        public IReadOnlyList<ValidationError> Errors { get; }

        public IReadOnlyDictionary<string, List<DataModelValidationError>> ValidationErrors { get; }

        public ParsedProblem(
            ProblemDetail problem,
            IReadOnlyList<ValidationError> errors,
            IReadOnlyDictionary<string, List<DataModelValidationError>> validationErrors)
        {
            Problem = problem;
            Errors = errors;
            ValidationErrors = validationErrors;
        }
    }
}
