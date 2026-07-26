#!/usr/bin/env node
// Generates TypeScript definitions from the epistola-catalog JSON Schemas. Keeping
// this list in a script makes package.json readable and keeps schema generation
// separate from the registry facade generation.
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const schemasDir = resolve(root, 'epistola-catalog/schemas');

const schemas = [
  ['template-document.schema.json', '../generated/template-document.ts'],
  ['template-shared.schema.json', '../generated/template-shared.ts'],
  ['theme.schema.json', '../generated/theme.ts'],
  ['component-manifest.schema.json', '../generated/component-manifest.ts'],
  ['style-registry.schema.json', '../generated/style-registry.ts'],
];

for (const [input, output] of schemas) {
  const result = spawnSync(
    'json2ts',
    ['--input', input, '--output', output, '--cwd', '.'],
    { cwd: schemasDir, stdio: 'inherit' },
  );
  if (result.status !== 0) process.exit(result.status ?? 1);
}
