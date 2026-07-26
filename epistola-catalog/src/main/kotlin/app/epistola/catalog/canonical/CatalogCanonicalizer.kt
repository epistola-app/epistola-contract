package app.epistola.catalog.canonical

import app.epistola.catalog.archive.CatalogArchive
import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.ResourceDetail
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.InputStream
import java.security.MessageDigest

@JvmInline
value class CatalogFingerprint(val value: String)

object CatalogCanonicalizer {
    private val mapper = jsonMapper { addModule(kotlinModule()) }
    private val decimalReader = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun fingerprint(catalog: CatalogArchive): CatalogFingerprint {
        val entries = entries(catalog)
        val canonical = buildString {
            append(catalog.manifest.catalog.identityLine())
            entries.sortedBy(Entry::key).forEach { entry ->
                append(entry.key).append(' ')
                    .append(entry.canonicalJson).append(' ')
                    .append(entry.assetHash).append('\n')
            }
            append("deps ").append(canonicalDependencies(catalog.manifest.dependencies)).append('\n')
        }
        return CatalogFingerprint(sha256(canonical.byteInputStream()))
    }

    fun perResourceFingerprints(catalog: CatalogArchive): Map<String, String> = entries(catalog).sortedBy(Entry::key).associate { entry ->
        entry.key to sha256((entry.canonicalJson + "\u0000" + entry.assetHash).byteInputStream())
    }

    fun canonicalResourceJson(detail: ResourceDetail): String = mapper.writeValueAsString(sortKeys(mapper.valueToTree(detail.resource)))

    private fun entries(catalog: CatalogArchive): List<Entry> = catalog.resourceDetails.map { (key, detail) ->
        val detailPath = "resources/$key.json"
        val resourceNode = if (detailPath in catalog.paths) {
            catalog.content.open(detailPath).use { input ->
                decimalReader.readTree(input).get("resource")
            }
        } else {
            mapper.valueToTree(detail.resource)
        }
        val assetHash = (detail.resource as? AssetResource)?.let { asset ->
            val path = asset.contentUrl.removePrefix("./")
            if (path in catalog.paths) {
                catalog.content.open(path).use(::sha256)
            } else {
                "MISSING"
            }
        }.orEmpty()
        Entry(
            key = key,
            canonicalJson = mapper.writeValueAsString(sortKeys(resourceNode)),
            assetHash = assetHash,
        )
    }

    private fun canonicalDependencies(dependencies: List<DependencyRef>?): String = dependencies.orEmpty()
        .map { dependency ->
            val (type, catalogKey) = when (dependency) {
                is DependencyRef.Theme -> "theme" to dependency.catalogKey
                is DependencyRef.Stencil -> "stencil" to dependency.catalogKey
                is DependencyRef.CodeList -> "codeList" to dependency.catalogKey
                is DependencyRef.Font -> "font" to dependency.catalogKey
                is DependencyRef.Asset -> "asset" to ""
            }
            "$type|$catalogKey|${dependency.slug}"
        }
        .sorted()
        .joinToString(";")

    private fun CatalogInfo.identityLine(): String = "$slug $name ${description.orEmpty()}\n"

    private fun sortKeys(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> mapper.createObjectNode().also { sorted ->
            node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
        }
        is ArrayNode -> mapper.createArrayNode().also { sorted ->
            node.forEach { sorted.add(sortKeys(it)) }
        }
        else -> node
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Entry(
        val key: String,
        val canonicalJson: String,
        val assetHash: String,
    )
}
