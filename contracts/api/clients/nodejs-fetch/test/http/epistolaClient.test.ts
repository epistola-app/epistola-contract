// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { generateKeyPairSync } from 'node:crypto'
import { test } from 'node:test'
import {
  ClientIdentity,
  ConsumersApi,
  EpistolaClient,
  FetchError,
  GenerationApi,
  JwtSigner,
  ProblemDetailException,
  ResponseError,
  SystemApi,
  TemplatesApi,
} from '../../src/index.js'
import { json, startServer, type TestServer } from '../helpers/server.js'

const VENDOR_JSON = 'application/vnd.epistola.v1+json'
const PROBLEM_JSON = 'application/problem+json'
const EMPTY_PAGE = { items: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } }
const PONG = { status: 'UP', timestamp: '2026-04-21T10:30:00Z' }

function client(server: TestServer): EpistolaClient {
  return EpistolaClient.builder(`${server.url}/api`)
    .identity(ClientIdentity.builder().nodeId('test-node').product('acme-billing', '9.9.9').build())
    .apiKey('epk_test')
    .build()
}

test('a generated call carries identity, the declared Accept, the vendor Content-Type and the API key', async () => {
  const server = await startServer((_, res) => json(res, 200, VENDOR_JSON, PONG))
  try {
    const pong = await new SystemApi(client(server)).ping({ pingRequest: { name: 'Test' } })
    assert.equal(pong.status, 'UP')
    const [request] = server.requests
    assert.equal(request.method, 'POST')
    assert.equal(request.path, '/api/ping')
    assert.equal(request.headers['x-ep-node-id'], 'test-node')
    assert.match(request.headers['user-agent'], /^epistola-contract\/[^ ]+ acme-billing\/9\.9\.9$/)
    assert.match(request.headers['content-type'], /^application\/vnd\.epistola\.v1\+json(;.*)?$/)
    assert.equal(request.headers.accept, VENDOR_JSON)
    assert.equal(request.headers.authorization, 'ApiKey epk_test')
    assert.equal(request.headers['x-api-key'], undefined)
    assert.deepEqual(JSON.parse(request.body.toString('utf8')), { name: 'Test' })
  } finally {
    await server.close()
  }
})

test('an operation with error responses asks for the problem document as well', async () => {
  const server = await startServer((_, res) => json(res, 200, VENDOR_JSON, EMPTY_PAGE))
  try {
    await new TemplatesApi(client(server)).listTemplates({ tenantId: 'acme-corp', catalogId: 'main' })
    const [request] = server.requests
    assert.equal(request.path, '/api/tenants/acme-corp/catalogs/main/templates')
    assert.match(request.headers.accept, /application\/vnd\.epistola\.v1\+json,\s*application\/problem\+json/)
  } finally {
    await server.close()
  }
})

test('a problem+json error is thrown as a typed ProblemDetailException', async () => {
  const server = await startServer((_, res) =>
    json(res, 400, `${PROBLEM_JSON}; charset=utf-8`, {
      type: 'https://epistola.app/errors/validation-error',
      title: 'Validation failed',
      status: 400,
      detail: 'The request body failed validation',
      errors: [{ field: 'name', message: 'must not be blank' }],
    }),
  )
  try {
    await assert.rejects(
      new TemplatesApi(client(server)).listTemplates({ tenantId: 'acme-corp', catalogId: 'main' }),
      (error: unknown) => {
        assert.ok(error instanceof ProblemDetailException)
        assert.equal(error.typeSlug, 'validation-error')
        assert.equal(error.statusCode, 400)
        assert.equal(error.errors[0].field, 'name')
        assert.equal(error.response.status, 400)
        return true
      },
    )
  } finally {
    await server.close()
  }
})

test('an error without a problem document falls through to the generic ResponseError', async () => {
  const server = await startServer((_, res) => json(res, 404, 'application/json', { message: 'nope' }))
  try {
    await assert.rejects(new TemplatesApi(client(server)).listTemplates({ tenantId: 'acme-corp', catalogId: 'main' }), (error: unknown) => {
      assert.ok(error instanceof ResponseError)
      assert.ok(!(error instanceof ProblemDetailException))
      return true
    })
  } finally {
    await server.close()
  }
})

test('a self-signed JWT is minted fresh for every request', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const signer = JwtSigner.builder().consumerId('conformance-consumer').privateKey(privateKey).build()
  const server = await startServer((_, res) => json(res, 200, VENDOR_JSON, EMPTY_PAGE))
  try {
    const jwtClient = EpistolaClient.builder(`${server.url}/api`).jwtSigner(signer).build()
    const templates = new TemplatesApi(jwtClient)
    await templates.listTemplates({ tenantId: 'acme-corp', catalogId: 'main' })
    await templates.listTemplates({ tenantId: 'acme-corp', catalogId: 'main' })
    const tokens = server.requests.map((request) => request.headers.authorization)
    assert.match(tokens[0], /^Bearer [A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/)
    assert.notEqual(tokens[0], tokens[1])
    assert.match(jwtClient.requestHeaders().Authorization, /^Bearer /)
  } finally {
    await server.close()
  }
})

test('a partial update sends only the fields the caller set', async () => {
  const server = await startServer((_, res) =>
    json(res, 200, VENDOR_JSON, {
      id: 'billing-service',
      tenantId: 'acme-corp',
      name: 'Billing Service',
      authMethod: 'api-key',
      status: 'active',
      requestedPermissions: {},
      grantedPermissions: {},
      nodes: [],
      createdAt: '2026-01-20T09:00:00Z',
    }),
  )
  try {
    await new ConsumersApi(client(server)).updateConsumer({ tenantId: 'acme-corp', consumerId: 'billing-service', updateConsumerRequest: { name: 'Billing Service' } })
    const [request] = server.requests
    assert.equal(request.method, 'PATCH')
    assert.deepEqual(JSON.parse(request.body.toString('utf8')), { name: 'Billing Service' })
  } finally {
    await server.close()
  }
})

test('a binary download arrives byte for byte', async () => {
  const bytes = Buffer.concat([Buffer.from('%PDF-1.7\n'), Buffer.from(Array.from({ length: 256 }, (_, i) => i)), Buffer.from('\n%%EOF\n')])
  const server = await startServer((_, res) => {
    res.writeHead(200, { 'Content-Type': 'application/pdf', 'Content-Length': String(bytes.length) })
    res.end(bytes)
  })
  try {
    const blob = await new GenerationApi(client(server)).downloadDocument({ tenantId: 'acme-corp', documentId: '99999999-9999-4999-8999-000000000001' })
    assert.deepEqual(Buffer.from(await blob.arrayBuffer()), bytes)
    assert.equal(server.requests[0].headers.accept, 'application/pdf, application/problem+json')
  } finally {
    await server.close()
  }
})

test('a request timeout aborts a request that hangs', async () => {
  const server = await startServer((_, res) => {
    setTimeout(() => json(res, 200, VENDOR_JSON, PONG), 2_000).unref()
  })
  try {
    const slow = EpistolaClient.builder(`${server.url}/api`).requestTimeoutMs(100).build()
    await assert.rejects(new SystemApi(slow).ping({}), (error: unknown) => error instanceof FetchError)
  } finally {
    await server.close()
  }
})

test('requestHeaders() hands the collector the same identity and authorization', () => {
  const headers = EpistolaClient.builder('https://x.example/api', ' epk_test ')
    .identity(ClientIdentity.builder().nodeId('n1').build())
    .build()
    .requestHeaders()
  assert.equal(headers.Authorization, 'ApiKey epk_test')
  assert.equal(headers['X-EP-Node-Id'], 'n1')
  assert.ok(headers['User-Agent'].startsWith('epistola-contract/'))
  assert.deepEqual(EpistolaClient.builder('https://x.example/api').build().requestHeaders(), {})
})

test('the builder validates its inputs and normalises the base URL', () => {
  assert.throws(() => EpistolaClient.builder().build(), /baseUrl is required/)
  assert.throws(() => EpistolaClient.builder('/api').build(), /absolute URL/)
  assert.throws(() => EpistolaClient.builder('https://x.example/api?x=1').build(), /query string/)
  assert.throws(() => EpistolaClient.builder('https://x.example/api').apiKey(' '), /apiKey must not be blank/)
  assert.throws(() => EpistolaClient.builder('https://x.example/api').requestTimeoutMs(0), RangeError)
  const built = EpistolaClient.builder('https://x.example/api/').build()
  assert.equal(built.baseUrl, 'https://x.example/api')
  assert.equal(built.basePath, 'https://x.example/api')
})

test('apiKey and jwtSigner are mutually exclusive; the last one wins', () => {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
  const signer = JwtSigner.builder().consumerId('svc').privateKey(privateKey).build()
  const jwt = EpistolaClient.builder('https://x.example/api').apiKey('epk').jwtSigner(signer).build()
  assert.match(jwt.authorizationHeader()!, /^Bearer /)
  const key = EpistolaClient.builder('https://x.example/api').jwtSigner(signer).apiKey('epk').build()
  assert.equal(key.authorizationHeader(), 'ApiKey epk')
  assert.throws(() => new EpistolaClient({ baseUrl: 'https://x.example/api', apiKey: 'epk', jwtSigner: signer }), /mutually exclusive/)
})
