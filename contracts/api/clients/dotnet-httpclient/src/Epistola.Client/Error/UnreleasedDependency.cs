// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using Newtonsoft.Json.Linq;

namespace Epistola.Client.Error;

/// <summary>
/// One entry of a <c>release-dependencies-unreleased</c> problem's <c>unreleasedDependencies</c>
/// member: a draft of another catalog that the released draft's dependency declarations name,
/// directly or through such a draft's own declarations. A release pins only releases, so the draft
/// must be released first, or along with it (<c>releaseDependencies</c>).
///
/// <para>
/// Hand-written rather than the generated <c>Epistola.Client.Model.UnreleasedCatalogDependency</c>
/// so that one malformed entry is skipped on its own instead of hiding the others, as
/// <see cref="MissingDependency"/> does. It is named differently from the generated model so code
/// importing both namespaces stays unambiguous.
/// </para>
/// </summary>
public sealed class UnreleasedDependency
{
    /// <summary>Registry of the catalog depended on, always written in full (never <c>~</c>).</summary>
    public string Registry { get; }

    /// <summary>Namespace of the catalog depended on, within its registry.</summary>
    public string Namespace { get; }

    /// <summary>Key of the catalog depended on.</summary>
    public string CatalogKey { get; }

    /// <summary>The id of the draft the declaration names.</summary>
    public string ReleaseId { get; }

    /// <summary>The draft's name, as the server sent it.</summary>
    public string ReleaseName { get; }

    public UnreleasedDependency(string registry, string @namespace, string catalogKey, string releaseId, string releaseName)
    {
        Registry = registry;
        Namespace = @namespace;
        CatalogKey = catalogKey;
        ReleaseId = releaseId;
        ReleaseName = releaseName;
    }

    /// <summary>
    /// One raw member entry, or <c>null</c> when it is not an object with all five members as
    /// strings. A malformed entry yields nothing rather than a guess.
    /// </summary>
    internal static UnreleasedDependency? FromMember(JToken? entry)
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
        var releaseId = Text("releaseId");
        var releaseName = Text("releaseName");
        if (registry == null || ns == null || catalogKey == null || releaseId == null || releaseName == null)
        {
            return null;
        }
        return new UnreleasedDependency(registry, ns, catalogKey, releaseId, releaseName);
    }

    /// <inheritdoc/>
    public override string ToString() => $"{Registry}/{Namespace}/{CatalogKey}#{ReleaseId}={ReleaseName}";
}
