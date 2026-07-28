using System;
using System.Collections.Generic;
using System.Linq;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Thrown when template data fails JSON Schema validation on the client side.
/// Mirrors the server's validation error structure.
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
    /// <param name="Path">JSON path to the invalid field, e.g. <c>#/customer/name</c>.</param>
    /// <param name="Message">Human-readable error description.</param>
    /// <param name="Keyword">JSON Schema keyword/kind that failed, e.g. <c>Required</c>, <c>StringExpected</c>.</param>
    public sealed record ValidationError(string Path, string Message, string? Keyword);

    /// <summary>Formats all errors as a multi-line string.</summary>
    public string FormatErrors() => string.Join("\n", Errors.Select(e => $"  {e.Path}: {e.Message}"));
}
