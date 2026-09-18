#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// Generates TypeScript definitions from the epistola-catalog JSON Schemas. Keeping
// this list in a script makes package.json readable and keeps schema generation
// separate from the registry facade generation.
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const schemasDir = resolve(root, 'schemas');

// json2ts renders a bounded array as a union of every tuple length up to its maxItems. For the
// manifest that would make a plain string[] unassignable to `keywords`, so its bound is left to
// runtime validation (`MAX_CATALOG_KEYWORDS`) rather than the type.
const unboundedArrays = ['--maxItems', '0'];

const schemas = [
  ['catalog-manifest.schema.json', '../generated/catalog-manifest.ts', unboundedArrays],
  ['resource-detail.schema.json', '../generated/resource-detail.ts'],
  ['template-document.schema.json', '../generated/template-document.ts'],
  ['template-shared.schema.json', '../generated/template-shared.ts'],
  ['theme.schema.json', '../generated/theme.ts'],
  ['component-manifest.schema.json', '../generated/component-manifest.ts'],
  ['style-registry.schema.json', '../generated/style-registry.ts'],
];

for (const [input, output, options = []] of schemas) {
  const result = spawnSync(
    'json2ts',
    ['--input', input, '--output', output, '--cwd', '.', ...options],
    { cwd: schemasDir, stdio: 'inherit' },
  );
  if (result.status !== 0) process.exit(result.status ?? 1);
}
