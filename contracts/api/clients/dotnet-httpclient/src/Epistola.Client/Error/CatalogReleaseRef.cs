// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using Newtonsoft.Json.Linq;

namespace Epistola.Client.Error;

/// <summary>
/// One release a problem names in full, such as each release of another catalog a
/// <c>release-in-use</c> problem's <c>pinnedBy</c> member says pins the release.
///
/// <para>
/// Hand-written rather than the generated <c>Epistola.Client.Model.CatalogReleaseRefDto</c> so that
/// one malformed entry is skipped on its own instead of hiding the others, as
/// <see cref="MissingDependency"/> does. It is named differently from the generated model so code
/// importing both namespaces stays unambiguous.
/// </para>
/// </summary>
public sealed class CatalogReleaseRef
{
    /// <summary>Registry of the catalog, always written in full (never <c>~</c>).</summary>
    public string Registry { get; }

    /// <summary>Namespace of the catalog, within its registry.</summary>
    public string Namespace { get; }

    /// <summary>Key of the catalog.</summary>
    public string CatalogKey { get; }

    /// <summary>The release's version.</summary>
    public string Version { get; }

    /// <summary>The release's id.</summary>
    public string ReleaseId { get; }

    public CatalogReleaseRef(string registry, string @namespace, string catalogKey, string version, string releaseId)
    {
        Registry = registry;
        Namespace = @namespace;
        CatalogKey = catalogKey;
        Version = version;
        ReleaseId = releaseId;
    }

    /// <summary>
    /// One raw member entry, or <c>null</c> when it is not an object with all five members as
    /// strings. A malformed entry yields nothing rather than a guess.
    /// </summary>
    internal static CatalogReleaseRef? FromMember(JToken? entry)
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
        var releaseId = Text("releaseId");
        if (registry == null || ns == null || catalogKey == null || version == null || releaseId == null)
        {
            return null;
        }
        return new CatalogReleaseRef(registry, ns, catalogKey, version, releaseId);
    }

    /// <inheritdoc/>
    public override string ToString() => $"{Registry}/{Namespace}/{CatalogKey}@{Version}#{ReleaseId}";
}
