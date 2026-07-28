// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.IdentityModel.Tokens.Jwt;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.IdentityModel.Tokens;

namespace Epistola.Client.Auth;

/// <summary>
/// Creates and signs short-lived JWTs for self-signed JWT authentication with Epistola.
///
/// Each token contains:
/// <list type="bullet">
/// <item><c>iss</c>: the consumer ID</item>
/// <item><c>iat</c>: issued-at timestamp</item>
/// <item><c>exp</c>: expiry (iat + tokenLifetime)</item>
/// <item><c>jti</c>: unique nonce (GUID) for replay protection</item>
/// </list>
///
/// <code>
/// var signer = JwtSigner.Builder()
///     .ConsumerId("invoice-service")
///     .PrivateKey(JwtSigner.LoadPrivateKey("private.pem"))
///     .Build();
/// </code>
/// </summary>
public sealed class JwtSigner
{
    private readonly string _consumerId;
    private readonly SigningCredentials _credentials;
    private readonly TimeSpan _tokenLifetime;

    private JwtSigner(string consumerId, SigningCredentials credentials, TimeSpan tokenLifetime)
    {
        _consumerId = consumerId;
        _credentials = credentials;
        _tokenLifetime = tokenLifetime;
    }

    /// <summary>Creates a new <see cref="JwtSignerBuilder"/>.</summary>
    public static JwtSignerBuilder Builder() => new();

    /// <summary>Loads a private key from a PEM file (RSA or EC P-256, PKCS#8 <c>BEGIN PRIVATE KEY</c>).</summary>
    public static AsymmetricAlgorithm LoadPrivateKey(string path) => ParsePrivateKeyPem(File.ReadAllText(path));

    /// <summary>Parses a PEM-encoded private key (RSA or EC P-256, PKCS#8 <c>BEGIN PRIVATE KEY</c>).</summary>
    public static AsymmetricAlgorithm ParsePrivateKeyPem(string pem)
    {
        try
        {
            var rsa = RSA.Create();
            rsa.ImportFromPem(pem);
            return rsa;
        }
        catch (Exception)
        {
            try
            {
                var ecdsa = ECDsa.Create();
                ecdsa.ImportFromPem(pem);
                return ecdsa;
            }
            catch (Exception)
            {
                throw new ArgumentException(
                    "Failed to parse private key. Supported formats: RSA, EC (P-256) in PKCS#8 PEM format.");
            }
        }
    }

    /// <summary>Creates a freshly signed JWT with a new <c>iat</c>, <c>exp</c>, and <c>jti</c>.</summary>
    public string CreateToken()
    {
        var now = DateTime.UtcNow;
        var handler = new JwtSecurityTokenHandler();
        var token = new JwtSecurityToken(
            issuer: _consumerId,
            audience: null,
            claims: new[]
            {
                new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
                new Claim(JwtRegisteredClaimNames.Iat,
                    new DateTimeOffset(now).ToUnixTimeSeconds().ToString(),
                    ClaimValueTypes.Integer64),
            },
            notBefore: now,
            expires: now.Add(_tokenLifetime),
            signingCredentials: _credentials);
        return handler.WriteToken(token);
    }

    /// <summary>
    /// Creates a <see cref="DelegatingHandler"/> that sets <c>Authorization: Bearer &lt;jwt&gt;</c> on
    /// every request, minting a fresh token each time.
    /// </summary>
    public DelegatingHandler Handler() => new JwtSignerHandler(this);

    /// <summary>Fluent builder for <see cref="JwtSigner"/>.</summary>
    public sealed class JwtSignerBuilder
    {
        private string? _consumerId;
        private AsymmetricAlgorithm? _privateKey;
        private TimeSpan _tokenLifetime = TimeSpan.FromSeconds(60);

        /// <summary>Sets the consumer ID used as the JWT <c>iss</c> claim.</summary>
        public JwtSignerBuilder ConsumerId(string consumerId)
        {
            if (string.IsNullOrWhiteSpace(consumerId)) throw new ArgumentException("consumerId must not be blank", nameof(consumerId));
            _consumerId = consumerId;
            return this;
        }

        /// <summary>Sets the private key used to sign tokens (from <see cref="LoadPrivateKey"/> / <see cref="ParsePrivateKeyPem"/>).</summary>
        public JwtSignerBuilder PrivateKey(AsymmetricAlgorithm privateKey)
        {
            _privateKey = privateKey;
            return this;
        }

        /// <summary>Sets the token lifetime (default: 60 seconds).</summary>
        public JwtSignerBuilder TokenLifetime(TimeSpan lifetime)
        {
            if (lifetime <= TimeSpan.Zero) throw new ArgumentException("tokenLifetime must be positive", nameof(lifetime));
            _tokenLifetime = lifetime;
            return this;
        }

        /// <summary>Builds the immutable <see cref="JwtSigner"/>.</summary>
        public JwtSigner Build()
        {
            var id = _consumerId ?? throw new InvalidOperationException("consumerId is required");
            var key = _privateKey ?? throw new InvalidOperationException("privateKey is required");
            var credentials = key switch
            {
                RSA rsa => new SigningCredentials(new RsaSecurityKey(rsa), SecurityAlgorithms.RsaSha256),
                ECDsa ecdsa => new SigningCredentials(new ECDsaSecurityKey(ecdsa), SecurityAlgorithms.EcdsaSha256),
                _ => throw new ArgumentException(
                    $"Unsupported key type: {key.GetType().Name}. Supported: RSA (2048+), EC (P-256)"),
            };
            return new JwtSigner(id, credentials, _tokenLifetime);
        }
    }

    private sealed class JwtSignerHandler : DelegatingHandler
    {
        private readonly JwtSigner _signer;

        public JwtSignerHandler(JwtSigner signer)
        {
            _signer = signer;
        }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _signer.CreateToken());
            return base.SendAsync(request, cancellationToken);
        }
    }
}
