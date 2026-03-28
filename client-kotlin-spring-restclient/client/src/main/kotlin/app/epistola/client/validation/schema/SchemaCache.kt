package app.epistola.client.validation.schema

import com.networknt.schema.JsonSchema
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for compiled JSON Schemas keyed by (tenantId, templateId).
 */
fun interface SchemaCache {
    /**
     * Returns a cached [JsonSchema], or invokes [loader] on cache miss and stores the result.
     * A `null` return means the template has no schema defined.
     */
    fun getOrLoad(tenantId: String, templateId: String, loader: () -> JsonSchema?): JsonSchema?
}

/**
 * Default TTL-based cache using [ConcurrentHashMap].
 * Entries expire after [ttl] from the time they were stored.
 */
class TtlSchemaCache(private val ttl: Duration = Duration.ofMinutes(5)) : SchemaCache {

    private data class CacheKey(val tenantId: String, val templateId: String)
    private data class CacheEntry(val schema: JsonSchema?, val storedAt: Instant)

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    override fun getOrLoad(tenantId: String, templateId: String, loader: () -> JsonSchema?): JsonSchema? {
        val key = CacheKey(tenantId, templateId)
        val existing = cache[key]
        if (existing != null && Instant.now().isBefore(existing.storedAt.plus(ttl))) {
            return existing.schema
        }
        val schema = loader()
        cache[key] = CacheEntry(schema, Instant.now())
        return schema
    }

    /** Evict a specific entry (useful after template updates). */
    fun evict(tenantId: String, templateId: String) {
        cache.remove(CacheKey(tenantId, templateId))
    }

    /** Evict all entries. */
    fun evictAll() {
        cache.clear()
    }
}
