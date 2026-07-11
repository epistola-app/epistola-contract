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
    val contractVersion: String by lazy {
        ServerContractInfo::class.java.getResourceAsStream("/epistola-contract-version.txt")
            ?.bufferedReader()?.readLine()?.trim()
            ?: "unknown"
    }
}
