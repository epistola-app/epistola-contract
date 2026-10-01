// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema.local;

import app.epistola.client.jakarta.EpistolaJson;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.TemplateDto;
import app.epistola.client.jakarta.validation.schema.TemplateDataValidationException.ValidationError;
import app.epistola.client.jakarta.validation.schema.TemplateDataValidator;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link TemplateDataValidator} that validates in-process, on
 * {@code com.networknt:json-schema-validator}.
 *
 * <p><strong>This lives in test sources on purpose.</strong> The published client ships no JSON
 * Schema engine, so no container inherits one. It exists to prove the interface is implementable
 * against a real engine rather than a stub, to drive the conformance scenario, and to be copied by
 * a consumer who wants local validation — pre-flighting a large batch is the case that justifies
 * it, since {@link #preflightsGeneration()} stays true and one cached schema then answers every
 * item without a request per item.
 *
 * <p>Two details are the whole reason a copyable example is worth keeping:
 *
 * <ul>
 *   <li><strong>The path format has to be asked for.</strong> networknt's default renders an
 *       instance location as {@code $.customer.email}, a JSONPath, while
 *       {@link TemplateDataValidator} pins {@code path} to a JSON Pointer.
 *       {@link PathType#JSON_POINTER} is what makes {@code getInstanceLocation().toString()} emit
 *       {@code /customer/email}. The validator this replaced did not set it, so it reported
 *       JSONPath under a Javadoc promising pointers.
 *   <li><strong>The schema is fetched, so it is cached.</strong> A validator is only "local" after
 *       a round trip for the template; without a cache this is slower than asking the server.
 * </ul>
 *
 * <p>And one thing a copy cannot fix, which is the argument for the default being the server:
 * <strong>a local engine's verdict is its own.</strong> Under 2019-09 and 2020-12 {@code format} is
 * an annotation rather than an assertion, so this validator accepts an {@code email} field holding
 * {@code not-an-email} unless format assertion is switched on; draft coverage, regex flavour and
 * {@code format} policy are all the engine's choices. Epistola decides what it will render, and
 * only asking it cannot disagree with that.
 */
public final class NetworkntTemplateDataValidator implements TemplateDataValidator {

    private static final SpecVersion.VersionFlag DEFAULT_VERSION = SpecVersion.VersionFlag.V202012;

    private final TemplatesApi templatesApi;
    private final Duration ttl;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public NetworkntTemplateDataValidator(TemplatesApi templatesApi) {
        this(templatesApi, Duration.ofMinutes(5));
    }

    public NetworkntTemplateDataValidator(TemplatesApi templatesApi, Duration ttl) {
        this.templatesApi = templatesApi;
        this.ttl = ttl;
    }

    @Override
    public List<ValidationError> validate(String tenantId, String catalogId, String templateId, Object data) {
        JsonSchema schema = schemaFor(tenantId, catalogId, templateId);
        if (schema == null) {
            // No schema on the template means nothing is claimed about the data, so nothing is
            // wrong with it. Same answer the server gives.
            return List.of();
        }

        Set<ValidationMessage> messages = schema.validate(EpistolaJson.jsonb().toJson(data), InputFormat.JSON);
        List<ValidationError> errors = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            errors.add(new ValidationError(
                    message.getInstanceLocation().toString(), message.getMessage(), message.getType()));
        }
        return errors;
    }

    private JsonSchema schemaFor(String tenantId, String catalogId, String templateId) {
        String key = String.join("\u0000", tenantId, catalogId, templateId);
        Entry existing = cache.get(key);
        if (existing != null && Instant.now().isBefore(existing.storedAt.plus(ttl))) {
            return existing.schema;
        }
        JsonSchema schema = loadSchema(tenantId, catalogId, templateId);
        cache.put(key, new Entry(schema, Instant.now()));
        return schema;
    }

    private JsonSchema loadSchema(String tenantId, String catalogId, String templateId) {
        TemplateDto template = templatesApi.getTemplate(tenantId, catalogId, templateId);
        if (template.getSchema() == null) {
            return null;
        }
        String schemaJson = EpistolaJson.jsonb().toJson(template.getSchema());
        SchemaValidatorsConfig config =
                SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build();
        return JsonSchemaFactory.getInstance(dialectOf(schemaJson)).getSchema(schemaJson, InputFormat.JSON, config);
    }

    /** Reads the schema's own {@code $schema} so a draft-07 template is not judged under 2020-12. */
    private static SpecVersion.VersionFlag dialectOf(String schemaJson) {
        String declared;
        try (JsonReader reader = Json.createReader(new StringReader(schemaJson))) {
            JsonValue root = reader.readValue();
            if (root.getValueType() != JsonValue.ValueType.OBJECT) {
                return DEFAULT_VERSION;
            }
            JsonObject object = root.asJsonObject();
            JsonValue declaration = object.get("$schema");
            if (declaration == null || declaration.getValueType() != JsonValue.ValueType.STRING) {
                return DEFAULT_VERSION;
            }
            declared = object.getString("$schema");
        } catch (RuntimeException e) {
            return DEFAULT_VERSION;
        }

        if (declared.contains("draft-04")) {
            return SpecVersion.VersionFlag.V4;
        }
        if (declared.contains("draft-06")) {
            return SpecVersion.VersionFlag.V6;
        }
        if (declared.contains("draft-07")) {
            return SpecVersion.VersionFlag.V7;
        }
        if (declared.contains("2019-09")) {
            return SpecVersion.VersionFlag.V201909;
        }
        return DEFAULT_VERSION;
    }

    private static final class Entry {
        private final JsonSchema schema;
        private final Instant storedAt;

        private Entry(JsonSchema schema, Instant storedAt) {
            this.schema = schema;
            this.storedAt = Objects.requireNonNull(storedAt);
        }
    }
}
