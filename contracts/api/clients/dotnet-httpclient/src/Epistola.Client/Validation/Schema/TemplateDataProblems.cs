// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System.Collections.Generic;
using Epistola.Client.Model;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Turns what the server reports about template data into the one error shape
/// <see cref="ITemplateDataValidator"/> pins.
///
/// <para>
/// <c>invalidFields</c> and <c>missingFields</c> are preferred over <c>errors</c> where the server
/// sends them, and not only because they carry more. Their <c>path</c> is specified as a JSON
/// Pointer into the data, which is what the interface promises callers; <c>errors[].path</c>
/// describes itself as a pointer but is documented with a JSONPath example
/// (<c>$.customer.email</c>), so passing it through unexamined would make the promise depend on
/// which member the server happened to fill. The fallback to <c>errors</c> exists so a server that
/// sends only that is still reported rather than silently accepted.
/// </para>
///
/// <para>
/// An absent <b>optional</b> field is not a finding — the contract says so explicitly, and the
/// server lists those in <c>missingFields</c> too so a client can offer them. Only required ones
/// become errors.
/// </para>
/// </summary>
internal static class TemplateDataProblems
{
    private const string MissingRequiredMessage = "is required but was not supplied";

    internal static IReadOnlyList<TemplateDataValidationException.ValidationError> ToValidationErrors(
        IReadOnlyList<TemplateDataValidationError>? errors,
        IReadOnlyList<MissingDataField>? missingFields,
        IReadOnlyList<InvalidDataField>? invalidFields)
    {
        var fromFields = new List<TemplateDataValidationException.ValidationError>();
        if (invalidFields != null)
        {
            foreach (var field in invalidFields)
            {
                fromFields.Add(new TemplateDataValidationException.ValidationError(
                    field.Path ?? string.Empty, field.Message ?? string.Empty, field.Keyword));
            }
        }
        if (missingFields != null)
        {
            foreach (var field in missingFields)
            {
                if (field.Required == false)
                {
                    continue;
                }
                fromFields.Add(new TemplateDataValidationException.ValidationError(
                    field.Path ?? string.Empty, MissingRequiredMessage, "required"));
            }
        }
        if (fromFields.Count > 0)
        {
            return fromFields;
        }

        var fromErrors = new List<TemplateDataValidationException.ValidationError>();
        if (errors != null)
        {
            foreach (var error in errors)
            {
                fromErrors.Add(new TemplateDataValidationException.ValidationError(
                    error.Path ?? string.Empty, error.Message ?? string.Empty, error.Keyword));
            }
        }
        return fromErrors.Count > 0
            ? fromErrors
            : new List<TemplateDataValidationException.ValidationError> { UnspecifiedFinding() };
    }

    /// <summary>
    /// The finding for a rejection that names nothing.
    ///
    /// <para>
    /// Callers only reach this once the verdict is already "not acceptable", and an empty list means
    /// the opposite to an <see cref="ITemplateDataValidator"/> caller. Returning nothing would turn
    /// a rejection into a pass, so a server that refuses the data without saying which field is
    /// wrong is still reported — at the document root, the only location that is certainly true.
    /// </para>
    /// </summary>
    internal static TemplateDataValidationException.ValidationError UnspecifiedFinding() =>
        new(string.Empty, "does not fit this template's data contract, which gave no field-level detail", null);
}
