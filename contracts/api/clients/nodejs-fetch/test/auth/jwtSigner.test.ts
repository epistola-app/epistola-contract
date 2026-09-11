// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { generateKeyPairSync, verify, type KeyObject } from 'node:crypto'
import { test } from 'node:test'
import { JwtSigner } from '../../src/index.js'

function ecKey(): { privateKey: KeyObject; publicKey: KeyObject } {
  return generateKeyPairSync('ec', { namedCurve: 'P-256' })
}

function rsaKey(): { privateKey: KeyObject; publicKey: KeyObject } {
  return generateKeyPairSync('rsa', { modulusLength: 2048 })
}

function decode(token: string): { header: Record<string, unknown>; claims: Record<string, unknown>; signature: Buffer; signingInput: string } {
  const [header, claims, signature] = token.split('.')
  return {
    header: JSON.parse(Buffer.from(header, 'base64url').toString('utf8')),
    claims: JSON.parse(Buffer.from(claims, 'base64url').toString('utf8')),
    signature: Buffer.from(signature, 'base64url'),
    signingInput: `${header}.${claims}`,
  }
}

test('signs ES256 tokens as a raw R||S pair that verifies, with the agreed claims', () => {
  const { privateKey, publicKey } = ecKey()
  const signer = JwtSigner.builder().consumerId('invoice-service').privateKey(privateKey).build()
  assert.equal(signer.algorithm, 'ES256')

  const { header, claims, signature, signingInput } = decode(signer.createToken())
  assert.deepEqual(header, { alg: 'ES256', typ: 'JWT' })
  // 64 bytes: JOSE's raw R||S, not the DER SEQUENCE Node emits by default, which verifiers reject.
  assert.equal(signature.length, 64)
  assert.ok(verify('sha256', Buffer.from(signingInput, 'ascii'), { key: publicKey, dsaEncoding: 'ieee-p1363' }, signature))
  assert.equal(claims.iss, 'invoice-service')
  assert.match(String(claims.jti), /^[0-9a-f-]{36}$/)
  assert.ok(typeof claims.iat === 'number' && typeof claims.exp === 'number' && claims.exp > claims.iat)
  // Seconds, not milliseconds.
  assert.ok(Math.abs((claims.iat as number) - Date.now() / 1000) < 5)
})

test('signs RS256 tokens for RSA keys', () => {
  const { privateKey, publicKey } = rsaKey()
  const signer = JwtSigner.builder().consumerId('svc').privateKey(privateKey).build()
  assert.equal(signer.algorithm, 'RS256')
  const { header, signature, signingInput } = decode(signer.createToken())
  assert.equal(header.alg, 'RS256')
  assert.ok(verify('sha256', Buffer.from(signingInput, 'ascii'), publicKey, signature))
})

test('two tokens have distinct jti values', () => {
  const signer = JwtSigner.builder().consumerId('svc').privateKey(ecKey().privateKey).build()
  assert.notEqual(decode(signer.createToken()).claims.jti, decode(signer.createToken()).claims.jti)
})

test('the token lifetime is respected and defaults to 60 seconds', () => {
  const key = ecKey().privateKey
  const custom = decode(JwtSigner.builder().consumerId('svc').privateKey(key).tokenLifetimeSeconds(120).build().createToken()).claims
  assert.equal((custom.exp as number) - (custom.iat as number), 120)
  const standard = decode(JwtSigner.builder().consumerId('svc').privateKey(key).build().createToken()).claims
  assert.equal((standard.exp as number) - (standard.iat as number), 60)
})

test('parses PKCS#8 PEM for both key types', () => {
  const rsa = JwtSigner.parsePrivateKeyPem(rsaKey().privateKey.export({ type: 'pkcs8', format: 'pem' }))
  assert.equal(rsa.asymmetricKeyType, 'rsa')
  const ec = JwtSigner.parsePrivateKeyPem(ecKey().privateKey.export({ type: 'pkcs8', format: 'pem' }) as string)
  assert.equal(ec.asymmetricKeyType, 'ec')
})

test('rejects missing settings, unparseable PEM and unsupported keys', () => {
  assert.throws(() => JwtSigner.builder().privateKey(rsaKey().privateKey).build(), /consumerId is required/)
  assert.throws(() => JwtSigner.builder().consumerId('svc').build(), /privateKey is required/)
  assert.throws(() => JwtSigner.builder().consumerId(' '), RangeError)
  assert.throws(() => JwtSigner.builder().consumerId('svc').tokenLifetimeSeconds(0), RangeError)
  assert.throws(() => JwtSigner.parsePrivateKeyPem('not a pem'), TypeError)
  const p384 = generateKeyPairSync('ec', { namedCurve: 'P-384' }).privateKey
  assert.throws(() => JwtSigner.builder().consumerId('svc').privateKey(p384).build(), /Unsupported EC curve/)
  const ed25519 = generateKeyPairSync('ed25519').privateKey
  assert.throws(() => JwtSigner.builder().consumerId('svc').privateKey(ed25519).build(), /Unsupported key type/)
})
