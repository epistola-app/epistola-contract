#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function readJson(path) {
  return JSON.parse(readFileSync(resolve(root, path), 'utf8'));
}

function fail(message) {
  console.error(`error: ${message}`);
  process.exitCode = 1;
}

function requireString(value, path) {
  if (typeof value !== 'string' || value.length === 0) fail(`${path} must be a non-empty string`);
}

function requireArray(value, path) {
  if (!Array.isArray(value)) fail(`${path} must be an array`);
}

const componentRegistry = readJson('epistola-model/registry/component-registry.json');
const styleRegistry = readJson('epistola-model/registry/style-registry.json');

if (componentRegistry.schemaVersion !== 1) {
  fail('component-registry.json schemaVersion must be 1');
}
requireArray(componentRegistry.components, 'component-registry.json components');

requireArray(styleRegistry.groups, 'style-registry.json groups');
const styleKeys = new Set();
for (const [groupIndex, group] of styleRegistry.groups.entries()) {
  requireString(group.name, `style-registry.json groups[${groupIndex}].name`);
  requireString(group.label, `style-registry.json groups[${groupIndex}].label`);
  requireArray(group.properties, `style-registry.json groups[${groupIndex}].properties`);
  for (const [propertyIndex, property] of group.properties.entries()) {
    requireString(property.key, `style-registry.json groups[${groupIndex}].properties[${propertyIndex}].key`);
    requireString(property.label, `style-registry.json groups[${groupIndex}].properties[${propertyIndex}].label`);
    requireString(property.type, `style-registry.json groups[${groupIndex}].properties[${propertyIndex}].type`);
    if (styleKeys.has(property.key)) fail(`duplicate style key '${property.key}'`);
    styleKeys.add(property.key);
  }
}

const componentTypes = new Set();
for (const [index, component] of componentRegistry.components.entries()) {
  const prefix = `component-registry.json components[${index}]`;
  requireString(component.type, `${prefix}.type`);
  requireString(component.label, `${prefix}.label`);
  requireString(component.category, `${prefix}.category`);
  if (componentTypes.has(component.type)) fail(`duplicate component type '${component.type}'`);
  componentTypes.add(component.type);

  requireArray(component.slots, `${prefix}.slots`);
  requireArray(component.inspector, `${prefix}.inspector`);
  if (typeof component.hidden !== 'boolean') fail(`${prefix}.hidden must be a boolean`);

  if (!component.allowedChildren || typeof component.allowedChildren.mode !== 'string') {
    fail(`${prefix}.allowedChildren.mode is required`);
  }
  if (component.allowedChildren?.types !== undefined) {
    requireArray(component.allowedChildren.types, `${prefix}.allowedChildren.types`);
  }

  if (component.applicableStyles !== 'all') {
    requireArray(component.applicableStyles, `${prefix}.applicableStyles`);
    for (const key of component.applicableStyles ?? []) {
      if (!styleKeys.has(key)) fail(`${prefix}.applicableStyles references unknown style key '${key}'`);
    }
  }

  requireArray(component.examples, `${prefix}.examples`);
  if (component.examples.length === 0) fail(`${prefix}.examples must contain at least one example`);
  for (const [exampleIndex, example] of component.examples.entries()) {
    const examplePrefix = `${prefix}.examples[${exampleIndex}]`;
    requireString(example.name, `${examplePrefix}.name`);
    requireString(example.description, `${examplePrefix}.description`);
    if (!example.fragment || typeof example.fragment !== 'object') fail(`${examplePrefix}.fragment is required`);
    requireString(example.fragment?.rootNodeId, `${examplePrefix}.fragment.rootNodeId`);
    if (!example.fragment?.nodes || typeof example.fragment.nodes !== 'object' || Array.isArray(example.fragment.nodes)) {
      fail(`${examplePrefix}.fragment.nodes must be an object`);
    }
    if (!example.fragment?.slots || typeof example.fragment.slots !== 'object' || Array.isArray(example.fragment.slots)) {
      fail(`${examplePrefix}.fragment.slots must be an object`);
    }
  }
}

for (const component of componentRegistry.components) {
  const childTypes = component.allowedChildren?.types ?? [];
  for (const childType of childTypes) {
    if (!componentTypes.has(childType)) {
      fail(`component '${component.type}' references unknown child type '${childType}'`);
    }
  }
}

if (process.exitCode) process.exit();
console.log(`Validated ${componentTypes.size} component descriptors and ${styleKeys.size} style keys`);
