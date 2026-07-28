// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Epistola.Client.Generated;

namespace Epistola.Client.Identity;

/// <summary>
/// Manages client identity headers (<c>User-Agent</c> and <c>X-EP-Node-Id</c>) required on every
/// Epistola API request.
///
/// The <c>User-Agent</c> always starts with <c>epistola-contract/{contractVersion}</c>. Additional
/// product tokens can be appended to describe the full software stack.
///
/// <code>
/// var identity = ClientIdentity.Builder()
///     .NodeId("my-pod-123")
///     .Product("valtimo-epistola-plugin", "1.2.0")
///     .Product("gzac", "5.0.0")
///     .Build();
/// </code>
///
/// Produces headers:
/// <code>
/// User-Agent: epistola-contract/0.11.0 valtimo-epistola-plugin/1.2.0 gzac/5.0.0
/// X-EP-Node-Id: my-pod-123
/// </code>
/// </summary>
public sealed class ClientIdentity
{
    /// <summary>The <c>X-EP-Node-Id</c> header name.</summary>
    public const string HeaderNodeId = "X-EP-Node-Id";

    internal const string ContractProduct = "epistola-contract";

    /// <summary>The contract version this client library was built against.</summary>
    public static string ContractVersion => Generated.ContractVersion.Version;

    /// <summary>The assembled <c>User-Agent</c> header value.</summary>
    public string UserAgent { get; }

    /// <summary>The <c>X-EP-Node-Id</c> header value.</summary>
    public string NodeId { get; }

    private ClientIdentity(string userAgent, string nodeId)
    {
        UserAgent = userAgent;
        NodeId = nodeId;
    }

    /// <summary>Creates a new <see cref="ClientIdentityBuilder"/>.</summary>
    public static ClientIdentityBuilder Builder() => new();

    /// <summary>
    /// Creates a <see cref="DelegatingHandler"/> that adds the <c>User-Agent</c> and
    /// <c>X-EP-Node-Id</c> headers to every outgoing request.
    /// </summary>
    public DelegatingHandler Handler() => new ClientIdentityHandler(this);

    /// <summary>Fluent builder for <see cref="ClientIdentity"/>.</summary>
    public sealed class ClientIdentityBuilder
    {
        private string? _nodeId;
        private readonly List<(string Name, string Version)> _products = new();

        /// <summary>Sets the node identifier (e.g. Kubernetes pod name, hostname). Defaults to the local hostname.</summary>
        public ClientIdentityBuilder NodeId(string nodeId)
        {
            _nodeId = nodeId;
            return this;
        }

        /// <summary>
        /// Appends a product/version pair to the <c>User-Agent</c>, after the
        /// <c>epistola-contract/{version}</c> token.
        /// </summary>
        public ClientIdentityBuilder Product(string name, string version)
        {
            if (string.IsNullOrWhiteSpace(name)) throw new ArgumentException("Product name must not be blank", nameof(name));
            if (string.IsNullOrWhiteSpace(version)) throw new ArgumentException("Product version must not be blank", nameof(version));
            if (name.Contains('/') || name.Contains(' ')) throw new ArgumentException("Product name must not contain '/' or spaces", nameof(name));
            _products.Add((name, version));
            return this;
        }

        /// <summary>Builds the immutable <see cref="ClientIdentity"/>.</summary>
        public ClientIdentity Build()
        {
            var tokens = new List<string> { $"{ContractProduct}/{ContractVersion}" };
            tokens.AddRange(_products.Select(p => $"{p.Name}/{p.Version}"));
            return new ClientIdentity(
                string.Join(" ", tokens),
                _nodeId ?? Dns.GetHostName());
        }
    }

    private sealed class ClientIdentityHandler : DelegatingHandler
    {
        private readonly ClientIdentity _identity;

        public ClientIdentityHandler(ClientIdentity identity)
        {
            _identity = identity;
        }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            request.Headers.Remove("User-Agent");
            request.Headers.TryAddWithoutValidation("User-Agent", _identity.UserAgent);
            request.Headers.Remove(HeaderNodeId);
            request.Headers.TryAddWithoutValidation(HeaderNodeId, _identity.NodeId);
            return base.SendAsync(request, cancellationToken);
        }
    }
}
