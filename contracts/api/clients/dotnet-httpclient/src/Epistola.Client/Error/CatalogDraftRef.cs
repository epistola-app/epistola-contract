// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using Newtonsoft.Json.Linq;

namespace Epistola.Client.Error;

/// <summary>
/// One entry of a <c>catalog-draft-in-use</c> problem's <c>dependents</c> member: a draft of another
/// catalog whose dependency declarations name the draft that could not be discarded. Change its
/// declarations, or discard it, first.
///
/// <para>
/// Hand-written rather than the generated <c>Epistola.Client.Model.CatalogDraftRefDto</c> so that
/// one malformed entry is skipped on its own instead of hiding the others, as
/// <see cref="UnreleasedDependency"/> does. It is named differently from the generated model so code
/// importing both namespaces stays unambiguous.
/// </para>
/// </summary>
public sealed class CatalogDraftRef
{
    /// <summary>Registry of the draft's catalog, always written in full (never <c>~</c>).</summary>
    public string Registry { get; }

    /// <summary>Namespace of the draft's catalog, within its registry.</summary>
    public string Namespace { get; }

    /// <summary>Key of the draft's catalog.</summary>
    public string CatalogKey { get; }

    /// <summary>The draft's id.</summary>
    public string ReleaseId { get; }

    /// <summary>The draft's name as it is now, as the server sent it.</summary>
    public string ReleaseName { get; }

    public CatalogDraftRef(string registry, string @namespace, string catalogKey, string releaseId, string releaseName)
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
    internal static CatalogDraftRef? FromMember(JToken? entry)
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
        return new CatalogDraftRef(registry, ns, catalogKey, releaseId, releaseName);
    }

    /// <inheritdoc/>
    public override string ToString() => $"{Registry}/{Namespace}/{CatalogKey}#{ReleaseId}={ReleaseName}";
}
