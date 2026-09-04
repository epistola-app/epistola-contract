// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Guard test: the hand-written problem-type constants must not drift from the build-time generated
 * ones (which come straight from the spec's x-problem-types). The Node.js analogue of the
 * Kotlin / .NET / Python ProblemRegistryTest.
 */

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { GENERATED_PROBLEM_TYPE_BASE, KnownProblemSlugs, ProblemExtensionMembers, TYPE_BASE, slugFor, typeFor } from '../../src/index.js'

// The canonical slugs and their documented string values (docs/error-types.md / the spec's
// x-problem-types registry). If the registry changes, this test — and the generated
// KnownProblemSlugs — must be updated together.
const EXPECTED_SLUGS: Record<string, string> = {
  VALIDATION_ERROR: 'validation-error',
  BAD_REQUEST: 'bad-request',
  UNAUTHORIZED: 'unauthorized',
  API_KEY_AUTH_DISABLED: 'api-key-auth-disabled',
  FORBIDDEN: 'forbidden',
  NOT_FOUND: 'not-found',
  CONFLICT: 'conflict',
  DATA_MODEL_VALIDATION_ERROR: 'data-model-validation-error',
  RATE_LIMITED: 'rate-limited',
}

test('the hand-written type base matches the generated one', () => {
  assert.equal(TYPE_BASE, GENERATED_PROBLEM_TYPE_BASE)
})

test('every canonical slug constant has its documented value', () => {
  for (const [constant, slug] of Object.entries(EXPECTED_SLUGS)) {
    assert.equal((KnownProblemSlugs as Record<string, string>)[constant], slug, `missing or wrong slug constant: ${constant}`)
  }
})

test('the extension members the client reads by name are the ones the registry declares', () => {
  assert.equal(ProblemExtensionMembers.ERRORS, 'errors')
  assert.equal(ProblemExtensionMembers.VALIDATION_ERRORS, 'validationErrors')
})

test('slugFor and typeFor are inverses on the Epistola namespace only', () => {
  assert.equal(slugFor(typeFor('not-found')), 'not-found')
  assert.equal(slugFor('about:blank'), undefined)
  assert.equal(slugFor('https://example.com/errors/not-found'), undefined)
  assert.equal(slugFor(TYPE_BASE), undefined)
  assert.equal(slugFor(undefined), undefined)
})
