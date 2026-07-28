// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;

namespace Epistola.Client.Auth;

/// <summary>
/// Static API-key authentication helper.
/// New integrations should send API keys through <c>Authorization: ApiKey &lt;key&gt;</c>.
/// </summary>
public sealed class ApiKeyAuth
{
    private readonly string _apiKey;

    private ApiKeyAuth(string apiKey)
    {
        _apiKey = apiKey;
    }

    public static ApiKeyAuth Of(string apiKey)
    {
        if (string.IsNullOrWhiteSpace(apiKey))
        {
            throw new ArgumentException("apiKey must not be blank", nameof(apiKey));
        }

        return new ApiKeyAuth(apiKey.Trim());
    }

    public DelegatingHandler Handler() => new ApiKeyAuthHandler(_apiKey);

    private sealed class ApiKeyAuthHandler : DelegatingHandler
    {
        private readonly string _apiKey;

        public ApiKeyAuthHandler(string apiKey)
        {
            _apiKey = apiKey;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("ApiKey", _apiKey);
            return base.SendAsync(request, cancellationToken);
        }
    }
}
