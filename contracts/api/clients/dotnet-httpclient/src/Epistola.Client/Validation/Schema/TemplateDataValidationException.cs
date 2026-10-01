// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Generic;
using System.Linq;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Thrown when template data does not fit a template's data contract.
/// </summary>
public sealed class TemplateDataValidationException : Exception
{
    /// <summary>The individual field-level validation failures.</summary>
    public IReadOnlyList<ValidationError> Errors { get; }

    public TemplateDataValidationException(IReadOnlyList<ValidationError> errors)
        : this(errors, $"Template data validation failed with {errors.Count} error(s)")
    {
    }

    public TemplateDataValidationException(IReadOnlyList<ValidationError> errors, string message)
        : base(message)
    {
        Errors = errors;
    }

    /// <summary>A single field-level validation failure.</summary>
    /// <param name="Path">
    /// JSON Pointer (RFC 6901) into the data, e.g. <c>/customer/email</c>; <c>""</c> for the
    /// document root. Not an NJsonSchema <c>#/customer/name</c> path and not a dotted key — the
    /// format is pinned on <see cref="ITemplateDataValidator"/>, which says why.
    /// </param>
    /// <param name="Message">Human-readable error description.</param>
    /// <param name="Keyword">
    /// The JSON Schema keyword that failed, e.g. <c>required</c>, <c>type</c>, <c>minLength</c>, or
    /// <c>null</c>. A library's own error kind (NJsonSchema's <c>StringExpected</c>, say) is not
    /// one; an adapter maps it.
    /// </param>
    public sealed record ValidationError(string Path, string Message, string? Keyword)
    {
        /// <summary>
        /// A copy with <paramref name="prefix"/> prepended to the path, used to locate an item
        /// within a batch. The prefix is not part of the pointer.
        /// </summary>
        public ValidationError WithPathPrefix(string prefix) => new(prefix + Path, Message, Keyword);
    }

    /// <summary>Formats all errors as a multi-line string.</summary>
    public string FormatErrors() => string.Join("\n", Errors.Select(e => $"  {e.Path}: {e.Message}"));
}
