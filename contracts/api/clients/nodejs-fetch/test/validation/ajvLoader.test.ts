// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { AJV_INSTALL_HINT, loadAjv } from '../../src/validation/ajvLoader.js'

test('the real peers load and unwrap to constructors and the formats plugin', async () => {
  const runtime = await loadAjv()
  assert.equal(typeof runtime.Ajv, 'function')
  assert.equal(typeof runtime.Ajv2019, 'function')
  assert.equal(typeof runtime.Ajv2020, 'function')
  assert.equal(typeof runtime.addFormats, 'function')
  const ajv = new runtime.Ajv({ allErrors: true, strict: false })
  runtime.addFormats(ajv)
  const validate = ajv.compile({ type: 'string', format: 'email' })
  assert.equal(validate('a@example.com'), true)
  assert.equal(validate('nope'), false)
  assert.equal(validate.errors?.[0]?.keyword, 'format')
  // Loaded once per process.
  assert.equal(await loadAjv(), runtime)
})

test('a missing peer dependency rejects with an error that says what to install', async () => {
  const notFound = (specifier: string) => Object.assign(new Error(`Cannot find package '${specifier}'`), { code: 'ERR_MODULE_NOT_FOUND' })
  await assert.rejects(
    loadAjv(async (specifier) => {
      throw notFound(specifier)
    }),
    (error: unknown) => {
      assert.ok(error instanceof Error)
      assert.equal(error.message, AJV_INSTALL_HINT)
      assert.match(error.message, /npm install ajv ajv-formats/)
      assert.equal((error.cause as { code?: string }).code, 'ERR_MODULE_NOT_FOUND')
      return true
    },
  )
})
