// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using Newtonsoft.Json.Linq;

namespace Epistola.Client.Error;

/// <summary>
/// One entry of a <c>release-dependencies-missing</c> problem's <c>missingDependencies</c> member:
/// a release of another catalog that the deployed release renders with and that the environment
/// does not serve.
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

    /// <summary>The release the deployed release was cut with.</summary>
    public string ReleaseVersion { get; }

    /// <summary>Why the release is missing, as the server sent it; see <see cref="Reasons"/>.</summary>
    public string Reason { get; }

    public MissingDependency(string registry, string @namespace, string catalogKey, string releaseVersion, string reason)
    {
        Registry = registry;
        Namespace = @namespace;
        CatalogKey = catalogKey;
        ReleaseVersion = releaseVersion;
        Reason = reason;
    }

    /// <summary>The reasons the contract names today. Keep a default branch: the set may grow.</summary>
    public static class Reasons
    {
        /// <summary>The tenant has the release, but the environment does not serve it.</summary>
        public const string NotDeployed = "NOT_DEPLOYED";

        /// <summary>The tenant does not have the release.</summary>
        public const string NotInstalled = "NOT_INSTALLED";

        /// <summary><c>includeDependencies</c> was set, but the release could not be fetched or verified.</summary>
        public const string Unavailable = "UNAVAILABLE";

        /// <summary>The tenant has the release without its content, so it cannot be deployed.</summary>
        public const string ContentNotRetained = "CONTENT_NOT_RETAINED";
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
        var releaseVersion = Text("releaseVersion");
        var reason = Text("reason");
        if (registry == null || ns == null || catalogKey == null || releaseVersion == null || reason == null)
        {
            return null;
        }
        return new MissingDependency(registry, ns, catalogKey, releaseVersion, reason);
    }

    /// <inheritdoc/>
    public override string ToString() => $"{Registry}/{Namespace}/{CatalogKey}@{ReleaseVersion}:{Reason}";
}
