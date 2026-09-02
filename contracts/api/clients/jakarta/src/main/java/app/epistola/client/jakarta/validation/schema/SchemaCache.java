// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import com.networknt.schema.JsonSchema;
import java.util.function.Supplier;

/**
 * Cache for compiled JSON Schemas keyed by (tenantId, templateId).
 *
 * <p>Implement this to plug in the cache the application already runs (Infinispan, Caffeine, a CDI
 * bean); {@link TtlSchemaCache} is the dependency-free default.
 */
@FunctionalInterface
public interface SchemaCache {

    /**
     * Returns the cached schema, or calls {@code loader} on a miss and stores what it returns. A
     * {@code null} result means the template has no schema defined — that is a cacheable answer,
     * not a miss.
     */
    JsonSchema getOrLoad(String tenantId, String templateId, Supplier<JsonSchema> loader);
}
