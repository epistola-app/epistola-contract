// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.model

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI

/**
 * RFC 9457 Problem Details, hand-written rather than generated so it can carry a catch-all for
 * extension members the base five fields don't model.
 *
 * A generated data class is a closed set of properties: any JSON member outside `type`, `title`,
 * `status`, `detail`, `instance` is silently dropped by Jackson on the way in. `errors[]`
 * (`ValidationProblemDetail`) and `validationErrors{}` (`DataModelValidationProblemDetail`) get
 * away with this because [app.epistola.client.error.parseProblem] reads them off the raw JSON tree
 * separately — but that only works for extension members someone anticipated. Epistola's own
 * `catalog-schema-too-old` carries `version`/`baselineVersion`, unregistered anywhere a generator
 * could see them, and a consumer rendering that problem into an operator-actionable message has no
 * way to reach them through a closed model. [extensions] is the general escape hatch: every problem
 * member this class does not name by field, however the contract grows.
 *
 * Substituted for the generated model via this build's `schemaMappings` (same fully-qualified name,
 * so no call site changes), which is why this lives in the `model` package rather than `error`.
 */
data class ProblemDetail
@JsonCreator
constructor(
    @get:JsonProperty("type")
    val type: URI = URI.create("about:blank"),
    @get:JsonProperty("title")
    val title: String,
    @get:JsonProperty("status")
    val status: Int,
    @get:JsonProperty("detail")
    val detail: String? = null,
    @get:JsonProperty("instance")
    val instance: String? = null,
    /**
     * Every problem member outside the five named above, keyed by its JSON name. Populated by
     * Jackson's creator-parameter any-setter (a single `Map`-typed `@JsonCreator` parameter
     * annotated `@JsonAnySetter` collects every property that did not bind to a named one) and
     * re-emitted the same way by [JsonAnyGetter] if this type is ever serialized rather than only
     * parsed.
     */
    @get:JsonAnyGetter
    @param:JsonAnySetter
    val extensions: Map<String, Any?> = emptyMap(),
)
