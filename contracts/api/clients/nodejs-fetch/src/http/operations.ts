// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { CONTRACT_OPERATIONS, type ContractOperation } from '../generated/contractOperations.js'

interface CompiledRoute {
  readonly operation: ContractOperation
  readonly pattern: RegExp
  readonly literalSegments: number
  /** Per segment, whether it is a literal: where literal counts tie, the earlier literal wins. */
  readonly literalAt: readonly boolean[]
}

let compiled: CompiledRoute[] | undefined

/**
 * The contract operation a request addresses, by HTTP method and the path relative to the API base
 * path — or undefined for a path the contract does not describe.
 *
 * Where two templates match one path, the one with more literal segments wins; where both have
 * as many (`/documents/jobs/content` matches `/documents/jobs/{requestId}` and
 * `/documents/{documentId}/content`), the one whose first differing segment is a literal wins, so
 * `jobs` beats `{documentId}`.
 */
export function findOperation(method: string, path: string): ContractOperation | undefined {
  const upper = method.toUpperCase()
  for (const route of routes()) {
    if (route.operation.method === upper && route.pattern.test(path)) {
      return route.operation
    }
  }
  return undefined
}

/**
 * The operation path of a request URL: its pathname with the API base path removed. A URL outside
 * the base path is returned as its whole pathname, which then matches nothing.
 */
export function operationPath(url: string, basePath: string): string {
  const pathname = new URL(url).pathname
  const base = new URL(basePath).pathname.replace(/\/+$/, '')
  return base !== '' && pathname.startsWith(base) ? pathname.slice(base.length) || '/' : pathname
}

function routes(): CompiledRoute[] {
  if (compiled === undefined) {
    compiled = CONTRACT_OPERATIONS.map((operation) => {
      const segments = operation.path.split('/')
      const literalAt = segments.map((segment) => !/^\{.*\}$/.test(segment))
      const literalSegments = literalAt.filter(Boolean).length
      const source = segments.map((segment) => (/^\{.*\}$/.test(segment) ? '[^/]+' : escapeRegExp(segment))).join('/')
      return { operation, pattern: new RegExp(`^${source}/?$`), literalSegments, literalAt }
    })
    compiled.sort(
      (a, b) =>
        b.literalSegments - a.literalSegments ||
        earlierLiteral(a.literalAt, b.literalAt) ||
        a.operation.path.localeCompare(b.operation.path),
    )
  }
  return compiled
}

/**
 * Negative when `a` has a literal where `b` first has a parameter, positive for the reverse. Only
 * templates with as many segments can match one path; a shorter one sorts first otherwise, which
 * keeps the order total.
 */
function earlierLiteral(a: readonly boolean[], b: readonly boolean[]): number {
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    if (a[i] !== b[i]) {
      return a[i] ? -1 : 1
    }
  }
  return a.length - b.length
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
