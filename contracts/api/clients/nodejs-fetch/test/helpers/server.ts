// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'

/** One request the test server received, as the client sent it. */
export interface RecordedRequest {
  readonly method: string
  readonly path: string
  readonly headers: Record<string, string>
  readonly body: Buffer
}

export interface TestServer {
  readonly url: string
  readonly requests: RecordedRequest[]
  close(): Promise<void>
}

export type Responder = (request: RecordedRequest, res: ServerResponse, index: number) => void | Promise<void>

/** A loopback HTTP server that records every request and answers with `respond`. */
export async function startServer(respond: Responder): Promise<TestServer> {
  const requests: RecordedRequest[] = []
  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const chunks: Buffer[] = []
    req.on('data', (chunk: Buffer) => chunks.push(chunk))
    req.on('end', () => {
      const headers: Record<string, string> = {}
      for (const [name, value] of Object.entries(req.headers)) {
        headers[name.toLowerCase()] = Array.isArray(value) ? value.join(', ') : (value ?? '')
      }
      const recorded: RecordedRequest = { method: req.method ?? 'GET', path: req.url ?? '/', headers, body: Buffer.concat(chunks) }
      requests.push(recorded)
      Promise.resolve(respond(recorded, res, requests.length - 1)).catch((error: unknown) => {
        res.writeHead(500, { 'Content-Type': 'text/plain' })
        res.end(String(error))
      })
    })
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (address === null || typeof address === 'string') {
    throw new Error('server did not bind to a TCP port')
  }
  return {
    url: `http://127.0.0.1:${address.port}`,
    requests,
    close: () =>
      new Promise<void>((resolve, reject) => {
        server.closeAllConnections()
        server.close((error) => (error ? reject(error) : resolve()))
      }),
  }
}

/** Writes a JSON body with the given status and content type. */
export function json(res: ServerResponse, status: number, contentType: string, body: unknown): void {
  const bytes = Buffer.from(JSON.stringify(body), 'utf8')
  res.writeHead(status, { 'Content-Type': contentType, 'Content-Length': String(bytes.length) })
  res.end(bytes)
}

/** Waits for `predicate` to hold, polling every few milliseconds, or fails after `timeoutMs`. */
export async function waitFor(predicate: () => boolean, timeoutMs = 5_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error(`condition not met within ${timeoutMs}ms`)
    }
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
