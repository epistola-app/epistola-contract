// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * The judge: evaluates a scenario's `expect` block against the request journal.
 *
 * Every check returns a message rather than throwing, so one run reports every way a client
 * diverged instead of only the first. The messages are the product here — "client X differs" is
 * useless without saying in what — so each one names the request, the field, what the contract
 * asks for and what arrived.
 */

import { createPublicKey, verify as verifySignature } from 'node:crypto'

/**
 * @returns an array of failure messages; empty means the client conformed
 */
export function judge(scenario, { journal, reports }) {
  const expected = scenario.expect ?? {}
  const failures = []

  checkCounts(expected, journal, failures)

  for (const [index, matcher] of (expected.requests ?? []).entries()) {
    const actual = journal[index]
    if (!actual) {
      failures.push(`request #${index + 1}: expected ${describe(matcher)} but the client sent only ${journal.length}`)
      continue
    }
    checkRequest(`request #${index + 1}`, matcher, actual, failures)
  }

  if (expected.everyRequest) {
    for (const actual of journal) {
      checkRequest(`request #${actual.index + 1} (everyRequest)`, expected.everyRequest, actual, failures)
    }
  }

  checkViolations(journal, failures)
  checkGaps(expected.gaps, journal, failures)
  checkJwt(expected.jwt, journal, failures)
  checkReports(expected.report, reports, failures)

  return failures
}

/**
 * Contract violations reported by an upstream that validates against the spec.
 *
 * These need no scenario to declare them: any violation is a failure, on every request, for every
 * operation a driver touches. That is the point of running against a validating backend — the
 * scenario says what to do, and the spec itself says what is allowed, so nobody has to think of the
 * constraint in advance. `direction=DESC` was caught by a scenario written after someone noticed;
 * this catches its whole family without anyone noticing anything.
 */
function checkViolations(journal, failures) {
  for (const entry of journal) {
    for (const violation of entry.violations ?? []) {
      const where = Array.isArray(violation.location) ? violation.location.join('.') : 'request'
      failures.push(
        `request #${entry.index + 1} (${entry.method} ${entry.path}) violates the contract at ` +
          `${where}: ${violation.message}${violation.code ? ` [${violation.code}]` : ''}`,
      )
    }
  }
}

function checkCounts(expected, journal, failures) {
  const count = journal.length
  if (expected.requestCount !== undefined && count !== expected.requestCount) {
    failures.push(`expected exactly ${expected.requestCount} request(s), the client sent ${count}`)
  }
  if (expected.requestCountAtMost !== undefined && count > expected.requestCountAtMost) {
    failures.push(
      `expected at most ${expected.requestCountAtMost} request(s), the client sent ${count}` +
        ` — ${perSecond(journal)}`,
    )
  }
  if (expected.requestCountAtLeast !== undefined && count < expected.requestCountAtLeast) {
    failures.push(`expected at least ${expected.requestCountAtLeast} request(s), the client sent ${count}`)
  }
}

function checkRequest(label, matcher, actual, failures) {
  if (matcher.method && matcher.method !== actual.method) {
    failures.push(`${label}: expected method ${matcher.method}, got ${actual.method}`)
  }
  if (matcher.path && matcher.path !== actual.path) {
    failures.push(`${label}: expected path ${matcher.path}, got ${actual.path}`)
  }
  if (matcher.query !== undefined) {
    checkValue(`${label}: query`, matcher.query, actual.query, failures)
  }
  for (const [name, paramMatcher] of Object.entries(matcher.queryParams ?? {})) {
    checkValue(`${label}: query parameter ${name}`, paramMatcher, actual.queryParams[name], failures)
  }

  for (const [name, headerMatcher] of Object.entries(matcher.headers ?? {})) {
    checkValue(`${label}: header ${name}`, headerMatcher, actual.headers[name.toLowerCase()], failures)
  }

  if (matcher.body !== undefined) {
    checkBody(label, matcher.body, actual.body, failures)
  }
}

function checkBody(label, matcher, rawBody, failures) {
  if (matcher.json !== undefined) {
    let parsed
    try {
      parsed = JSON.parse(rawBody)
    } catch {
      failures.push(`${label}: body is not JSON: ${truncate(rawBody)}`)
      return
    }
    checkSubset(`${label}: body`, matcher.json, parsed, failures)
  }
  if (matcher.jsonNullOrAbsent !== undefined) {
    // Absent and null are the same value under a schema that types the field `[string, "null"]`,
    // and the four serializers do not agree on which they emit. What matters is that neither
    // carries a real value the caller never set.
    let parsed
    try {
      parsed = JSON.parse(rawBody)
    } catch {
      failures.push(`${label}: body is not JSON: ${truncate(rawBody)}`)
      return
    }
    for (const key of matcher.jsonNullOrAbsent) {
      if (parsed?.[key] !== undefined && parsed[key] !== null) {
        failures.push(`${label}: body should not set "${key}", got ${JSON.stringify(parsed[key])}`)
      }
    }
  }
  if (matcher.jsonAbsent !== undefined) {
    let parsed
    try {
      parsed = JSON.parse(rawBody)
    } catch {
      failures.push(`${label}: body is not JSON: ${truncate(rawBody)}`)
      return
    }
    for (const key of matcher.jsonAbsent) {
      if (parsed !== null && typeof parsed === 'object' && key in parsed) {
        failures.push(`${label}: body should not carry "${key}", got ${JSON.stringify(parsed[key])}`)
      }
    }
  }
  if (matcher.equals !== undefined || matcher.matches !== undefined || matcher.contains !== undefined) {
    checkValue(`${label}: body`, matcher, rawBody, failures)
  }
}

/**
 * A value matcher is either a literal (exact match) or one of the forms below. `absent` is the
 * only one that tolerates a missing value; everything else reports one.
 */
function checkValue(label, matcher, actual, failures) {
  if (matcher !== null && typeof matcher === 'object') {
    if (matcher.absent) {
      if (actual !== undefined) {
        failures.push(`${label}: expected no value, got ${JSON.stringify(actual)}`)
      }
      return
    }
    // Some differences between clients are legitimate: a parameter the contract gives a default
    // for may be sent explicitly or left out, and both produce identical server behaviour. What is
    // not legitimate is sending a value the contract does not allow, so those are matched only
    // when present.
    if (matcher.whenPresent) {
      if (actual !== undefined) {
        checkValue(label, matcher.whenPresent, actual, failures)
      }
      return
    }
    if (actual === undefined) {
      failures.push(`${label}: missing, expected ${describe(matcher)}`)
      return
    }
    if (matcher.equals !== undefined && actual !== matcher.equals) {
      failures.push(`${label}: expected ${JSON.stringify(matcher.equals)}, got ${JSON.stringify(actual)}`)
    }
    if (matcher.matches !== undefined && !new RegExp(matcher.matches).test(actual)) {
      failures.push(`${label}: ${JSON.stringify(actual)} does not match /${matcher.matches}/`)
    }
    if (matcher.contains !== undefined && !String(actual).includes(matcher.contains)) {
      failures.push(`${label}: ${JSON.stringify(actual)} does not contain ${JSON.stringify(matcher.contains)}`)
    }
    if (matcher.oneOf !== undefined && !matcher.oneOf.includes(actual)) {
      failures.push(`${label}: ${JSON.stringify(actual)} is not one of ${JSON.stringify(matcher.oneOf)}`)
    }
    return
  }

  if (actual === undefined) {
    failures.push(`${label}: missing, expected ${JSON.stringify(matcher)}`)
    return
  }
  if (actual !== matcher) {
    failures.push(`${label}: expected ${JSON.stringify(matcher)}, got ${JSON.stringify(actual)}`)
  }
}

/** Deep subset: every key the matcher names must be present and equal; extra keys are allowed. */
function checkSubset(label, expected, actual, failures) {
  for (const [key, value] of Object.entries(expected)) {
    const child = actual === null || actual === undefined ? undefined : actual[key]
    if (value !== null && typeof value === 'object' && !Array.isArray(value) && !isMatcher(value)) {
      checkSubset(`${label}.${key}`, value, child, failures)
    } else {
      checkValue(`${label}.${key}`, value, child, failures)
    }
  }
}

function isMatcher(value) {
  return ['equals', 'matches', 'contains', 'oneOf', 'absent', 'whenPresent'].some((key) => key in value)
}

/**
 * Timing checks. The gap between two polls is the only externally visible evidence of a client's
 * backoff policy, and a collapsed backoff is invisible to a functional test — the results still
 * arrive, just after thousands of requests.
 */
function checkGaps(gaps, journal, failures) {
  if (!gaps) {
    return
  }
  // A collector is *meant* to poll again immediately when the server said hasMore, so scenarios
  // that provoke a burst exclude those deliberate gaps from the floor.
  const firstChecked = 1 + (gaps.skipFirst ?? 0)

  if (gaps.minMs !== undefined) {
    // One message, not one per gap: a collapsed backoff produces hundreds of violations, and
    // hundreds of near-identical lines bury every other failure in the run.
    const violations = []
    for (let i = firstChecked; i < journal.length; i++) {
      const gap = journal[i].atMs - journal[i - 1].atMs
      if (gap < gaps.minMs) {
        violations.push({ after: i, gap })
      }
    }
    if (violations.length > 0) {
      const worst = violations.reduce((a, b) => (a.gap <= b.gap ? a : b))
      failures.push(
        `${violations.length} of ${journal.length - firstChecked} idle gap(s) fell below the ` +
          `${gaps.minMs}ms floor, the shortest ${worst.gap}ms after request #${worst.after}\n` +
          `gaps: ${summariseGaps(journal, firstChecked)}`,
      )
    }
  }
  if (gaps.increasing) {
    const observed = gapList(journal).slice(firstChecked - 1)
    if (observed.length < 2) {
      failures.push(`expected enough idle polls to show the backoff growing, saw ${observed.length + 1}`)
    } else if (!observed.some((gap, i) => i > 0 && gap > observed[i - 1] * 1.5)) {
      failures.push(
        'expected the idle gaps to grow by the backoff multiplier, they stayed flat\n' +
          `gaps: ${summariseGaps(journal, firstChecked)}`,
      )
    }
  }
}

function gapList(journal) {
  return journal.slice(1).map((entry, i) => entry.atMs - journal[i].atMs)
}

/** The idle gaps, elided in the middle when there are too many to read. */
function summariseGaps(journal, firstChecked) {
  const observed = gapList(journal).slice(firstChecked - 1)
  const render = (list) => list.map((gap) => `${gap}ms`).join(', ')
  return observed.length <= 12
    ? render(observed)
    : `${render(observed.slice(0, 6))} … ${render(observed.slice(-3))} (${observed.length} gaps)`
}

function perSecond(journal) {
  if (journal.length < 2) {
    return 'too few to rate'
  }
  const span = journal[journal.length - 1].atMs - journal[0].atMs
  return span === 0
    ? 'all within the same millisecond'
    : `${Math.round((journal.length / span) * 1000)}/s over ${span}ms`
}

/**
 * Verifies the bearer tokens rather than pattern-matching them: decode the parts, check the claims,
 * and verify the signature against the public half of the key the driver was given. A client that
 * signs ES256 as a DER SEQUENCE, or reuses one token across requests, fails here and nowhere else.
 */
function checkJwt(jwt, journal, failures) {
  if (!jwt) {
    return
  }
  const tokens = []

  for (const entry of journal) {
    const authorization = entry.headers.authorization
    if (!authorization) {
      failures.push(`request #${entry.index + 1}: expected an Authorization header carrying a JWT, got none`)
      continue
    }
    if (!authorization.startsWith('Bearer ')) {
      failures.push(`request #${entry.index + 1}: expected "Bearer <jwt>", got ${JSON.stringify(authorization)}`)
      continue
    }
    const token = authorization.slice('Bearer '.length)
    tokens.push(token)
    checkOneToken(`request #${entry.index + 1}`, jwt, token, failures)
  }

  if (jwt.freshPerRequest && tokens.length > 1 && new Set(tokens).size !== tokens.length) {
    failures.push(
      `expected a freshly minted token per request, ${tokens.length - new Set(tokens).size} were reused` +
        ' — a replayed token defeats the jti nonce the server deduplicates on',
    )
  }
}

function checkOneToken(label, jwt, token, failures) {
  const parts = token.split('.')
  if (parts.length !== 3) {
    failures.push(`${label}: token is not three dot-separated parts: ${truncate(token)}`)
    return
  }

  const header = decodePart(parts[0])
  const claims = decodePart(parts[1])
  if (!header || !claims) {
    failures.push(`${label}: token header or claims are not base64url JSON`)
    return
  }

  if (jwt.alg && header.alg !== jwt.alg) {
    failures.push(`${label}: expected alg ${jwt.alg}, got ${JSON.stringify(header.alg)}`)
  }
  if (header.typ !== undefined && header.typ !== 'JWT') {
    failures.push(`${label}: expected typ JWT when present, got ${JSON.stringify(header.typ)}`)
  }
  if (jwt.issuer && claims.iss !== jwt.issuer) {
    failures.push(`${label}: expected iss ${JSON.stringify(jwt.issuer)}, got ${JSON.stringify(claims.iss)}`)
  }
  for (const claim of jwt.requiredClaims ?? []) {
    if (claims[claim] === undefined) {
      failures.push(`${label}: token is missing the ${claim} claim`)
    }
  }
  if (claims.iat !== undefined && claims.exp !== undefined) {
    const lifetime = claims.exp - claims.iat
    if (jwt.maxLifetimeSeconds !== undefined && lifetime > jwt.maxLifetimeSeconds) {
      failures.push(`${label}: token lives ${lifetime}s, longer than the ${jwt.maxLifetimeSeconds}s the contract allows`)
    }
    const skew = Math.abs(Math.floor(Date.now() / 1000) - claims.iat)
    if (skew > 120) {
      failures.push(`${label}: iat is ${skew}s away from now — the client's clock or units are wrong`)
    }
  }

  if (jwt.publicKeyPem) {
    if (!verifyToken(parts, header.alg, jwt.publicKeyPem)) {
      failures.push(
        `${label}: signature does not verify against the public key` +
          ` — for ES256 this is usually a DER-encoded signature where JOSE requires raw R||S`,
      )
    }
  }
}

function verifyToken(parts, alg, publicKeyPem) {
  const signingInput = Buffer.from(`${parts[0]}.${parts[1]}`, 'ascii')
  const signature = Buffer.from(parts[2], 'base64url')
  const key = createPublicKey(publicKeyPem)
  try {
    if (alg === 'RS256') {
      return verifySignature('sha256', signingInput, key, signature)
    }
    if (alg === 'ES256') {
      return verifySignature('sha256', signingInput, { key, dsaEncoding: 'ieee-p1363' }, signature)
    }
  } catch {
    return false
  }
  return false
}

function decodePart(part) {
  try {
    return JSON.parse(Buffer.from(part, 'base64url').toString('utf8'))
  } catch {
    return null
  }
}

/**
 * Scenarios that assert on what the client *surfaced* rather than what it sent — the parsed problem
 * slug, the results a collector handed its handler. The driver posts these to /__conformance/report
 * as plain JSON, so the assertion stays at the level of observable behaviour rather than internals.
 */
function checkReports(expected, reports, failures) {
  if (!expected) {
    return
  }
  const merged = Object.assign({}, ...reports)
  checkSubset('report', expected, merged, failures)
}

function describe(matcher) {
  if (matcher === null || typeof matcher !== 'object') {
    return JSON.stringify(matcher)
  }
  if (matcher.method || matcher.path) {
    return `${matcher.method ?? 'any'} ${matcher.path ?? 'any path'}`
  }
  return JSON.stringify(matcher)
}

function truncate(text, limit = 120) {
  return text.length > limit ? `${text.slice(0, limit)}…` : text
}
