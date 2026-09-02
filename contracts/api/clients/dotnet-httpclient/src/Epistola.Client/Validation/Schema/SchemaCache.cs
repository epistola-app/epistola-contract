// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Concurrent;
using NJsonSchema;

namespace Epistola.Client.Validation.Schema;

/// <summary>
/// Cache for compiled JSON Schemas keyed by (tenantId, catalogId, templateId).
/// </summary>
/// <remarks>
/// The catalog is part of the key, not decoration: the same template id in two catalogs of one
/// tenant is two different templates with two different schemas.
/// </remarks>
public interface ISchemaCache
{
    /// <summary>
    /// Returns a cached <see cref="JsonSchema"/>, or invokes <paramref name="loader"/> on a cache miss
    /// and stores the result. A <c>null</c> return means the template has no schema defined.
    /// </summary>
    JsonSchema? GetOrLoad(string tenantId, string catalogId, string templateId, Func<JsonSchema?> loader);
}

/// <summary>
/// Default TTL-based cache using <see cref="ConcurrentDictionary{TKey,TValue}"/>. Entries expire
/// after <c>ttl</c> from the time they were stored.
/// </summary>
public sealed class TtlSchemaCache : ISchemaCache
{
    private readonly TimeSpan _ttl;
    private readonly ConcurrentDictionary<(string TenantId, string CatalogId, string TemplateId), CacheEntry> _cache = new();

    public TtlSchemaCache()
        : this(TimeSpan.FromMinutes(5))
    {
    }

    public TtlSchemaCache(TimeSpan ttl)
    {
        _ttl = ttl;
    }

    public JsonSchema? GetOrLoad(string tenantId, string catalogId, string templateId, Func<JsonSchema?> loader)
    {
        var key = (tenantId, catalogId, templateId);
        if (_cache.TryGetValue(key, out var existing) && DateTimeOffset.UtcNow < existing.StoredAt + _ttl)
        {
            return existing.Schema;
        }

        var schema = loader();
        _cache[key] = new CacheEntry(schema, DateTimeOffset.UtcNow);
        return schema;
    }

    /// <summary>Evicts a specific entry (useful after template updates).</summary>
    public void Evict(string tenantId, string catalogId, string templateId) =>
        _cache.TryRemove((tenantId, catalogId, templateId), out _);

    /// <summary>Evicts all entries.</summary>
    public void EvictAll() => _cache.Clear();

    private readonly record struct CacheEntry(JsonSchema? Schema, DateTimeOffset StoredAt);
}
