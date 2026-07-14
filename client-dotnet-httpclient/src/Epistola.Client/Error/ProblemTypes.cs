using System;

namespace Epistola.Client.Error;

/// <summary>
/// Constants and helpers describing the Epistola RFC 9457 problem <c>type</c> URI scheme.
///
/// Intentionally duplicated from the server module's problem-type base: the client is published
/// as a standalone package and must not depend on the server. Keep the two in sync — the
/// <c>ProblemRegistryTest</c> guard asserts <see cref="TypeBase"/> equals the value the build-time
/// generator wrote to <c>GeneratedProblemType.Base</c> from the spec's <c>x-problem-types</c>.
///
/// The machine-readable discriminator is the problem <c>type</c> URI — there is no separate
/// <c>code</c> member. Application-level errors use a <c>https://epistola.app/errors/{slug}</c>
/// type; framework errors keep RFC 9457's default <c>about:blank</c>.
/// </summary>
public static class ProblemTypes
{
    /// <summary>Base URI for Epistola problem <c>type</c> values, e.g. <c>https://epistola.app/errors/not-found</c>.</summary>
    public const string TypeBase = "https://epistola.app/errors/";

    /// <summary>The RFC 9457 default problem type, used when no specific type is supplied.</summary>
    public const string BlankType = "about:blank";

    /// <summary>
    /// Extracts the kebab-case slug from an Epistola problem <c>type</c> URI (the part after
    /// <see cref="TypeBase"/>), or <c>null</c> when <paramref name="type"/> is <c>about:blank</c>,
    /// empty, or any non-Epistola URI.
    /// </summary>
    public static string? SlugFor(string? type)
    {
        if (string.IsNullOrEmpty(type)) return null;
        if (!type!.StartsWith(TypeBase, StringComparison.Ordinal)) return null;
        var slug = type.Substring(TypeBase.Length);
        return slug.Length == 0 ? null : slug;
    }
}
