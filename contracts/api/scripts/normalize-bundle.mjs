#!/usr/bin/env node

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

let bundle = await readFile(bundlePath, 'utf8');

if (!bundle.includes(alias) || !bundle.includes(generatedDefinition)) {
  console.error(
    `error: ${bundlePath} does not contain the expected catalog schema aliases; ` +
      'the Redocly bundle shape may have changed',
  );
  process.exit(1);
}

bundle = bundle
  .replace(alias, '')
  .replaceAll(generatedRef, canonicalRef)
  .replace(generatedDefinition, `    ${canonicalName}:\n`)
  // JSON Schema identifiers are correct in the catalog sources, but once Redocly embeds
  // those schemas in OpenAPI components they change relative-reference resolution for
  // OpenAPI Generator. Strip them from this generated bundle only.
  .replace(/^      \$(?:id|schema):.*\n/gm, '');

if (bundle.includes(generatedName)) {
  console.error(`error: generated catalog component name remains in ${bundlePath}`);
  process.exit(1);
}

await writeFile(bundlePath, bundle);
console.log(`Normalized catalog schemas in ${bundlePath}`);
