// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import app.epistola.client.jakarta.model.InvalidDataField;
import app.epistola.client.jakarta.model.MissingDataField;
import app.epistola.client.jakarta.model.TemplateDataValidationError;
import app.epistola.client.jakarta.validation.schema.TemplateDataValidationException.ValidationError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns what the server reports about template data into the one error shape
 * {@link TemplateDataValidator} pins.
 *
 * <p>{@code invalidFields} and {@code missingFields} are preferred over {@code errors} where the
 * server sends them, and not only because they carry more. Their {@code path} is specified as a
 * JSON Pointer into the data, which is what the interface promises callers; {@code errors[].path}
 * describes itself as a pointer but is documented with a JSONPath example
 * ({@code $.customer.email}), so passing it through unexamined would make the promise depend on
 * which member the server happened to fill. The fallback to {@code errors} exists so a server that
 * sends only that is still reported rather than silently accepted.
 *
 * <p>An absent <strong>optional</strong> field is not a finding — the contract says so explicitly,
 * and the server lists those in {@code missingFields} too so a client can offer them. Only
 * {@code required} ones become errors.
 */
final class TemplateDataProblems {

    private static final String MISSING_REQUIRED_MESSAGE = "is required but was not supplied";

    private TemplateDataProblems() {}

    static List<ValidationError> toValidationErrors(
            List<TemplateDataValidationError> errors,
            List<MissingDataField> missingFields,
            List<InvalidDataField> invalidFields) {

        List<ValidationError> fromFields = new ArrayList<>();
        if (invalidFields != null) {
            for (InvalidDataField field : invalidFields) {
                fromFields.add(new ValidationError(field.getPath(), field.getMessage(), field.getKeyword()));
            }
        }
        if (missingFields != null) {
            for (MissingDataField field : missingFields) {
                if (Boolean.FALSE.equals(field.getRequired())) {
                    continue;
                }
                fromFields.add(new ValidationError(field.getPath(), MISSING_REQUIRED_MESSAGE, "required"));
            }
        }
        if (!fromFields.isEmpty()) {
            return Collections.unmodifiableList(fromFields);
        }

        List<ValidationError> fromErrors = new ArrayList<>();
        if (errors != null) {
            for (TemplateDataValidationError error : errors) {
                fromErrors.add(new ValidationError(error.getPath(), error.getMessage(), error.getKeyword()));
            }
        }
        if (!fromErrors.isEmpty()) {
            return Collections.unmodifiableList(fromErrors);
        }
        return Collections.singletonList(unspecifiedFinding());
    }

    /**
     * The finding for a rejection that names nothing.
     *
     * <p>Callers only reach this once the verdict is already "not acceptable", and an empty list
     * means the opposite to a {@link TemplateDataValidator} caller. Returning nothing would turn a
     * rejection into a pass, so a server that refuses the data without saying which field is wrong
     * is still reported — at the document root, the only location that is certainly true.
     */
    static ValidationError unspecifiedFinding() {
        return new ValidationError(
                "", "does not fit this template's data contract, which gave no field-level detail", null);
    }
}
