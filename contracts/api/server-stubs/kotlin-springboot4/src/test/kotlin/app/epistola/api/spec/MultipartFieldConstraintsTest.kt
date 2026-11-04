// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.api.spec

import jakarta.validation.ElementKind
import jakarta.validation.Path
import jakarta.validation.Validation
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.RequestParam
import org.yaml.snakeyaml.Yaml
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generator writes no constraints on a multipart/form-data field, so the build adds them (see
 * the multipart rewrite in `build.gradle.kts`). Every constrained text field of a multipart body in
 * the spec must carry them, with the spec's own values, and Bean Validation must refuse a value that
 * breaks them.
 */
class MultipartFieldConstraintsTest {
    private class Field(val operationId: String, val tag: String, val name: String, val schema: Map<String, Any?>)

    @Suppress("UNCHECKED_CAST")
    private val fields: List<Field> = run {
        val resource = javaClass.getResourceAsStream("/openapi/epistola-contract.yaml")
        assertNotNull(resource, "bundled spec missing from classpath")
        val spec = resource.use { Yaml().load<Map<String, Any?>>(it) }
        (spec["paths"] as Map<String, Map<String, Any?>>).values.flatMap { item ->
            item.values.filterIsInstance<Map<String, Any?>>().flatMap { operation ->
                val content = (operation["requestBody"] as? Map<String, Any?>)?.get("content") as? Map<String, Map<String, Any?>>
                val schema = content?.get("multipart/form-data")?.get("schema") as? Map<String, Any?>
                val properties = schema?.get("properties") as? Map<String, Map<String, Any?>> ?: emptyMap()
                properties.filterValues { it["format"] != "binary" && listOf("pattern", "minLength", "maxLength").any(it::containsKey) }
                    .map { (name, property) -> Field(operation["operationId"] as String, (operation["tags"] as List<String>).first(), name, property) }
            }
        }
    }

    private fun method(field: Field): Pair<Class<*>, Method> {
        val api = Class.forName("app.epistola.api.${field.tag.replace(" ", "")}Api")
        return api to api.methods.single { it.name == field.operationId }
    }

    private fun parameterIndex(method: Method, name: String): Int = method.parameterAnnotations.indexOfFirst { annotations ->
        annotations.filterIsInstance<RequestParam>().any { it.value == name }
    }.also { assertTrue(it >= 0, "${method.name} has no @RequestParam(\"$name\")") }

    @Test
    fun `the spec has the constrained multipart fields this test is about`() {
        val names = fields.map { "${it.operationId}.${it.name}" }
        assertTrue("uploadImage.mediaType" in names && "importCatalog.registry" in names, "found only $names")
    }

    @Test
    fun `every constrained multipart field carries the spec's constraints`() {
        for (field in fields) {
            val (_, method) = method(field)
            val annotations = method.parameterAnnotations[parameterIndex(method, field.name)]
            val where = "${field.operationId} ${field.name}"
            val size = annotations.filterIsInstance<Size>().singleOrNull()
            if ("minLength" in field.schema || "maxLength" in field.schema) {
                assertNotNull(size, "$where: no @Size")
                assertEquals((field.schema["minLength"] as? Number)?.toInt() ?: 0, size.min, "$where: min")
                assertEquals((field.schema["maxLength"] as? Number)?.toInt() ?: Int.MAX_VALUE, size.max, "$where: max")
            }
            (field.schema["pattern"] as? String)?.let { pattern ->
                assertEquals<String?>(pattern, annotations.filterIsInstance<Pattern>().singleOrNull()?.regexp, "$where: @Pattern")
            }
        }
    }

    @Test
    fun `bean validation refuses a multipart field that breaks its constraints`() {
        val validator = Validation.buildDefaultValidatorFactory().use { it.validator }
        for (field in fields) {
            val (api, method) = method(field)
            val maxLength = (field.schema["maxLength"] as? Number)?.toInt() ?: continue
            val args = arrayOfNulls<Any>(method.parameterCount)
            val index = parameterIndex(method, field.name)
            args[index] = "a".repeat(maxLength + 1)
            val implementation = Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, _, _ -> null }
            val violations = validator.forExecutables().validateParameters(implementation, method, args)
                .filter { violation ->
                    violation.propertyPath.any { it.kind == ElementKind.PARAMETER && it.`as`(Path.ParameterNode::class.java).parameterIndex == index }
                }
            assertTrue(violations.any { it.constraintDescriptor.annotation is Size }, "${field.operationId} ${field.name}: no @Size violation in $violations")
        }
    }
}
