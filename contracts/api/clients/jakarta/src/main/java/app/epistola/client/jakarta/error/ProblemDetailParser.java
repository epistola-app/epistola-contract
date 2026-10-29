// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.error;

import app.epistola.client.jakarta.EpistolaJson;
import app.epistola.client.jakarta.model.DataModelValidationError;
import app.epistola.client.jakarta.model.InvalidDataField;
import app.epistola.client.jakarta.model.MissingDataField;
import app.epistola.client.jakarta.model.ProblemDetail;
import app.epistola.client.jakarta.model.ValidationError;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
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
        private final List<MissingDataField> missingFields;
        private final List<InvalidDataField> invalidFields;
        private final List<MissingDependency> missingDependencies;
        private final List<UnreleasedDependency> unreleasedDependencies;
        private final String readOnlyReason;
        private final List<String> unresolvedConflicts;
        private final String missingBaseVersion;
        private final String missingBaseFingerprint;
        private final List<String> behind;
        private final List<String> environments;
        private final List<CatalogReleaseRef> pinnedBy;
        private final List<String> tiedVariants;

        ParsedProblem(
                ProblemDetail problem,
                List<ValidationError> errors,
                Map<String, List<DataModelValidationError>> validationErrors,
                List<MissingDataField> missingFields,
                List<InvalidDataField> invalidFields,
                List<MissingDependency> missingDependencies,
                List<UnreleasedDependency> unreleasedDependencies,
                String readOnlyReason,
                List<String> unresolvedConflicts,
                String missingBaseVersion,
                String missingBaseFingerprint,
                List<String> behind,
                List<String> environments,
                List<CatalogReleaseRef> pinnedBy,
                List<String> tiedVariants) {
            this.problem = problem;
            this.behind = behind;
            this.environments = environments;
            this.pinnedBy = pinnedBy;
            this.tiedVariants = tiedVariants;
            this.readOnlyReason = readOnlyReason;
            this.unresolvedConflicts = unresolvedConflicts;
            this.missingBaseVersion = missingBaseVersion;
            this.missingBaseFingerprint = missingBaseFingerprint;
            this.errors = errors;
            this.validationErrors = validationErrors;
            this.missingFields = missingFields;
            this.invalidFields = invalidFields;
            this.missingDependencies = missingDependencies;
            this.unreleasedDependencies = unreleasedDependencies;
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

        /**
         * Absent fields from a {@code TemplateDataValidationProblemDetail}; empty when absent.
         *
         * <p>Each entry carries a JSON Pointer into the request's {@code data} and the contract's
         * schema for that field, so a caller can ask for exactly what is missing. An entry with
         * {@code required} false is informational: the resolved version's template reads the field,
         * but leaving it out does not make the data invalid.
         */
        public List<MissingDataField> missingFields() {
            return missingFields;
        }

        /** Supplied values that break the contract, from the same problem; empty when absent. */
        public List<InvalidDataField> invalidFields() {
            return invalidFields;
        }

        /**
         * The releases a {@code release-dependencies-missing} problem lists, in the order sent;
         * empty when absent.
         */
        public List<MissingDependency> missingDependencies() {
            return missingDependencies;
        }

        /**
         * The drafts a {@code release-dependencies-unreleased} problem lists, in the order sent;
         * empty when absent.
         */
        public List<UnreleasedDependency> unreleasedDependencies() {
            return unreleasedDependencies;
        }

        /**
         * Why a {@code catalog-read-only} problem refused a write, as sent; {@code null} when absent
         * or not a string.
         */
        public String readOnlyReason() {
            return readOnlyReason;
        }

        /**
         * The conflicts a {@code merge-conflicts-unresolved} problem lists, in the order sent;
         * empty when absent. An entry that is not a string is skipped.
         */
        public List<String> unresolvedConflicts() {
            return unresolvedConflicts;
        }

        /**
         * The version a {@code merge-base-missing} problem names as the release to get first;
         * {@code null} for any other problem, and when absent or not a string.
         */
        public String missingBaseVersion() {
            return missingBaseVersion;
        }

        /** That release's fingerprint, as the same problem names it; {@code null} otherwise. */
        public String missingBaseFingerprint() {
            return missingBaseFingerprint;
        }

        /** The versions a {@code catalog-version-behind} problem lists; empty when absent. */
        public List<String> behind() {
            return behind;
        }

        /** The environments a {@code release-in-use} problem lists; empty when absent. */
        public List<String> environments() {
            return environments;
        }

        /** The releases a {@code release-in-use} problem says pin the release; empty when absent. */
        public List<CatalogReleaseRef> pinnedBy() {
            return pinnedBy;
        }

        /** The variant keys an {@code ambiguous-variant} problem lists; empty when absent. */
        public List<String> tiedVariants() {
            return tiedVariants;
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
            // `version` and `fingerprint` are generic names, so they are read for this problem only.
            boolean mergeBaseMissing =
                    KnownProblemSlugs.MERGE_BASE_MISSING.equals(ProblemTypes.slugFor(problem.getType()));
            return new ParsedProblem(
                    problem,
                    readErrors(root),
                    readValidationErrors(root),
                    readArray(root, ProblemExtensionMembers.MISSING_FIELDS, MissingDataField[].class),
                    readArray(root, ProblemExtensionMembers.INVALID_FIELDS, InvalidDataField[].class),
                    readMissingDependencies(root),
                    readUnreleasedDependencies(root),
                    stringMember(root, ProblemExtensionMembers.READ_ONLY_REASON),
                    readStrings(root, ProblemExtensionMembers.UNRESOLVED_CONFLICTS),
                    mergeBaseMissing ? stringMember(root, ProblemExtensionMembers.VERSION) : null,
                    mergeBaseMissing ? stringMember(root, ProblemExtensionMembers.FINGERPRINT) : null,
                    readStrings(root, ProblemExtensionMembers.BEHIND),
                    readStrings(root, ProblemExtensionMembers.ENVIRONMENTS),
                    readReleaseRefs(root, ProblemExtensionMembers.PINNED_BY),
                    readStrings(root, ProblemExtensionMembers.TIED_VARIANTS));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<ValidationError> readErrors(JsonObject root) {
        JsonValue errors = root.get(ProblemExtensionMembers.ERRORS);
        if (errors == null || errors.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        return Arrays.asList(EpistolaJson.jsonb().fromJson(errors.toString(), ValidationError[].class));
    }

    /** One extension member that is an array of objects, or an empty list when absent or malformed. */
    private static <T> List<T> readArray(JsonObject root, String member, Class<T[]> type) {
        JsonValue value = root.get(member);
        if (value == null || value.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        return Arrays.asList(EpistolaJson.jsonb().fromJson(value.toString(), type));
    }

    /**
     * Read with JSON-P rather than bound to the generated {@code MissingReleaseDependency}, whose
     * enum {@code reason} would reject a reason added after this client was built. An entry that
     * is not an object is skipped; a member that is not a string reads as {@code null}.
     */
    private static List<MissingDependency> readMissingDependencies(JsonObject root) {
        JsonValue value = root.get(ProblemExtensionMembers.MISSING_DEPENDENCIES);
        if (value == null || value.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        List<MissingDependency> dependencies = new ArrayList<>();
        for (JsonValue entry : value.asJsonArray()) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject dependency = entry.asJsonObject();
            dependencies.add(new MissingDependency(
                    stringMember(dependency, "registry"),
                    stringMember(dependency, "namespace"),
                    stringMember(dependency, "catalogKey"),
                    stringMember(dependency, "version"),
                    stringMember(dependency, "reason")));
        }
        return dependencies;
    }

    /**
     * Read with JSON-P, as {@link #readMissingDependencies} is, so one malformed entry does not
     * cost the whole problem. An entry that is not an object is skipped; a member that is not a
     * string reads as {@code null}.
     */
    private static List<UnreleasedDependency> readUnreleasedDependencies(JsonObject root) {
        JsonValue value = root.get(ProblemExtensionMembers.UNRELEASED_DEPENDENCIES);
        if (value == null || value.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        List<UnreleasedDependency> dependencies = new ArrayList<>();
        for (JsonValue entry : value.asJsonArray()) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject dependency = entry.asJsonObject();
            dependencies.add(new UnreleasedDependency(
                    stringMember(dependency, "registry"),
                    stringMember(dependency, "namespace"),
                    stringMember(dependency, "catalogKey"),
                    stringMember(dependency, "releaseId"),
                    stringMember(dependency, "releaseName")));
        }
        return dependencies;
    }

    /**
     * Read with JSON-P, as {@link #readMissingDependencies} is, so one malformed entry does not
     * cost the whole problem. An entry that is not an object is skipped; a member that is not a
     * string reads as {@code null}.
     */
    private static List<CatalogReleaseRef> readReleaseRefs(JsonObject root, String member) {
        JsonValue value = root.get(member);
        if (value == null || value.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        List<CatalogReleaseRef> releases = new ArrayList<>();
        for (JsonValue entry : value.asJsonArray()) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject release = entry.asJsonObject();
            releases.add(new CatalogReleaseRef(
                    stringMember(release, "registry"),
                    stringMember(release, "namespace"),
                    stringMember(release, "catalogKey"),
                    stringMember(release, "version"),
                    stringMember(release, "releaseId")));
        }
        return releases;
    }

    /** An extension member that is an array of strings; entries that are not strings are skipped. */
    private static List<String> readStrings(JsonObject root, String member) {
        JsonValue value = root.get(member);
        if (value == null || value.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        List<String> strings = new ArrayList<>();
        for (JsonValue entry : value.asJsonArray()) {
            if (entry.getValueType() == JsonValue.ValueType.STRING) {
                strings.add(((JsonString) entry).getString());
            }
        }
        return strings;
    }

    private static String stringMember(JsonObject object, String name) {
        JsonValue value = object.get(name);
        return value != null && value.getValueType() == JsonValue.ValueType.STRING
                ? ((JsonString) value).getString()
                : null;
    }

    private static Map<String, List<DataModelValidationError>> readValidationErrors(JsonObject root) {
        JsonValue validationErrors = root.get(ProblemExtensionMembers.VALIDATION_ERRORS);
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
