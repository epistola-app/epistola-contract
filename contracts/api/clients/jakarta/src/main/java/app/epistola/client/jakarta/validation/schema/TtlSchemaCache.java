// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import com.networknt.schema.JsonSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * TTL cache backed by a {@link ConcurrentHashMap}. Entries expire a fixed duration after they were
 * stored, so a template whose schema changed is picked up without a redeploy.
 */
public final class TtlSchemaCache implements SchemaCache {

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    public TtlSchemaCache() {
        this(Duration.ofMinutes(5));
    }

    public TtlSchemaCache(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.ttl = ttl;
    }

    @Override
    public JsonSchema getOrLoad(String tenantId, String catalogId, String templateId, Supplier<JsonSchema> loader) {
        CacheKey key = new CacheKey(tenantId, catalogId, templateId);
        CacheEntry existing = cache.get(key);
        if (existing != null && Instant.now().isBefore(existing.storedAt.plus(ttl))) {
            return existing.schema;
        }
        JsonSchema schema = loader.get();
        cache.put(key, new CacheEntry(schema, Instant.now()));
        return schema;
    }

    /** Evicts one entry — useful straight after updating a template. */
    public void evict(String tenantId, String catalogId, String templateId) {
        cache.remove(new CacheKey(tenantId, catalogId, templateId));
    }

    /** Evicts everything. */
    public void evictAll() {
        cache.clear();
    }

    private static final class CacheKey {

        private final String tenantId;
        private final String catalogId;
        private final String templateId;

        private CacheKey(String tenantId, String catalogId, String templateId) {
            this.tenantId = tenantId;
            this.catalogId = catalogId;
            this.templateId = templateId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey that = (CacheKey) other;
            return Objects.equals(tenantId, that.tenantId)
                    && Objects.equals(catalogId, that.catalogId)
                    && Objects.equals(templateId, that.templateId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, catalogId, templateId);
        }
    }

    private static final class CacheEntry {

        private final JsonSchema schema;
        private final Instant storedAt;

        private CacheEntry(JsonSchema schema, Instant storedAt) {
            this.schema = schema;
            this.storedAt = storedAt;
        }
    }
}
