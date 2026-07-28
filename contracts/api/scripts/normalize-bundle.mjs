#!/usr/bin/env node

/**
 * Post-process the generated, self-contained OpenAPI bundle for downstream tooling.
 *
 * How this is called:
 *   `make bundle` first runs Redocly against `contracts/api/openapi.yaml`, writes
 *   `contracts/api/build/openapi.yaml`, and then invokes:
 *
 *     node contracts/api/scripts/normalize-bundle.mjs contracts/api/build/openapi.yaml
 *
 * The same sequence is used by CI, documentation, mock-server, snapshot, and release builds.
 * This script edits only that generated bundle. It never edits the authored OpenAPI files or
 * the canonical catalog JSON Schemas.
 *
 * Why this is necessary:
 *   The authored API deliberately references the catalog schemas directly. Redocly embeds those
 *   schemas correctly, but its bundle shape exposes a few interoperability problems in OpenAPI
 *   Generator:
 *
 *   1. Redocly's default component-name strategy derives `template-document.schema` from the
 *      JSON Schema filename. The schema already has `title: TemplateDocument`, but Redocly's
 *      title strategy requires every extracted API schema to have a title. Rather than rename
 *      unrelated API components, we normalize this generated component to `TemplateDocument`.
 *   2. The catalog's `$id` is its canonical resource identity and the base URI for its relative
 *      references. Redocly rewrites those references to local `#/components/schemas/...` refs
 *      when embedding the schema. Retaining the original `$id` would then make those local refs
 *      resolve against the catalog resource instead of this OpenAPI document, so `$id` and the
 *      catalog's `$schema` dialect declaration are removed from the embedded copy only.
 *   3. JSON Schema can infer the integer type from `const: 1`, while OpenAPI Generator cannot.
 *      Adding the redundant `type: integer` keeps generated client/server models strongly typed.
 *   4. The catalog's `ThemeRef` is an exact `oneOf`, but the Kotlin generators merge its inline
 *      branches and incorrectly require `themeId` for `inherit`. The bundle rewrites that union
 *      to an equivalent object schema using `if`/`then`: validators retain the same accepted JSON,
 *      while generators see a required enum discriminator and optional override fields.
 *
 * Every transformation checks for Redocly's expected input shape and fails loudly if that shape
 * changes, so an upstream tool update cannot silently produce a malformed contract bundle.
 */

import { readFile, writeFile } from 'node:fs/promises';

const bundlePath = process.argv[2];
if (!bundlePath) {
  console.error('usage: normalize-bundle.mjs <bundled-openapi.yaml>');
  process.exit(2);
}

const generatedName = 'template-document.schema';
const canonicalName = 'TemplateDocument';
const generatedRef = `#/components/schemas/${generatedName}`;
const canonicalRef = `#/components/schemas/${canonicalName}`;
const alias = `    ${canonicalName}:\n      $ref: '${generatedRef}'\n`;
const generatedDefinition = `    ${generatedName}:\n`;
const modelVersionWithoutType = `        modelVersion:
          const: 1
          description: Schema version for forward-compatibility detection.`;
const modelVersionWithType = `        modelVersion:
          type: integer
          const: 1
          description: Schema version for forward-compatibility detection.`;
const generatedThemeRef = `    ThemeRef:
      oneOf:
        - type: object
          properties:
            type:
              const: inherit
          required:
            - type
          additionalProperties: false
        - type: object
          properties:
            type:
              const: override
            themeId:
              type: string
              minLength: 1
            catalogKey:
              type: string
              minLength: 1
              description: Catalog containing the theme. Omitted means the same catalog as the owning template.
          required:
            - type
            - themeId
          additionalProperties: false`;
const generatorCompatibleThemeRef = `    ThemeRef:
      type: object
      required:
        - type
      properties:
        type:
          type: string
          enum:
            - inherit
            - override
        themeId:
          type: string
          minLength: 1
        catalogKey:
          type: string
          minLength: 1
          description: Catalog containing the theme. Omitted means the same catalog as the owning template.
      additionalProperties: false
      allOf:
        - if:
            properties:
              type:
                const: inherit
            required:
              - type
          then:
            not:
              anyOf:
                - required:
                    - themeId
                - required:
                    - catalogKey
        - if:
            properties:
              type:
                const: override
            required:
              - type
          then:
            required:
              - themeId`;

let bundle = await readFile(bundlePath, 'utf8');

if (
  !bundle.includes(alias) ||
  !bundle.includes(generatedDefinition) ||
  !bundle.includes(modelVersionWithoutType) ||
  !bundle.includes(generatedThemeRef)
) {
  console.error(
    `error: ${bundlePath} does not contain the expected bundled catalog schema shape; ` +
      'the Redocly bundle shape may have changed',
  );
  process.exit(1);
}

bundle = bundle
  .replace(alias, '')
  .replaceAll(generatedRef, canonicalRef)
  .replace(generatedDefinition, `    ${canonicalName}:\n`)
  .replace(modelVersionWithoutType, modelVersionWithType)
  .replace(generatedThemeRef, generatorCompatibleThemeRef)
  // JSON Schema identifiers are correct in the catalog sources, but once Redocly embeds
  // those schemas in OpenAPI components they change relative-reference resolution for
  // OpenAPI Generator. Strip them from this generated bundle only.
  .replace(/^      \$(?:id|schema):.*\n/gm, '');

if (
  bundle.includes(generatedName) ||
  bundle.includes(modelVersionWithoutType) ||
  bundle.includes(generatedThemeRef)
) {
  console.error(`error: an unnormalized catalog schema remains in ${bundlePath}`);
  process.exit(1);
}

await writeFile(bundlePath, bundle);
console.log(`Normalized catalog schemas in ${bundlePath}`);
