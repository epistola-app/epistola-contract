// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using Newtonsoft.Json.Linq;

namespace Epistola.Client.Error;

/// <summary>
/// One entry of a <c>release-dependencies-missing</c> problem's <c>missingDependencies</c> member:
/// a release of another catalog that the deployed release renders with (one it pins, directly or
/// through another dependency) and that cannot be had.
///
/// <para>
/// Hand-written rather than the generated <c>Epistola.Client.Model.MissingReleaseDependency</c>
/// because <see cref="Reason"/> is a plain string here: the generated enum would reject a reason a
/// later contract adds, and the entry carrying it would be lost. Compare it against the constants
/// in <see cref="Reasons"/>. It is named differently from the generated model so code importing
/// both namespaces stays unambiguous.
/// </para>
/// </summary>
public sealed class MissingDependency
{
    /// <summary>Registry of the catalog depended on, always written in full (never <c>~</c>).</summary>
    public string Registry { get; }

    /// <summary>Namespace of the catalog depended on, within its registry.</summary>
    public string Namespace { get; }

    /// <summary>Key of the catalog depended on.</summary>
    public string CatalogKey { get; }

    /// <summary>The release of the catalog depended on that the deployed release pins.</summary>
    public string Version { get; }

    /// <summary>Why the release is missing, as the server sent it; see <see cref="Reasons"/>.</summary>
    public string Reason { get; }

    public MissingDependency(string registry, string @namespace, string catalogKey, string version, string reason)
    {
        Registry = registry;
        Namespace = @namespace;
        CatalogKey = catalogKey;
        Version = version;
        Reason = reason;
    }

    /// <summary>The reasons the contract names today. Keep a default branch: the set may grow.</summary>
    public static class Reasons
    {
        /// <summary>
        /// The tenant does not have the release, and it could not be fetched from its registry or
        /// any configured mirror, or the copy did not match its recorded fingerprint.
        /// </summary>
        public const string Unavailable = "unavailable";
    }

    /// <summary>
    /// One raw member entry, or <c>null</c> when it is not an object with all five members as
    /// strings. A malformed entry yields nothing rather than a guess.
    /// </summary>
    internal static MissingDependency? FromMember(JToken? entry)
    {
        if (entry is not JObject obj)
        {
            return null;
        }

        string? Text(string name) =>
            obj[name] is JValue { Type: JTokenType.String } value ? (string?)value.Value : null;

        var registry = Text("registry");
        var ns = Text("namespace");
        var catalogKey = Text("catalogKey");
        var version = Text("version");
        var reason = Text("reason");
        if (registry == null || ns == null || catalogKey == null || version == null || reason == null)
        {
            return null;
        }
        return new MissingDependency(registry, ns, catalogKey, version, reason);
    }

    /// <inheritdoc/>
    public override string ToString() => $"{Registry}/{Namespace}/{CatalogKey}@{Version}:{Reason}";
}
