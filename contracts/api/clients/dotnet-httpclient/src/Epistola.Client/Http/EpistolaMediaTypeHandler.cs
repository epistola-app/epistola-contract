// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;

namespace Epistola.Client.Http;

/// <summary>
/// Rewrites outgoing request bodies' <c>Content-Type</c> from the generic
/// <c>application/json</c> the generated client emits to the versioned Epistola vendor type
/// <c>application/vnd.epistola.v1+json</c> the API requires.
///
/// The OpenAPI C# generator hardcodes <c>application/json</c> on the serialized <c>StringContent</c>
/// (ApiClient builds the body with a literal media type), even though it selects the vendor type for
/// content negotiation. This handler corrects that on the wire without patching generated code, so a
/// generator upgrade can't silently reintroduce the wrong media type. Binary/multipart uploads keep
/// their own content type and are untouched.
/// </summary>
public sealed class EpistolaMediaTypeHandler : DelegatingHandler
{
    /// <summary>The Epistola versioned JSON media type.</summary>
    public const string VendorJson = "application/vnd.epistola.v1+json";

    private const string GenericJson = "application/json";

    public EpistolaMediaTypeHandler()
    {
    }

    public EpistolaMediaTypeHandler(HttpMessageHandler innerHandler)
        : base(innerHandler)
    {
    }

    protected override Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        var contentType = request.Content?.Headers.ContentType;
        if (contentType != null && contentType.MediaType == GenericJson)
        {
            request.Content!.Headers.ContentType = new MediaTypeHeaderValue(VendorJson)
            {
                CharSet = contentType.CharSet,
            };
        }

        return base.SendAsync(request, cancellationToken);
    }
}
