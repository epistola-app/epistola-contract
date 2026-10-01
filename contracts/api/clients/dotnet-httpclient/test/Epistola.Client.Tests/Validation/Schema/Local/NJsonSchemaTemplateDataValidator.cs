// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using Epistola.Client.Api;
using Epistola.Client.Validation.Schema;
using Newtonsoft.Json;
using NJsonSchema;
using NJsonSchema.Validation;

namespace Epistola.Client.Tests.Validation.Schema.Local;

/// <summary>
/// An <see cref="ITemplateDataValidator"/> that validates in-process, on NJsonSchema.
///
/// <para>
/// <b>This lives in test sources on purpose.</b> The published client ships no JSON Schema library
/// and references none, so nobody installs a schema compiler for a feature they may never call. It
/// exists to prove the interface is implementable against a real library rather than a stub, to
/// drive the conformance scenario, and to be copied by a consumer who wants local validation —
/// pre-flighting a large batch is the case that justifies it, since
/// <see cref="PreflightsGeneration"/> stays <c>true</c> and one cached schema then answers every
/// item without a request per item.
/// </para>
///
/// <para>Two details are the whole reason a copyable example is worth keeping:</para>
///
/// <list type="bullet">
///   <item><description>
///     <b>Both the path and the keyword have to be converted.</b> NJsonSchema reports a path like
///     <c>#/customer/name</c> and a <c>Kind</c> enum (<c>StringExpected</c>, <c>Required</c>) —
///     neither of which is what <see cref="ITemplateDataValidator"/> pins. The validator this
///     replaced passed both through unchanged, so it reported <c>#/customer/name</c> and
///     <c>StringExpected</c> where the contract promised <c>/customer/name</c> and a JSON Schema
///     keyword.
///   </description></item>
///   <item><description>
///     <b>The schema is fetched, so it is cached.</b> A validator is only "local" after a round
///     trip for the template; without a cache this is slower than asking the server outright.
///   </description></item>
/// </list>
///
/// <para>
/// And one thing a copy cannot fix, which is the argument for the default being the server: <b>a
/// local library's verdict is its own.</b> Draft coverage, <c>format</c> handling and error
/// vocabulary are all the library's choices. Epistola decides what it will render, and only asking
/// it cannot disagree with that.
/// </para>
/// </summary>
public sealed class NJsonSchemaTemplateDataValidator : ITemplateDataValidator
{
    private readonly ITemplatesApi _templatesApi;
    private readonly TimeSpan _ttl;
    private readonly ConcurrentDictionary<string, (JsonSchema? Schema, DateTimeOffset StoredAt)> _cache = new();

    public NJsonSchemaTemplateDataValidator(ITemplatesApi templatesApi, TimeSpan? ttl = null)
    {
        _templatesApi = templatesApi;
        _ttl = ttl ?? TimeSpan.FromMinutes(5);
    }

    /// <summary>
    /// <c>true</c> — an in-process check before submitting is nearly free, and reports every item
    /// of a batch at once.
    /// </summary>
    public bool PreflightsGeneration => true;

    public IReadOnlyList<TemplateDataValidationException.ValidationError> Validate(
        string tenantId, string catalogId, string templateId, object data)
    {
        var schema = SchemaFor(tenantId, catalogId, templateId);
        // No schema on the template means nothing is claimed about the data, so nothing is wrong
        // with it. Same answer the server gives.
        if (schema == null)
        {
            return Array.Empty<TemplateDataValidationException.ValidationError>();
        }

        return Flatten(schema.Validate(JsonConvert.SerializeObject(data)))
            .Select(message => new TemplateDataValidationException.ValidationError(
                ToPointer(message.Path),
                message.ToString(),
                ToKeyword(message.Kind)))
            .ToList();
    }

    /// <summary>
    /// The leaf failures, with NJsonSchema's grouping errors removed.
    ///
    /// <para>
    /// A failure inside an array item or a sub-schema is not reported where it happened: NJsonSchema
    /// raises a <see cref="ChildSchemaValidationError"/> at the container — <c>#/lineItems[0]</c>,
    /// kind <c>ArrayItemNotValid</c> — and hangs the real failures off it. Without this walk a
    /// caller is told that an item is wrong but not which of its fields, which is useless for
    /// marking a form field. The validator this replaced did not walk them, so every nested failure
    /// surfaced as its container.
    /// </para>
    /// </summary>
    private static IEnumerable<ValidationError> Flatten(IEnumerable<ValidationError> errors)
    {
        foreach (var error in errors)
        {
            if (error is ChildSchemaValidationError group)
            {
                foreach (var child in group.Errors.Values.SelectMany(Flatten))
                {
                    yield return child;
                }
                continue;
            }
            yield return error;
        }
    }

    private JsonSchema? SchemaFor(string tenantId, string catalogId, string templateId)
    {
        var key = string.Join('\u0000', tenantId, catalogId, templateId);
        if (_cache.TryGetValue(key, out var entry) && DateTimeOffset.UtcNow < entry.StoredAt + _ttl)
        {
            return entry.Schema;
        }
        var template = _templatesApi.GetTemplate(tenantId, catalogId, templateId);
        var schema = template.Schema == null
            ? null
            : JsonSchema.FromJsonAsync(JsonConvert.SerializeObject(template.Schema)).GetAwaiter().GetResult();
        _cache[key] = (schema, DateTimeOffset.UtcNow);
        return schema;
    }

    /// <summary>
    /// NJsonSchema's path as the JSON Pointer the interface pins.
    ///
    /// <para>
    /// NJsonSchema writes <c>#/customer.name</c> and <c>#/lineItems[0].quantity</c>: a <c>#/</c>
    /// prefix, dot-separated property names, and bracketed array indices. A pointer is
    /// <c>/customer/name</c> and <c>/lineItems/0/quantity</c>, and the document root is the empty
    /// string rather than <c>#</c> or <c>/</c>. So the prefix is dropped and the segments are
    /// re-joined.
    /// </para>
    ///
    /// <para>
    /// One limit worth naming: that format is ambiguous. A property actually called <c>a.b</c>, or
    /// one containing a bracket, is indistinguishable from two segments once NJsonSchema has
    /// flattened it, so this conversion cannot be exactly right for such a contract. A pointer has
    /// no such problem — which is one more reason the server's answer is the one to trust.
    /// </para>
    /// </summary>
    internal static string ToPointer(string? path)
    {
        if (string.IsNullOrEmpty(path))
        {
            return string.Empty;
        }
        var body = path!.StartsWith("#", StringComparison.Ordinal) ? path[1..] : path;
        // `lineItems[0].quantity` -> `lineItems.0.quantity`, then split, dropping the empties the
        // leading slash and the closing brackets leave behind.
        var segments = body
            .Replace("[", ".", StringComparison.Ordinal)
            .Replace("]", string.Empty, StringComparison.Ordinal)
            .Split('.', '/')
            .Where(segment => segment.Length > 0);
        return string.Concat(segments.Select(segment => "/" + Escape(segment)));
    }

    private static string Escape(string segment) =>
        segment.Replace("~", "~0", StringComparison.Ordinal).Replace("/", "~1", StringComparison.Ordinal);

    /// <summary>
    /// NJsonSchema's <c>ValidationErrorKind</c> as the JSON Schema keyword it corresponds to.
    ///
    /// <para>
    /// Only the kinds a data contract can realistically produce are mapped; anything else falls
    /// back to the kind's own name, which is still more useful than nothing. This mapping is the
    /// clearest illustration of why the interface pins a shape: the library's vocabulary is not the
    /// specification's.
    /// </para>
    /// </summary>
    internal static string ToKeyword(ValidationErrorKind kind) => kind switch
    {
        ValidationErrorKind.PropertyRequired => "required",
        ValidationErrorKind.StringExpected or
        ValidationErrorKind.NumberExpected or
        ValidationErrorKind.IntegerExpected or
        ValidationErrorKind.BooleanExpected or
        ValidationErrorKind.ObjectExpected or
        ValidationErrorKind.ArrayExpected or
        ValidationErrorKind.NullExpected => "type",
        ValidationErrorKind.StringTooShort => "minLength",
        ValidationErrorKind.StringTooLong => "maxLength",
        ValidationErrorKind.PatternMismatch => "pattern",
        ValidationErrorKind.NumberTooSmall => "minimum",
        ValidationErrorKind.NumberTooBig => "maximum",
        ValidationErrorKind.TooFewItems => "minItems",
        ValidationErrorKind.TooManyItems => "maxItems",
        ValidationErrorKind.NotInEnumeration => "enum",
        _ => kind.ToString(),
    };
}
