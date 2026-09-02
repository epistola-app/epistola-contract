// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when template data fails JSON Schema validation on the client side, before the request is
 * sent. Mirrors the server's validation error structure.
 */
public class TemplateDataValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<ValidationError> errors;

    public TemplateDataValidationException(List<ValidationError> errors) {
        this(errors, "Template data validation failed with " + errors.size() + " error(s)");
    }

    public TemplateDataValidationException(List<ValidationError> errors, String message) {
        super(message);
        this.errors = Collections.unmodifiableList(errors);
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    /** Every error as one indented line each, ready for a log statement. */
    public String formatErrors() {
        return errors.stream()
                .map(error -> "  " + error.getPath() + ": " + error.getMessage())
                .collect(Collectors.joining("\n"));
    }

    /** One schema violation. */
    public static final class ValidationError {

        private final String path;
        private final String message;
        private final String keyword;

        public ValidationError(String path, String message, String keyword) {
            this.path = path;
            this.message = message;
            this.keyword = keyword;
        }

        /** JSON Pointer path to the invalid field, e.g. {@code /customer/name}. */
        public String getPath() {
            return path;
        }

        /** Human-readable description of what failed. */
        public String getMessage() {
            return message;
        }

        /** The JSON Schema keyword that failed, e.g. {@code required}, {@code type}, {@code minLength}. */
        public String getKeyword() {
            return keyword;
        }

        /** A copy with {@code prefix} prepended to the path, used to locate an item within a batch. */
        public ValidationError withPathPrefix(String prefix) {
            return new ValidationError(prefix + path, message, keyword);
        }

        @Override
        public String toString() {
            return path + ": " + message;
        }
    }
}
