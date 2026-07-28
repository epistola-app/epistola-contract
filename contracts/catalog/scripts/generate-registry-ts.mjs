#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// Generates the typed TypeScript facade for the JSON editor registries. The JSON
// files remain the source of truth; this script gives npm consumers literal
// component/style unions and typed registry constants.
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function readJson(path) {
  return JSON.parse(readFileSync(resolve(root, path), 'utf8'));
}

const componentRegistry = readJson('registry/component-registry.json');
const styleRegistry = readJson('registry/style-registry.json');

const componentTypes = componentRegistry.components.map((component) => component.type);
const styleKeys = styleRegistry.groups.flatMap((group) => group.properties.map((property) => property.key));

const target = resolve(root, 'generated/registry.ts');
mkdirSync(dirname(target), { recursive: true });
writeFileSync(
  target,
  `/* Generated from registry/*.json -- do not edit manually. */\n` +
    `import type { ComponentRegistry } from './component-manifest.js';\n` +
    `import type { StyleRegistry } from './style-registry.js';\n\n` +
    `export const componentRegistry: ComponentRegistry = ${JSON.stringify(componentRegistry, null, 2)};\n\n` +
    `export const styleRegistry: StyleRegistry = ${JSON.stringify(styleRegistry, null, 2)};\n\n` +
    `export const componentTypes = ${JSON.stringify(componentTypes, null, 2)} as const;\n\n` +
    `export type ComponentType = (typeof componentTypes)[number];\n\n` +
    `export const styleKeys = ${JSON.stringify(styleKeys, null, 2)} as const;\n\n` +
    `export type StyleKey = (typeof styleKeys)[number];\n`,
);

console.log(`Wrote ${target}`);
