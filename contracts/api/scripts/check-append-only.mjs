// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Holds a minor version to adding members and parameters only after the existing ones.
 *
 * The generated clients take members and parameters positionally: Kotlin data class and .NET model
 * constructors, and the Kotlin, Jakarta, .NET and Python operation methods. A member inserted
 * before an existing one still compiles wherever the types line up, and silently passes every
 * later argument to the wrong member. Within a major version, a schema's members and an
 * operation's parameters therefore keep their order, and new ones go after them. A major version
 * may reorder them; check-append-only.sh only runs this against a release of the same major.
 *
 * The generators order two ways: the Kotlin generators put required members and parameters first,
 * the .NET model constructor keeps the spec's order. A base order must stay a prefix of both, so a
 * new required member among optional ones is refused too: Kotlin would place it before them.
 *
 * Usage: node check-append-only.mjs <base bundle .json> <head bundle .json>
 */
import { readFileSync } from 'node:fs';

const [basePath, headPath] = process.argv.slice(2);
if (!basePath || !headPath) {
  console.error('usage: check-append-only.mjs <base bundle .json> <head bundle .json>');
  process.exit(2);
}
const base = JSON.parse(readFileSync(basePath, 'utf8'));
const head = JSON.parse(readFileSync(headPath, 'utf8'));

const METHODS = ['get', 'put', 'post', 'delete', 'patch', 'head', 'options'];

function resolve(spec, node) {
  let current = node;
  const seen = new Set();
  while (current && typeof current === 'object' && typeof current.$ref === 'string') {
    if (seen.has(current.$ref) || !current.$ref.startsWith('#/')) break;
    seen.add(current.$ref);
    current = current.$ref
      .slice(2)
      .split('/')
      .map((part) => part.replace(/~1/g, '/').replace(/~0/g, '~'))
      .reduce((value, part) => (value == null ? undefined : value[part]), spec);
  }
  return current;
}

/** A schema's members in spec order, `allOf` parts flattened in order, with whether each is required. */
function members(spec, schema, seen = new Set()) {
  const resolved = resolve(spec, schema);
  if (!resolved || typeof resolved !== 'object' || seen.has(resolved)) return [];
  seen.add(resolved);
  const out = [];
  for (const part of resolved.allOf ?? []) out.push(...members(spec, part, seen));
  const required = new Set(resolved.required ?? []);
  for (const name of Object.keys(resolved.properties ?? {})) {
    const existing = out.find((member) => member.name === name);
    if (existing) existing.required ||= required.has(name);
    else out.push({ name, required: required.has(name) });
  }
  return out;
}

/** An operation's parameters, path-level first as the generators take them, then a multipart body's fields. */
function parameters(spec, pathItem, operation) {
  const out = [];
  const add = (name, required) => {
    if (!out.some((parameter) => parameter.name === name)) out.push({ name, required });
  };
  for (const raw of [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])]) {
    const parameter = resolve(spec, raw);
    if (parameter?.name) add(`${parameter.in}:${parameter.name}`, parameter.required === true);
  }
  const body = resolve(spec, operation.requestBody);
  const multipart = body?.content?.['multipart/form-data']?.schema;
  if (multipart) {
    for (const member of members(spec, multipart)) add(`form:${member.name}`, member.required);
  }
  return out;
}

const specOrder = (list) => list.map((item) => item.name);
const requiredFirst = (list) => [
  ...list.filter((item) => item.required).map((item) => item.name),
  ...list.filter((item) => !item.required).map((item) => item.name),
];

const failures = [];

/**
 * Why `before` is not a prefix of `after`, or null when it is. Members removed in between are left
 * out: removing one is a breaking change of its own, reported by `make breaking`.
 */
function prefixProblem(before, after) {
  const kept = before.filter((name) => after.includes(name));
  const prefix = after.slice(0, kept.length);
  if (kept.every((name, index) => prefix[index] === name)) return null;
  const inserted = prefix.filter((name) => !kept.includes(name));
  const detail = inserted.length ? `${inserted.join(', ')} inserted before existing ones` : 'existing ones reordered';
  return `${detail}\n    was: ${kept.join(', ')}\n    now: ${after.join(', ')}`;
}

function checkList(where, before, after) {
  const inSpecOrder = prefixProblem(specOrder(before), specOrder(after));
  if (inSpecOrder) {
    failures.push(`${where}: ${inSpecOrder}`);
    return;
  }
  // Appended in spec order, but required: the Kotlin generators place it among the required ones.
  const requiredFirstProblem = prefixProblem(requiredFirst(before), requiredFirst(after));
  if (requiredFirstProblem) {
    failures.push(
      `${where}: a new required one goes before the optional ones in the Kotlin client, so add it as optional; ${requiredFirstProblem}`,
    );
  }
}

for (const [name, schema] of Object.entries(base.components?.schemas ?? {})) {
  const next = head.components?.schemas?.[name];
  if (!next) continue;
  checkList(`schema ${name}`, members(base, schema), members(head, next));
}

for (const [path, pathItem] of Object.entries(base.paths ?? {})) {
  const nextItem = head.paths?.[path];
  if (!nextItem) continue;
  for (const method of METHODS) {
    const operation = pathItem[method];
    const next = nextItem[method];
    if (!operation || !next) continue;
    const label = operation.operationId ?? `${method.toUpperCase()} ${path}`;
    checkList(`operation ${label}`, parameters(base, pathItem, operation), parameters(head, nextItem, next));
  }
}

if (failures.length > 0) {
  console.error('Members and parameters must be added after the existing ones within a major version:');
  for (const failure of failures) console.error(`  - ${failure}`);
  console.error(
    'The generated clients take them positionally, so an insertion silently shifts every later argument.',
  );
  process.exit(1);
}
console.log('append-only OK: no member or parameter inserted before an existing one');
