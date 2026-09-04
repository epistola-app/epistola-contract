// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { hostname } from 'node:os'
import { ContractIdentity } from '../generated/contractIdentity.js'
import { CONTRACT_VERSION } from '../generated/contractVersion.js'

/**
 * Client identity headers required on every Epistola API request.
 *
 * The `User-Agent` always starts with `epistola-contract/{contractVersion}`. Additional product
 * tokens can be appended to describe the full software stack:
 *
 * ```ts
 * const identity = ClientIdentity.builder()
 *   .nodeId('my-pod-123')
 *   .product('valtimo-epistola-plugin', '1.2.0')
 *   .product('gzac', '5.0.0')
 *   .build()
 * ```
 *
 * produces
 *
 * ```
 * User-Agent: epistola-contract/1.2.0 valtimo-epistola-plugin/1.2.0 gzac/5.0.0
 * X-EP-Node-Id: my-pod-123
 * ```
 *
 * The header name and the `User-Agent` grammar are generated from the spec's `x-client-identity`
 * extension, which the server module parses from the same registry.
 */
export class ClientIdentity {
  /** The contract version this client library was built against. */
  static readonly CONTRACT_VERSION: string = CONTRACT_VERSION

  /** The header carrying the node identifier (`X-EP-Node-Id`). */
  static readonly NODE_ID_HEADER: string = ContractIdentity.NODE_ID_HEADER

  /** The `User-Agent` header name. */
  static readonly USER_AGENT_HEADER = 'User-Agent'

  private constructor(
    /** The assembled `User-Agent` header value. */
    readonly userAgent: string,
    /** The `X-EP-Node-Id` header value. */
    readonly nodeId: string,
  ) {}

  /** Creates a new {@link ClientIdentityBuilder}. */
  static builder(): ClientIdentityBuilder {
    return new ClientIdentityBuilder()
  }

  /** The identity headers as a plain object, for merging into request headers. */
  headers(): Record<string, string> {
    return {
      [ClientIdentity.USER_AGENT_HEADER]: this.userAgent,
      [ClientIdentity.NODE_ID_HEADER]: this.nodeId,
    }
  }

  /** @internal */
  static assemble(userAgent: string, nodeId: string): ClientIdentity {
    return new ClientIdentity(userAgent, nodeId)
  }
}

/** Fluent builder for {@link ClientIdentity}. */
export class ClientIdentityBuilder {
  private nodeIdValue: string | undefined
  private readonly products: Array<{ name: string; version: string }> = []

  /** Sets the node identifier (e.g. Kubernetes pod name, hostname). Defaults to the local hostname. */
  nodeId(nodeId: string): this {
    if (nodeId.trim() === '') {
      throw new RangeError('nodeId must not be blank')
    }
    this.nodeIdValue = nodeId
    return this
  }

  /** Appends a product/version pair to the `User-Agent`, after the `epistola-contract/{version}` token. */
  product(name: string, version: string): this {
    if (name.trim() === '') {
      throw new RangeError('Product name must not be blank')
    }
    if (version.trim() === '') {
      throw new RangeError('Product version must not be blank')
    }
    if (name.includes(ContractIdentity.VERSION_SEPARATOR) || /\s/.test(name)) {
      throw new RangeError(`Product name must not contain '${ContractIdentity.VERSION_SEPARATOR}' or whitespace`)
    }
    if (/\s/.test(version)) {
      throw new RangeError('Product version must not contain whitespace')
    }
    this.products.push({ name, version })
    return this
  }

  /** Builds the immutable {@link ClientIdentity}. */
  build(): ClientIdentity {
    const tokens = [
      { name: ContractIdentity.CONTRACT_PRODUCT, version: CONTRACT_VERSION },
      ...this.products,
    ]
    const userAgent = tokens
      .map((token) => `${token.name}${ContractIdentity.VERSION_SEPARATOR}${token.version}`)
      .join(ContractIdentity.PRODUCT_SEPARATOR)
    return ClientIdentity.assemble(userAgent, this.nodeIdValue ?? hostname())
  }
}
