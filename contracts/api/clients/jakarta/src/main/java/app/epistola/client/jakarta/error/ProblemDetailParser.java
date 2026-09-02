// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import app.epistola.client.jakarta.EpistolaJson;
import app.epistola.client.jakarta.model.DataModelValidationError;
import app.epistola.client.jakarta.model.ProblemDetail;
import app.epistola.client.jakarta.model.ValidationError;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.core.MediaType;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an {@code application/problem+json} body into the pieces
 * {@link ProblemDetailException} carries.
 *
 * <p>The generated {@code ProblemDetail}, {@code ValidationProblemDetail} and
 * {@code DataModelValidationProblemDetail} are independent classes, so the base problem and each
 * extension member are read separately: the body is parsed once with JSON-P and the extension
 * members are then bound with JSON-B. Members the contract does not model are ignored, as RFC 9457
 * requires.
 */
public final class ProblemDetailParser {

    /** The RFC 9457 problem media type, {@code application/problem+json}. */
    public static final MediaType PROBLEM_JSON = new MediaType("application", "problem+json");

    /** A parsed problem body: the base problem plus each extension member the contract models. */
    public static final class ParsedProblem {

        private final ProblemDetail problem;
        private final List<ValidationError> errors;
        private final Map<String, List<DataModelValidationError>> validationErrors;

        ParsedProblem(
                ProblemDetail problem,
                List<ValidationError> errors,
                Map<String, List<DataModelValidationError>> validationErrors) {
            this.problem = problem;
            this.errors = errors;
            this.validationErrors = validationErrors;
        }

        public ProblemDetail problem() {
            return problem;
        }

        /** Field-level failures from a {@code ValidationProblemDetail}; empty when absent. */
        public List<ValidationError> errors() {
            return errors;
        }

        /** Per-example failures from a {@code DataModelValidationProblemDetail}; empty when absent. */
        public Map<String, List<DataModelValidationError>> validationErrors() {
            return validationErrors;
        }
    }

    /**
     * True when {@code mediaType} is {@code application/problem+json} (or a subtype of it, such as
     * one carrying a charset parameter). A {@code null} media type is not a problem body.
     */
    public static boolean isProblemJson(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "problem+json".equalsIgnoreCase(mediaType.getSubtype());
    }

    /**
     * Parses {@code body} into a {@link ParsedProblem}, or returns {@code null} when it is not a
     * usable problem document (empty, not an object, or malformed JSON). Callers fall back to the
     * untyped {@code ApiException} in that case, so behaviour is never worse than the generated
     * default.
     */
    public static ParsedProblem parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonObject root;
            try (JsonReader reader = Json.createReader(new StringReader(body))) {
                JsonValue value = reader.readValue();
                if (value.getValueType() != JsonValue.ValueType.OBJECT) {
                    return null;
                }
                root = value.asJsonObject();
            }

            ProblemDetail problem = EpistolaJson.jsonb().fromJson(body, ProblemDetail.class);
            if (problem == null) {
                return null;
            }
            return new ParsedProblem(problem, readErrors(root), readValidationErrors(root));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<ValidationError> readErrors(JsonObject root) {
        JsonValue errors = root.get("errors");
        if (errors == null || errors.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        return Arrays.asList(EpistolaJson.jsonb().fromJson(errors.toString(), ValidationError[].class));
    }

    private static Map<String, List<DataModelValidationError>> readValidationErrors(JsonObject root) {
        JsonValue validationErrors = root.get("validationErrors");
        if (validationErrors == null || validationErrors.getValueType() != JsonValue.ValueType.OBJECT) {
            return Collections.emptyMap();
        }
        Map<String, List<DataModelValidationError>> byExample = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : validationErrors.asJsonObject().entrySet()) {
            if (entry.getValue().getValueType() != JsonValue.ValueType.ARRAY) {
                continue;
            }
            JsonArray failures = entry.getValue().asJsonArray();
            byExample.put(
                    entry.getKey(),
                    new ArrayList<>(Arrays.asList(
                            EpistolaJson.jsonb().fromJson(failures.toString(), DataModelValidationError[].class))));
        }
        return byExample;
    }

    private ProblemDetailParser() {
    }
}
