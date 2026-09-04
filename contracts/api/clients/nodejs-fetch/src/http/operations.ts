// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { CONTRACT_OPERATIONS, type ContractOperation } from '../generated/contractOperations.js'

interface CompiledRoute {
  readonly operation: ContractOperation
  readonly pattern: RegExp
  readonly literalSegments: number
}

let compiled: CompiledRoute[] | undefined

/**
 * The contract operation a request addresses, by HTTP method and the path relative to the API base
 * path — or undefined for a path the contract does not describe.
 *
 * Where two templates match one path (`/documents/jobs` against `/documents/{documentId}`), the
 * one with more literal segments wins, which is the same rule every router applies.
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
      const literalSegments = segments.filter((segment) => !/^\{.*\}$/.test(segment)).length
      const source = segments.map((segment) => (/^\{.*\}$/.test(segment) ? '[^/]+' : escapeRegExp(segment))).join('/')
      return { operation, pattern: new RegExp(`^${source}/?$`), literalSegments }
    })
    compiled.sort((a, b) => b.literalSegments - a.literalSegments || a.operation.path.localeCompare(b.operation.path))
  }
  return compiled
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
