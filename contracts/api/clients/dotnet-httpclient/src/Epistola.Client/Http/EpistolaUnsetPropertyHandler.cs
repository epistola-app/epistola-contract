// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Epistola.Client.Http;

/// <summary>
/// Drops properties the caller never set from outgoing request bodies.
///
/// The generated models are plain nullable properties with no way to distinguish "not set" from
/// "explicitly null", so whatever the serializer does with an unset property becomes the request's
/// meaning. Newtonsoft writes it as <c>null</c> — and on the API's thirteen PATCH operations a null
/// is an instruction: <c>description</c> and <c>contact</c> are documented "null to clear",
/// <c>expiresAt</c> as "null to remove expiry". Renaming a consumer therefore also erased its
/// description, contact and expiry, with a 200 in reply. It is also a plain contract violation
/// elsewhere: <c>GenerateDocumentRequest.attributes</c> is typed <c>array</c> with no null in the
/// union, so a request that simply did not select variants by attribute was rejected by any server
/// validating against the spec.
///
/// Omitting is the safe half of the trade. Nothing can be destroyed by accident; what is lost is the
/// ability to clear a field, which was never usable anyway — you could not clear one field without
/// clearing every other you had not set. Restoring it properly means models that carry the
/// distinction (a JsonNullable-style wrapper), which is a larger, separate change.
///
/// Installed by <see cref="EpistolaHttpClientBuilder"/>, in the same slot and for the same class of
/// reason as <see cref="EpistolaMediaTypeHandler"/>: the C# generator gives no way to configure it
/// on the model.
/// </summary>
public sealed class EpistolaUnsetPropertyHandler : DelegatingHandler
{
    public EpistolaUnsetPropertyHandler()
    {
    }

    public EpistolaUnsetPropertyHandler(HttpMessageHandler innerHandler)
        : base(innerHandler)
    {
    }

    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        var mediaType = request.Content?.Headers.ContentType?.MediaType;
        if (request.Content != null && mediaType != null && mediaType.EndsWith("json", System.StringComparison.Ordinal))
        {
            var body = await request.Content.ReadAsStringAsync().ConfigureAwait(false);
            var stripped = StripNulls(body);
            if (stripped != null && stripped != body)
            {
                var replacement = new StringContent(stripped, Encoding.UTF8, mediaType);
                foreach (var header in request.Content.Headers.Where(h => h.Key != "Content-Type" && h.Key != "Content-Length"))
                {
                    replacement.Headers.TryAddWithoutValidation(header.Key, header.Value);
                }

                request.Content = replacement;
            }
        }

        return await base.SendAsync(request, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Removes null-valued properties from the top level of a JSON object. Only the top level:
    /// nested values come from caller-supplied data such as a generation request's free-form
    /// <c>data</c>, where a null is the caller's own and means what they meant by it.
    /// </summary>
    private static string? StripNulls(string body)
    {
        JToken parsed;
        try
        {
            parsed = JToken.Parse(body);
        }
        catch
        {
            // Not JSON after all — leave it exactly as it was.
            return null;
        }

        if (parsed is not JObject root)
        {
            return null;
        }

        foreach (var property in root.Properties().Where(p => p.Value.Type == JTokenType.Null).ToList())
        {
            property.Remove();
        }

        return root.ToString(Newtonsoft.Json.Formatting.None);
    }
}
