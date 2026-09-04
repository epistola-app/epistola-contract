// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { createPrivateKey, randomUUID, sign, type KeyObject } from 'node:crypto'
import { readFileSync } from 'node:fs'

/** The JOSE algorithms the signer supports, decided by the key type. */
export type JwtAlgorithm = 'RS256' | 'ES256'

const DEFAULT_TOKEN_LIFETIME_SECONDS = 60

/**
 * Mints the short-lived self-signed JWTs Epistola accepts for consumer authentication.
 *
 * Each token carries:
 * - `iss` — the consumer ID
 * - `iat` — issued-at
 * - `exp` — `iat` plus the token lifetime
 * - `jti` — a fresh UUID per token, for replay protection
 *
 * Signed with `node:crypto` alone — RS256 for RSA keys, ES256 for EC P-256 — so this needs no JOSE
 * dependency. ES256 signatures are emitted as the raw `R || S` pair JOSE requires (`ieee-p1363`),
 * not the DER sequence Node produces by default, which every compliant verifier rejects.
 *
 * The client calls {@link createToken} per request so an expired token is never sent.
 *
 * ```ts
 * const signer = JwtSigner.builder()
 *   .consumerId('invoice-service')
 *   .privateKey(JwtSigner.loadPrivateKey('private.pem'))
 *   .build()
 * ```
 */
export class JwtSigner {
  private constructor(
    private readonly consumerId: string,
    private readonly privateKey: KeyObject,
    /** The JOSE algorithm this signer uses, decided by the key type. */
    readonly algorithm: JwtAlgorithm,
    private readonly tokenLifetimeSeconds: number,
  ) {}

  /** Creates a new {@link JwtSignerBuilder}. */
  static builder(): JwtSignerBuilder {
    return new JwtSignerBuilder()
  }

  /** Loads a private key from a PEM file (RSA or EC P-256, PKCS#8 `BEGIN PRIVATE KEY`). */
  static loadPrivateKey(path: string): KeyObject {
    return JwtSigner.parsePrivateKeyPem(readFileSync(path))
  }

  /** Parses a PEM-encoded private key (RSA or EC P-256, PKCS#8 `BEGIN PRIVATE KEY`). */
  static parsePrivateKeyPem(pem: string | Buffer): KeyObject {
    try {
      return createPrivateKey(pem)
    } catch (cause) {
      throw new TypeError('Failed to parse private key. Supported formats: RSA, EC (P-256) in PKCS#8 PEM format.', { cause })
    }
  }

  /** Creates a freshly signed JWT with a new `iat`, `exp` and `jti`. */
  createToken(): string {
    const nowSeconds = Math.floor(Date.now() / 1000)
    const header = { alg: this.algorithm, typ: 'JWT' }
    const claims = {
      iss: this.consumerId,
      iat: nowSeconds,
      exp: nowSeconds + this.tokenLifetimeSeconds,
      jti: randomUUID(),
    }
    const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`
    return `${signingInput}.${this.sign(signingInput).toString('base64url')}`
  }

  private sign(signingInput: string): Buffer {
    const data = Buffer.from(signingInput, 'ascii')
    return this.algorithm === 'ES256'
      ? sign('sha256', data, { key: this.privateKey, dsaEncoding: 'ieee-p1363' })
      : sign('sha256', data, this.privateKey)
  }

  /** @internal */
  static assemble(consumerId: string, privateKey: KeyObject, tokenLifetimeSeconds: number): JwtSigner {
    return new JwtSigner(consumerId, privateKey, detectAlgorithm(privateKey), tokenLifetimeSeconds)
  }
}

/** Fluent builder for {@link JwtSigner}. */
export class JwtSignerBuilder {
  private consumerIdValue: string | undefined
  private privateKeyValue: KeyObject | undefined
  private tokenLifetimeSecondsValue = DEFAULT_TOKEN_LIFETIME_SECONDS

  /** The consumer ID used as the JWT `iss` claim. */
  consumerId(consumerId: string): this {
    if (consumerId.trim() === '') {
      throw new RangeError('consumerId must not be blank')
    }
    this.consumerIdValue = consumerId
    return this
  }

  /**
   * The private key used to sign tokens, from {@link JwtSigner.loadPrivateKey} or
   * {@link JwtSigner.parsePrivateKeyPem}.
   */
  privateKey(privateKey: KeyObject): this {
    this.privateKeyValue = privateKey
    return this
  }

  /** Token lifetime in seconds (default: 60). */
  tokenLifetimeSeconds(seconds: number): this {
    if (!(seconds > 0)) {
      throw new RangeError('tokenLifetimeSeconds must be positive')
    }
    this.tokenLifetimeSecondsValue = seconds
    return this
  }

  /** Builds the immutable {@link JwtSigner}. */
  build(): JwtSigner {
    if (this.consumerIdValue === undefined) {
      throw new RangeError('consumerId is required')
    }
    if (this.privateKeyValue === undefined) {
      throw new RangeError('privateKey is required')
    }
    return JwtSigner.assemble(this.consumerIdValue, this.privateKeyValue, this.tokenLifetimeSecondsValue)
  }
}

function detectAlgorithm(key: KeyObject): JwtAlgorithm {
  if (key.type !== 'private') {
    throw new TypeError(`A private key is required, got a ${key.type} key`)
  }
  switch (key.asymmetricKeyType) {
    case 'rsa':
      return 'RS256'
    case 'ec': {
      const curve = key.asymmetricKeyDetails?.namedCurve
      if (curve !== 'prime256v1') {
        throw new TypeError(`Unsupported EC curve: ${curve ?? 'unknown'}. Supported: P-256 (prime256v1)`)
      }
      return 'ES256'
    }
    default:
      throw new TypeError(`Unsupported key type: ${key.asymmetricKeyType ?? 'unknown'}. Supported: RSA (2048+), EC (P-256)`)
  }
}

function base64url(value: string): string {
  return Buffer.from(value, 'utf8').toString('base64url')
}
