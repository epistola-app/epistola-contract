import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const packageName = '@epistola.app/epistola-catalog';

const catalog = await import(packageName);
const validationFixture = JSON.parse(
  await readFile(new URL('../fixtures/v1/template-validation.json', import.meta.url), 'utf8'),
);
assert.equal(
  catalog.MAX_STENCIL_NESTING_DEPTH,
  validationFixture.limits.maxStencilNestingDepth,
  'npm and conformance fixture stencil-depth limits must agree',
);

const registry = await import(`${packageName}/registry`);
assert.ok(registry.componentRegistry, 'component registry must be exported');
assert.ok(registry.styleRegistry, 'style registry must be exported');

await assert.rejects(
  import(`${packageName}/generated/theme`),
  (error) => error?.code === 'ERR_PACKAGE_PATH_NOT_EXPORTED',
  'generator output must not be a public npm entry point',
);
