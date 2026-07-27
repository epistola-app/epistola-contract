import assert from 'node:assert/strict';

const packageName = '@epistola.app/epistola-catalog';

await import(packageName);

const registry = await import(`${packageName}/registry`);
assert.ok(registry.componentRegistry, 'component registry must be exported');
assert.ok(registry.styleRegistry, 'style registry must be exported');

await assert.rejects(
  import(`${packageName}/generated/theme`),
  (error) => error?.code === 'ERR_PACKAGE_PATH_NOT_EXPORTED',
  'generator output must not be a public npm entry point',
);
