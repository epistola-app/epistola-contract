// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Net.Http;
using Epistola.Client.Auth;
using Epistola.Client.Error;
using Epistola.Client.Identity;

namespace Epistola.Client.Http;

/// <summary>
/// Assembles an <see cref="HttpClient"/> with the Epistola handler chain and hands it to the
/// generated API classes (each generated <c>*Api</c> accepts an <see cref="HttpClient"/>).
///
/// Mirrors the Kotlin client's <c>RestClient.Builder</c> wiring:
/// <code>
/// var http = new EpistolaHttpClientBuilder()
///     .BaseUrl("https://epistola.example.com/api")
///     .Identity(identity)                 // User-Agent + X-EP-Node-Id
///     .JwtSigner(signer)                  // Authorization: Bearer
///     // or .ApiKey("epk_...")             // Authorization: ApiKey
///     .InstallProblemDetailHandler()      // typed ProblemDetailException on problem+json
///     .Build();
///
/// var templates = new Epistola.Client.Api.TemplatesApi(http);
/// </code>
///
/// The media-type handler (fixing the request <c>Content-Type</c> to the Epistola vendor type) is
/// always installed.
/// </summary>
public sealed class EpistolaHttpClientBuilder
{
    private string? _baseUrl;
    private ClientIdentity? _identity;
    private JwtSigner? _jwtSigner;
    private ApiKeyAuth? _apiKeyAuth;
    private bool _installProblemDetailHandler;
    private HttpMessageHandler? _primaryHandler;

    /// <summary>Sets the API base URL (e.g. <c>https://epistola.example.com/api</c>).</summary>
    public EpistolaHttpClientBuilder BaseUrl(string baseUrl)
    {
        _baseUrl = baseUrl;
        return this;
    }

    /// <summary>Adds the identity handler (<c>User-Agent</c> + <c>X-EP-Node-Id</c>).</summary>
    public EpistolaHttpClientBuilder Identity(ClientIdentity identity)
    {
        _identity = identity;
        return this;
    }

    /// <summary>Adds the self-signed JWT bearer handler.</summary>
    public EpistolaHttpClientBuilder JwtSigner(JwtSigner signer)
    {
        _jwtSigner = signer;
        return this;
    }

    /// <summary>Adds the static API-key authorization handler.</summary>
    public EpistolaHttpClientBuilder ApiKey(string apiKey)
    {
        _apiKeyAuth = ApiKeyAuth.Of(apiKey);
        return this;
    }

    /// <summary>Installs the opt-in <see cref="ProblemDetailHandler"/>.</summary>
    public EpistolaHttpClientBuilder InstallProblemDetailHandler()
    {
        _installProblemDetailHandler = true;
        return this;
    }

    /// <summary>Overrides the innermost (network) handler. Defaults to a new <see cref="HttpClientHandler"/>.</summary>
    public EpistolaHttpClientBuilder PrimaryHandler(HttpMessageHandler handler)
    {
        _primaryHandler = handler;
        return this;
    }

    /// <summary>Builds the configured <see cref="HttpClient"/>.</summary>
    public HttpClient Build()
    {
        HttpMessageHandler inner = _primaryHandler ?? new HttpClientHandler();

        if (_installProblemDetailHandler)
        {
            inner = new ProblemDetailHandler(inner);
        }

        inner = new EpistolaMediaTypeHandler(inner);

        // Outside the media-type handler, so it sees the body before the content type is corrected
        // and works on either spelling.
        inner = new EpistolaUnsetPropertyHandler(inner);

        if (_jwtSigner != null)
        {
            inner = Wrap(_jwtSigner.Handler(), inner);
        }
        else if (_apiKeyAuth != null)
        {
            inner = Wrap(_apiKeyAuth.Handler(), inner);
        }

        if (_identity != null)
        {
            inner = Wrap(_identity.Handler(), inner);
        }

        var client = new HttpClient(inner);
        if (!string.IsNullOrEmpty(_baseUrl))
        {
            // The trailing slash is load-bearing. Uri resolution treats the last segment of a base
            // without one as a file rather than a directory, so a base of ".../api" and a relative
            // request of "tenants/…" resolve to "/tenants/…" — the API path silently dropped.
            // ResultCollector issues exactly that relative request, so against the base URL this
            // library's own documentation uses, result collection went to the wrong path.
            client.BaseAddress = new Uri(_baseUrl.EndsWith('/') ? _baseUrl : _baseUrl + "/");
        }

        return client;
    }

    private static HttpMessageHandler Wrap(DelegatingHandler outer, HttpMessageHandler inner)
    {
        outer.InnerHandler = inner;
        return outer;
    }
}
