// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Epistola Node.js client.
 *
 * Re-exports the stock generated client (API classes, models, runtime) alongside the hand-written
 * Epistola glue: the client builder, identity headers, self-signed JWT auth, RFC 9457
 * problem-detail handling, NDJSON result collection, and client-side schema validation.
 *
 * ```ts
 * import { ClientIdentity, EpistolaClient, JwtSigner, TemplatesApi } from '@epistola.app/epistola-client'
 *
 * const client = EpistolaClient.builder('https://epistola.example.com/api')
 *   .identity(ClientIdentity.builder().nodeId('my-pod').build())
 *   .jwtSigner(JwtSigner.builder().consumerId('svc').privateKey(key).build())
 *   // or .apiKey('epk_...') for Authorization: ApiKey <key>
 *   .build()
 *
 * const templates = new TemplatesApi(client)
 * ```
 */

// The full stock generated surface (API classes, models, Configuration, ResponseError, …).
export * from './generated/api/index.js'

// Derived sources: the contract constants both sides of the wire agree on.
export * from './generated/index.js'

// Hand-written glue.
export { JwtSigner, JwtSignerBuilder, type JwtAlgorithm } from './auth/jwtSigner.js'
export { ClientIdentity, ClientIdentityBuilder } from './identity/clientIdentity.js'
export { ProblemDetailException } from './error/problemDetailException.js'
export { isProblemJson, parseProblem } from './error/problemDetailParser.js'
export { BLANK_TYPE, PROBLEM_JSON, TYPE_BASE, slugFor, typeFor } from './error/problemTypes.js'
export { EpistolaClient, EpistolaClientBuilder, type EpistolaClientOptions } from './http/epistolaClient.js'
export { epistolaRequestMiddleware, problemDetailMiddleware, type EpistolaRequestOptions } from './http/middleware.js'
export { findOperation, operationPath } from './http/operations.js'
export {
  ResultCollector,
  ResultCollectorBuilder,
  type CollectErrorHandler,
  type CollectResult,
  type MetricsListener,
  type ResultHandler,
} from './collect/resultCollector.js'
export { MAX_ROUTING_KEY_ATTEMPTS, PartitionRouting } from './protocol/partitionRouting.js'
export { PollBackoff } from './protocol/pollBackoff.js'
export { murmur3x86_32, murmur3x86_32String } from './protocol/murmur3.js'
export { acceptEncoding, decompress, detectCodec, supportsZstd, type Codec } from './protocol/compression.js'
export { lines as ndjsonLines } from './protocol/ndjson.js'
export { ModelValidationException, type ConstraintViolation } from './validation/modelValidationException.js'
export {
  TemplateDataValidationException,
  TemplateSchemaValidator,
  TtlSchemaCache,
  ValidatingGenerationApi,
  type GenerationApiLike,
  type SchemaCache,
  type SchemaLoader,
  type TemplateSchemaSource,
  type ValidationFailure,
} from './validation/templateSchemaValidator.js'
