// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * The conformance server: a scripted Epistola API that records everything it is sent.
 *
 * It plays a scenario's `script` back one entry per request, so a driver walks a deterministic
 * sequence of responses, and it keeps a journal of the requests it received — method, path,
 * headers, body and arrival time. The journal is what `expect.mjs` judges; the client's own
 * assertions are irrelevant here, and drivers deliberately make none.
 *
 * It also serves the control plane the drivers use, so that a driver needs no YAML parser, no
 * command-line contract beyond a base URL, and no knowledge of the scenario it is running:
 *
 *   GET  /__conformance/action   what to do, as JSON (action name, client config, keys)
 *   POST /__conformance/report   what the client surfaced, for scenarios that assert on it
 *   POST /__conformance/done     the driver finished (or failed, with a message)
 */

import { createServer } from 'node:http'
import { gzipSync } from 'node:zlib'

const CONTROL_PREFIX = '/__conformance/'

/**
 * Starts a scripted server for one scenario.
 *
 * @param scenario the parsed scenario
 * @param action what to hand the driver from `GET /__conformance/action`
 * @returns a handle with the base URL, the request journal, what the driver reported, and close()
 */
export async function startServer(scenario, action) {
  const journal = []
  const reports = []
  const script = scenario.script ?? []
  let scriptIndex = 0
  let done = null
  let resolveDone
  const doneSignal = new Promise((resolve) => {
    resolveDone = resolve
  })

  const server = createServer((req, res) => {
    collectBody(req, (bodyBuffer) => {
      if (req.url.startsWith(CONTROL_PREFIX)) {
        handleControl(req, res, bodyBuffer)
        return
      }

      const entry = nextScriptEntry()
      journal.push({
        index: journal.length,
        method: req.method,
        path: pathOf(req.url),
        query: queryOf(req.url),
        headers: lowercaseHeaders(req.headers),
        body: bodyBuffer.toString('utf8'),
        atMs: Math.round(performance.now() - startedAt),
      })
      respond(res, entry)
    })
  })

  function handleControl(req, res, bodyBuffer) {
    const route = req.url.slice(CONTROL_PREFIX.length)
    if (route === 'action') {
      json(res, 200, action)
      return
    }
    if (route === 'report') {
      reports.push(parseJson(bodyBuffer))
      json(res, 200, { ok: true })
      return
    }
    if (route === 'done') {
      done = parseJson(bodyBuffer) ?? {}
      json(res, 200, { ok: true })
      resolveDone(done)
      return
    }
    json(res, 404, { error: `unknown control route ${route}` })
  }

  function nextScriptEntry() {
    if (script.length === 0) {
      return { status: 204 }
    }
    // The last entry repeats. Polling scenarios run for a wall-clock duration rather than a fixed
    // number of requests — how many they make is the thing under test — so the script cannot know
    // how long it needs to be.
    const entry = script[Math.min(scriptIndex, script.length - 1)]
    scriptIndex++
    return entry
  }

  const startedAt = performance.now()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const { port } = server.address()

  return {
    baseUrl: `http://127.0.0.1:${port}`,
    journal,
    reports,
    get done() {
      return done
    },
    waitForDone: (timeoutMs) => withTimeout(doneSignal, timeoutMs),
    close: () =>
      new Promise((resolve) => {
        server.closeAllConnections?.()
        server.close(resolve)
      }),
  }
}

function respond(res, entry) {
  const status = entry.status ?? 200
  const headers = { ...(entry.headers ?? {}) }
  let body = renderBody(entry)

  if (entry.gzip) {
    body = gzipSync(body)
    headers['Content-Encoding'] = 'gzip'
  }
  if (entry.contentType) {
    headers['Content-Type'] = entry.contentType
  }
  headers['Content-Length'] = String(body.length)

  res.writeHead(status, headers)
  res.end(body)
}

function renderBody(entry) {
  if (entry.ndjson) {
    return Buffer.from(entry.ndjson.map((line) => JSON.stringify(line)).join('\n') + '\n', 'utf8')
  }
  if (entry.bodyText !== undefined) {
    return Buffer.from(entry.bodyText, 'utf8')
  }
  if (entry.body !== undefined) {
    return Buffer.from(JSON.stringify(entry.body), 'utf8')
  }
  return Buffer.alloc(0)
}

function collectBody(req, callback) {
  const chunks = []
  req.on('data', (chunk) => chunks.push(chunk))
  req.on('end', () => callback(Buffer.concat(chunks)))
}

function json(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': String(body.length) })
  res.end(body)
}

function parseJson(buffer) {
  if (buffer.length === 0) {
    return null
  }
  try {
    return JSON.parse(buffer.toString('utf8'))
  } catch {
    return { unparseable: buffer.toString('utf8') }
  }
}

function pathOf(url) {
  const questionMark = url.indexOf('?')
  return questionMark === -1 ? url : url.slice(0, questionMark)
}

function queryOf(url) {
  const questionMark = url.indexOf('?')
  return questionMark === -1 ? '' : url.slice(questionMark + 1)
}

/**
 * Header names are case-insensitive on the wire and the four HTTP stacks do not agree on the case
 * they send, so the journal stores them lowercased and the matchers look them up that way. Repeated
 * headers keep Node's array form joined with ", ".
 */
function lowercaseHeaders(headers) {
  const result = {}
  for (const [name, value] of Object.entries(headers)) {
    result[name.toLowerCase()] = Array.isArray(value) ? value.join(', ') : value
  }
  return result
}

function withTimeout(promise, timeoutMs) {
  return Promise.race([
    promise,
    new Promise((resolve) => setTimeout(() => resolve(null), timeoutMs).unref?.()),
  ])
}
