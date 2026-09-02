// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// Shared reading of the bundled OpenAPI spec for the contract-derived Gradle builds
// (the Kotlin Spring client, the Jakarta EE client, and the Kotlin server stubs).
//
// Each build generates the same things from the contract — the problem-type registry, the
// client-identity convention, the constraint validators, the contract version — but in its own
// language. What is identical between them is *which* parts of the spec those come from and what
// counts as a constraint; what differs is only the emitted syntax. That reading lives here, once,
// so a new constraint keyword or a change to a registry's shape is one edit rather than several
// that can silently disagree.
//
// Applied from each client's build.gradle.kts via
//   apply(from = "$rootDir/../../build-logic/contract-spec-model.gradle.kts")
//
// and used as
//   @Suppress("UNCHECKED_CAST")
//   val specModel = extra["epistolaSpecModel"] as (Map<String, Any>) -> Map<String, Any>
//   val model = specModel(org.yaml.snakeyaml.Yaml().load(bundledSpec.readText()))
//
// It takes the already-parsed document rather than the file: snakeyaml reaches each build through
// the OpenAPI Generator plugin's classpath, which an `apply(from =)` script is not compiled
// against. Parsing is one line and stays local; the interpretation — which is the part that can
// drift — is here. The values are plain data (maps, lists, strings), so they cross the
// extra-properties boundary without either build needing a shared type.

/**
 * Turns a parsed bundled spec into the model both clients generate from:
 *
 * - `version`: `String` — `info.version`, for the contract-version resource.
 * - `problemTypeBase`: `String` — `x-problem-types.base`.
 * - `problemTypes`: `List<Map<String, Any?>>` — one entry per registered problem type, with
 *   `slug`, `status`, `description` (whitespace collapsed) and `constantName` (the slug in
 *   MACRO_CASE, which every target language happens to agree on).
 * - `constrainedSchemas`: `List<Map<String, Any?>>` — one entry per object schema that declares
 *   at least one constraint, with `name` and `fields`. Each field carries `property`, `nullable`
 *   (not required, or explicitly nullable) and `constraints` — a list of maps naming the
 *   `kind` (`length`, `pattern`, `range`, `minItems`) and its bounds.
 * - `clientIdentity`: `Map<String, String>` — `x-client-identity`, the header name and
 *   `User-Agent` grammar every client writes and the server parses. Both sides generate their
 *   constants from this, because a mismatch here makes every request from that client
 *   unidentifiable and nothing else would catch it.
 * - `problemExtensionMembers`: `Map<String, List<String>>` — for each problem schema the registry
 *   names, the members it adds on top of the base `ProblemDetail` (`ValidationProblemDetail` →
 *   `["errors"]`). The server writes these members and the clients read them by name out of the
 *   raw body, so a rename would make the extension silently vanish rather than fail.
 *
 * Throws when the spec is missing the pieces the clients depend on, so a truncated or restructured
 * bundle fails the build rather than quietly generating an empty registry.
 */
@Suppress("UNCHECKED_CAST")
val epistolaSpecModel: (Map<String, Any>) -> Map<String, Any> = { document ->
    val info = document["info"] as? Map<String, Any>
        ?: throw GradleException("the bundled spec has no info block")
    val version = info["version"] as? String
        ?: throw GradleException("the bundled spec has no info.version")

    val registry = document["x-problem-types"] as? Map<String, Any>
        ?: throw GradleException(
            "bundled spec has no x-problem-types extension — the problem-slug constants cannot be generated",
        )
    val problemTypeBase = registry["base"] as? String
        ?: throw GradleException("x-problem-types.base is missing from the bundled spec")
    val rawTypes = (registry["types"] as? List<Map<String, Any>>).orEmpty()
    if (rawTypes.size < 8) {
        throw GradleException(
            "x-problem-types lists only ${rawTypes.size} problem types (expected at least 8) — " +
                "was the registry truncated?",
        )
    }

    val problemTypes = rawTypes.map { entry ->
        val slug = entry["slug"] as? String
            ?: throw GradleException("an x-problem-types entry has no slug: $entry")
        mapOf(
            "slug" to slug,
            "status" to entry["status"],
            "description" to (entry["description"] as? String).orEmpty().replace(Regex("\\s+"), " ").trim(),
            "constantName" to slug.uppercase().replace('-', '_'),
        )
    }

    val identity = document["x-client-identity"] as? Map<String, Any>
        ?: throw GradleException(
            "the bundled spec has no x-client-identity extension — the client-identity constants " +
                "cannot be generated",
        )
    val clientIdentity = listOf(
        "nodeIdHeader",
        "contractProduct",
        "userAgentProductSeparator",
        "userAgentVersionSeparator",
    ).associateWith { key ->
        identity[key] as? String
            ?: throw GradleException("x-client-identity.$key is missing from the bundled spec")
    }

    val schemas = (document["components"] as? Map<String, Any>)?.get("schemas") as? Map<String, Any>
        ?: throw GradleException("the bundled spec has no components.schemas")

    // The members each problem schema adds to the RFC 9457 base. `ProblemDetail` is the base by
    // name — it is the RFC's own, and the spec's plain problem schema — so anything a registered
    // problem schema declares beyond it is an extension member.
    //
    // The extension schemas are allOf-composed (`[{$ref: ProblemDetail}, {properties: {...}}]`),
    // so the members are what the inline branches declare; a `$ref` branch contributes the base.
    fun propertiesOf(schemaName: String): Set<String> {
        val schema = schemas[schemaName] as? Map<String, Any>
            ?: throw GradleException("x-problem-types names schema '$schemaName', which components.schemas has not")
        val direct = (schema["properties"] as? Map<String, Any>).orEmpty().keys
        val composed = (schema["allOf"] as? List<Map<String, Any>>).orEmpty()
            .filterNot { it.containsKey("\$ref") }
            .flatMap { (it["properties"] as? Map<String, Any>).orEmpty().keys }
        return direct + composed
    }

    val baseProblemProperties = propertiesOf("ProblemDetail")
    val problemExtensionMembers = rawTypes
        .mapNotNull { it["schema"] as? String }
        .distinct()
        .filter { it != "ProblemDetail" }
        .associateWith { schemaName -> (propertiesOf(schemaName) - baseProblemProperties).toList() }
    problemExtensionMembers.forEach { (schemaName, members) ->
        if (members.isEmpty()) {
            throw GradleException(
                "problem schema '$schemaName' adds nothing to ProblemDetail — either it is redundant " +
                    "or the base problem schema gained a member that belongs only to the extension",
            )
        }
    }

    val constrainedSchemas = mutableListOf<Map<String, Any?>>()

    for ((schemaName, schemaDefinition) in schemas) {
        val schema = schemaDefinition as? Map<String, Any> ?: continue
        if (schema["type"] != "object") continue

        val required = (schema["required"] as? List<String>).orEmpty()
        val properties = (schema["properties"] as? Map<String, Any>) ?: continue

        val fields = mutableListOf<Map<String, Any?>>()

        for ((propertyName, propertyDefinition) in properties) {
            val property = propertyDefinition as? Map<String, Any> ?: continue
            // Skip $ref properties — the referenced type carries its own constraints, and its own
            // generated validator.
            if (property.containsKey("\$ref")) continue

            val declaredType = property["type"]
            val explicitlyNullable = declaredType is List<*> && declaredType.contains("null")
            val baseType = when (declaredType) {
                is String -> declaredType
                is List<*> -> declaredType.firstOrNull { it != "null" }?.toString()
                else -> null
            } ?: continue

            val constraints = mutableListOf<Map<String, Any?>>()

            when (baseType) {
                "string" -> {
                    val minLength = (property["minLength"] as? Number)?.toInt()
                    val maxLength = (property["maxLength"] as? Number)?.toInt()
                    if (minLength != null || maxLength != null) {
                        constraints.add(mapOf("kind" to "length", "min" to minLength, "max" to maxLength))
                    }
                    val pattern = property["pattern"] as? String
                    if (pattern != null) {
                        constraints.add(mapOf("kind" to "pattern", "pattern" to pattern))
                    }
                }

                "integer" -> {
                    val minimum = (property["minimum"] as? Number)?.toLong()
                    val maximum = (property["maximum"] as? Number)?.toLong()
                    if (minimum != null || maximum != null) {
                        constraints.add(mapOf("kind" to "range", "min" to minimum, "max" to maximum))
                    }
                }

                "array" -> {
                    val minItems = (property["minItems"] as? Number)?.toInt()
                    if (minItems != null) {
                        constraints.add(mapOf("kind" to "minItems", "min" to minItems))
                    }
                }
            }

            if (constraints.isNotEmpty()) {
                fields.add(
                    mapOf(
                        "property" to propertyName,
                        "nullable" to (propertyName !in required || explicitlyNullable),
                        "constraints" to constraints.toList(),
                    ),
                )
            }
        }

        if (fields.isNotEmpty()) {
            constrainedSchemas.add(mapOf("name" to schemaName, "fields" to fields.toList()))
        }
    }

    if (constrainedSchemas.isEmpty()) {
        throw GradleException(
            "the bundled spec produced no constrained schemas — either it lost all its constraints " +
                "or the schema-walking code in contract-spec-model.gradle.kts no longer matches its structure",
        )
    }

    mapOf(
        "version" to version,
        "problemTypeBase" to problemTypeBase,
        "problemTypes" to problemTypes.toList(),
        "clientIdentity" to clientIdentity,
        "problemExtensionMembers" to problemExtensionMembers,
        "constrainedSchemas" to constrainedSchemas.toList(),
    )
}

extra["epistolaSpecModel"] = epistolaSpecModel
