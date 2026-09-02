// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import app.epistola.client.jakarta.EpistolaJson;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.TemplateDto;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates generation data against the JSON Schema declared on the template, before the request
 * leaves the JVM.
 *
 * <p>The template is fetched from the server on first use and the compiled schema is cached.
 *
 * <pre>{@code
 * TemplateSchemaValidator validator = new TemplateSchemaValidator(templatesApi);
 * validator.validate("my-tenant", "default", "monthly-invoice", data);
 * }</pre>
 *
 * <p><strong>Requires an optional dependency.</strong> {@code com.networknt:json-schema-validator}
 * is {@code compileOnly} in this client so the container is not handed a JSON Schema engine (and a
 * Jackson) it did not ask for. Add it to your own build to use this class.
 */
public class TemplateSchemaValidator {

    private static final SpecVersion.VersionFlag DEFAULT_VERSION = SpecVersion.VersionFlag.V202012;

    private final TemplatesApi templatesApi;
    private final SchemaCache cache;

    public TemplateSchemaValidator(TemplatesApi templatesApi) {
        this(templatesApi, new TtlSchemaCache());
    }

    /**
     * @param templatesApi the generated API used to fetch template metadata
     * @param cache        where compiled schemas are kept between calls
     */
    public TemplateSchemaValidator(TemplatesApi templatesApi, SchemaCache cache) {
        this.templatesApi = templatesApi;
        this.cache = cache;
    }

    /**
     * Validates {@code data} against the template's schema.
     *
     * @throws TemplateDataValidationException when the data does not satisfy the schema
     * @throws app.epistola.client.jakarta.api.ApiException when the template cannot be fetched
     */
    public void validate(String tenantId, String catalogId, String templateId, Object data) {
        JsonSchema schema = cache.getOrLoad(tenantId, templateId, () -> loadSchema(tenantId, catalogId, templateId));
        if (schema == null) {
            // No schema on the template — nothing to validate against.
            return;
        }

        Set<ValidationMessage> messages =
                schema.validate(EpistolaJson.jsonb().toJson(data), InputFormat.JSON);
        if (messages.isEmpty()) {
            return;
        }

        List<TemplateDataValidationException.ValidationError> errors = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            errors.add(new TemplateDataValidationException.ValidationError(
                    message.getInstanceLocation().toString(), message.getMessage(), message.getType()));
        }
        throw new TemplateDataValidationException(errors);
    }

    private JsonSchema loadSchema(String tenantId, String catalogId, String templateId) {
        TemplateDto template = templatesApi.getTemplate(tenantId, catalogId, templateId);
        if (template.getSchema() == null) {
            return null;
        }
        String schemaJson = EpistolaJson.jsonb().toJson(template.getSchema());
        return JsonSchemaFactory.getInstance(detectVersion(schemaJson)).getSchema(schemaJson, InputFormat.JSON);
    }

    /**
     * Reads the schema's own {@code $schema} declaration so a draft-07 template is not validated
     * under 2020-12 rules. Falls back to 2020-12, which is what the editor emits.
     */
    private static SpecVersion.VersionFlag detectVersion(String schemaJson) {
        String declared;
        try (JsonReader reader = Json.createReader(new StringReader(schemaJson))) {
            JsonValue root = reader.readValue();
            if (root.getValueType() != JsonValue.ValueType.OBJECT) {
                return DEFAULT_VERSION;
            }
            JsonObject object = root.asJsonObject();
            JsonValue schemaUri = object.get("$schema");
            if (schemaUri == null || schemaUri.getValueType() != JsonValue.ValueType.STRING) {
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
}
