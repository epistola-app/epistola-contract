#!/usr/bin/env node
// Validates the editor component/style registries that epistola-catalog publishes
// for suite and other consumers. This is intentionally a lightweight structural
// guard for cross-file references that JSON Schema alone does not cover well.
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

function requireObject(value, path) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${path} must be an object`);
}

function canContain(parentType, childType, componentByType) {
  const parent = componentByType.get(parentType);
  if (!parent) return false;
  switch (parent.allowedChildren?.mode) {
    case 'all':
      return true;
    case 'none':
      return false;
    case 'allowlist':
      return parent.allowedChildren.types.includes(childType);
    case 'denylist':
      return !parent.allowedChildren.types.includes(childType);
    default:
      return false;
  }
}

const componentRegistry = readJson('epistola-catalog/registry/component-registry.json');
const styleRegistry = readJson('epistola-catalog/registry/style-registry.json');

// The component registry is consumed by epistola-suite, so keep the top-level
// contract shape explicit before validating relationships between entries.
if (componentRegistry.schemaVersion !== 1) {
  fail('component-registry.json schemaVersion must be 1');
}
requireArray(componentRegistry.components, 'component-registry.json components');
if (styleRegistry.schemaVersion !== 1) {
  fail('style-registry.json schemaVersion must be 1');
}

// Collect the canonical style keys first. Component descriptors may opt into all
// styles or reference a subset, and every referenced key must exist.
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

// Validate each component descriptor independently: required display metadata,
// editor slots, inspector fields, allowed child mode, style references, and
// example fragments that suite tooling can load.
const componentTypes = new Set();
const componentByType = new Map();
for (const [index, component] of componentRegistry.components.entries()) {
  const prefix = `component-registry.json components[${index}]`;
  requireString(component.type, `${prefix}.type`);
  requireString(component.label, `${prefix}.label`);
  requireString(component.category, `${prefix}.category`);
  if (componentTypes.has(component.type)) fail(`duplicate component type '${component.type}'`);
  componentTypes.add(component.type);
  componentByType.set(component.type, component);

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

  if (component.defaultStyles !== undefined) {
    requireObject(component.defaultStyles, `${prefix}.defaultStyles`);
    for (const key of Object.keys(component.defaultStyles)) {
      if (!styleKeys.has(key)) fail(`${prefix}.defaultStyles references unknown style key '${key}'`);
      if (component.applicableStyles !== 'all' && !(component.applicableStyles ?? []).includes(key)) {
        fail(`${prefix}.defaultStyles key '${key}' is not listed in applicableStyles`);
      }
    }
  }

  if (component.parameters !== undefined) {
    requireObject(component.parameters, `${prefix}.parameters`);
    if (component.parameters.kind === 'dynamic') {
      if (Object.keys(component.parameters).length !== 1) {
        fail(`${prefix}.parameters dynamic metadata must only contain kind`);
      }
    } else if (component.parameters.kind === 'static') {
      requireObject(component.parameters.schema, `${prefix}.parameters.schema`);
    } else {
      fail(`${prefix}.parameters.kind must be 'dynamic' or 'static'`);
    }
  }

  requireArray(component.examples, `${prefix}.examples`);
  if (component.examples.length === 0) fail(`${prefix}.examples must contain at least one example`);
}

// Validate cross-component references after collecting every component type so
// ordering in the JSON file does not matter.
for (const component of componentRegistry.components) {
  const childTypes = component.allowedChildren?.types ?? [];
  for (const childType of childTypes) {
    if (!componentTypes.has(childType)) {
      fail(`component '${component.type}' references unknown child type '${childType}'`);
    }
  }
}

for (const component of componentRegistry.components) {
  for (const [exampleIndex, example] of component.examples.entries()) {
    const examplePrefix = `component '${component.type}' examples[${exampleIndex}]`;
    requireString(example.name, `${examplePrefix}.name`);
    requireString(example.description, `${examplePrefix}.description`);
    requireObject(example.fragment, `${examplePrefix}.fragment`);
    requireString(example.fragment.rootNodeId, `${examplePrefix}.fragment.rootNodeId`);
    requireObject(example.fragment.nodes, `${examplePrefix}.fragment.nodes`);
    requireObject(example.fragment.slots, `${examplePrefix}.fragment.slots`);

    const nodes = example.fragment.nodes;
    const slots = example.fragment.slots;
    const rootNode = nodes[example.fragment.rootNodeId];
    if (!rootNode) fail(`${examplePrefix}.fragment.rootNodeId '${example.fragment.rootNodeId}' is not in nodes`);
    if (rootNode?.type !== component.type) {
      fail(`${examplePrefix}.fragment.rootNodeId node must have type '${component.type}'`);
    }

    for (const [nodeId, node] of Object.entries(nodes)) {
      requireObject(node, `${examplePrefix}.fragment.nodes['${nodeId}']`);
      if (node.id !== nodeId) fail(`${examplePrefix}.fragment.nodes['${nodeId}'].id must match its key`);
      requireString(node.type, `${examplePrefix}.fragment.nodes['${nodeId}'].type`);
      if (!componentTypes.has(node.type)) fail(`${examplePrefix}.fragment.nodes['${nodeId}'] has unknown type '${node.type}'`);
      requireArray(node.slots, `${examplePrefix}.fragment.nodes['${nodeId}'].slots`);
      for (const slotId of node.slots) {
        if (!slots[slotId]) fail(`${examplePrefix}.fragment.nodes['${nodeId}'] references unknown slot '${slotId}'`);
      }
    }

    for (const [slotId, slot] of Object.entries(slots)) {
      requireObject(slot, `${examplePrefix}.fragment.slots['${slotId}']`);
      if (slot.id !== slotId) fail(`${examplePrefix}.fragment.slots['${slotId}'].id must match its key`);
      requireString(slot.nodeId, `${examplePrefix}.fragment.slots['${slotId}'].nodeId`);
      requireString(slot.name, `${examplePrefix}.fragment.slots['${slotId}'].name`);
      requireArray(slot.children, `${examplePrefix}.fragment.slots['${slotId}'].children`);
      const parent = nodes[slot.nodeId];
      if (!parent) fail(`${examplePrefix}.fragment.slots['${slotId}'].nodeId '${slot.nodeId}' is not in nodes`);
      if (parent && !parent.slots.includes(slotId)) {
        fail(`${examplePrefix}.fragment.slots['${slotId}'] is not referenced by parent node '${slot.nodeId}'`);
      }
      for (const childId of slot.children) {
        const child = nodes[childId];
        if (!child) {
          fail(`${examplePrefix}.fragment.slots['${slotId}'] references unknown child '${childId}'`);
          continue;
        }
        if (!canContain(parent.type, child.type, componentByType)) {
          fail(`${examplePrefix}: ${parent.type} cannot contain ${child.type} in slot '${slot.name}'`);
        }
      }
    }

    const visited = new Set();
    const visiting = new Set();
    function visit(nodeId) {
      if (visiting.has(nodeId)) {
        fail(`${examplePrefix}.fragment contains a cycle at node '${nodeId}'`);
        return;
      }
      if (visited.has(nodeId)) return;
      visiting.add(nodeId);
      visited.add(nodeId);
      const node = nodes[nodeId];
      for (const slotId of node?.slots ?? []) {
        for (const childId of slots[slotId]?.children ?? []) visit(childId);
      }
      visiting.delete(nodeId);
    }
    visit(example.fragment.rootNodeId);
    for (const nodeId of Object.keys(nodes)) {
      if (!visited.has(nodeId)) fail(`${examplePrefix}.fragment node '${nodeId}' is not reachable from rootNodeId`);
    }
  }
}

if (process.exitCode) process.exit();
console.log(`Validated ${componentTypes.size} component descriptors and ${styleKeys.size} style keys`);
