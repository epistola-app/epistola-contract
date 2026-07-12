package app.epistola.api.identity

/**
 * The contract version these server interfaces were generated from.
 *
 * Read from the bundled `epistola-contract-version.txt` resource, written at build
 * time from the OpenAPI spec's `info.version`. This mirrors the client library's
 * [ClientIdentity.contractVersion][app.epistola.client.identity.ClientIdentity] so a
 * server can reliably report the contract version it implements — e.g. the
 * `apiVersion` field of the `/ping` response — instead of falling back to
 * `"unknown"`.
 *
 * Example usage in a `/ping` implementation:
 * ```
 * PongDetailsDto(
 *     serverVersion = buildProperties.version,
 *     apiVersion = ServerContractInfo.contractVersion,
 *     // ...
 * )
 * ```
 */
object ServerContractInfo {
    /**
     * The contract version this server was built against, or `"unknown"` if the
     * version resource is absent (e.g. running from unbuilt sources).
     */
    val contractVersion: String by lazy { readResource("/epistola-contract-version.txt") }

    /**
     * The compatibility floor: the oldest contract version this build remains
     * wire-compatible with (the spec's `info.x-min-compatible-version`). Together
     * with [contractVersion] it defines the accepted peer range
     * `[minCompatibleContractVersion .. contractVersion]`, so a server can report
     * the range it supports without any hand-maintained constant. Falls back to
     * [contractVersion] when the resource is absent (a spec predating the floor →
     * a point range), or `"unknown"` if neither resource is present.
     */
    val minCompatibleContractVersion: String by lazy {
        readResourceOrNull("/epistola-contract-min-compatible.txt") ?: contractVersion
    }

    private fun readResource(path: String): String = readResourceOrNull(path) ?: "unknown"

    private fun readResourceOrNull(path: String): String? =
        ServerContractInfo::class.java.getResourceAsStream(path)
            ?.bufferedReader()?.readLine()?.trim()
}
