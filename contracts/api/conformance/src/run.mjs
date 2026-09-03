// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Runs the conformance scenarios against one client.
 *
 *   node src/run.mjs --client kotlin [--scenario collect-backoff] [--list]
 *
 * For each scenario it starts a scripted server, runs that client's driver against it, and judges
 * the request journal. The driver is told what to do over HTTP, so the only thing a driver needs to
 * know is a base URL — no scenario files, no YAML parser, nothing to keep in step in four
 * languages.
 */

import { spawn } from 'node:child_process'
import { generateKeyPairSync } from 'node:crypto'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseYaml } from 'yaml'

import { judge } from './expect.mjs'
import { startServer } from './server.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const CLIENTS = ['kotlin', 'jakarta', 'dotnet', 'python']
const DRIVER_TIMEOUT_MS = 120_000

const options = parseArgs(process.argv.slice(2))

if (options.list) {
  for (const scenario of loadScenarios()) {
    console.log(`${scenario.id.padEnd(28)} ${scenario.title}`)
  }
  process.exit(0)
}

if (!CLIENTS.includes(options.client)) {
  console.error(`--client must be one of ${CLIENTS.join(', ')} (got ${options.client ?? 'nothing'})`)
  process.exit(2)
}

process.exit(await main())

async function main() {
  const scenarios = loadScenarios().filter((s) => !options.scenario || s.id === options.scenario)
  if (scenarios.length === 0) {
    console.error(`no scenario matched ${JSON.stringify(options.scenario)}`)
    return 2
  }

  await prepareDriver(options.client)

  console.log(`\nconformance: ${options.client} — ${scenarios.length} scenario(s)\n`)
  const results = []

  for (const scenario of scenarios) {
    results.push(await runScenario(scenario, options.client))
  }

  return report(results)
}

async function runScenario(scenario, client) {
  const skipReason = scenario.skip?.[client]
  if (skipReason) {
    console.log(`  SKIP  ${scenario.id}\n        ${skipReason}`)
    return { scenario, status: 'skipped', skipReason }
  }

  const keys = scenario.action?.config?.auth === 'jwt' ? generateRsaKeyPair() : null
  const action = {
    scenario: scenario.id,
    action: scenario.action.name,
    config: { ...scenario.action.config, ...(keys ? { privateKeyPem: keys.privateKeyPem } : {}) },
  }

  const server = await startServer(scenario, action)
  let driverExit
  try {
    driverExit = await runDriver(client, server.baseUrl)
    await server.waitForDone(2_000)
  } finally {
    await server.close()
  }

  const failures = []
  if (driverExit.code !== 0) {
    failures.push(`driver exited with code ${driverExit.code}`)
  }
  if (server.done?.error) {
    failures.push(`driver reported an error: ${server.done.error}`)
  }
  if (driverExit.code !== 0 || server.done?.error) {
    failures.push(indent(driverExit.output.trim() || '(no driver output)'))
  } else {
    const expected = keys ? withPublicKey(scenario, keys.publicKeyPem) : scenario
    failures.push(...judge(expected, server))
  }

  if (failures.length === 0) {
    console.log(`  PASS  ${scenario.id}`)
    return { scenario, status: 'passed' }
  }
  console.log(`  FAIL  ${scenario.id}`)
  for (const failure of failures) {
    console.log(`        ${failure.replace(/\n/g, '\n        ')}`)
  }
  return { scenario, status: 'failed', failures }
}

function withPublicKey(scenario, publicKeyPem) {
  return { ...scenario, expect: { ...scenario.expect, jwt: { ...scenario.expect.jwt, publicKeyPem } } }
}

function prepareDriver(client) {
  return new Promise((resolvePromise, rejectPromise) => {
    const script = join(ROOT, 'drivers', client, 'prepare.sh')
    console.log(`preparing the ${client} driver (${script})`)
    const child = spawn(script, [], { stdio: options.verbose ? 'inherit' : 'pipe', cwd: join(ROOT, 'drivers', client) })
    let output = ''
    child.stdout?.on('data', (chunk) => (output += chunk))
    child.stderr?.on('data', (chunk) => (output += chunk))
    child.on('error', rejectPromise)
    child.on('close', (code) => {
      if (code === 0) {
        resolvePromise()
      } else {
        console.error(output)
        rejectPromise(new Error(`preparing the ${client} driver failed with code ${code}`))
      }
    })
  })
}

function runDriver(client, baseUrl) {
  return new Promise((resolvePromise) => {
    const script = join(ROOT, 'drivers', client, 'run.sh')
    const child = spawn(script, [baseUrl], { cwd: join(ROOT, 'drivers', client) })
    let output = ''
    child.stdout.on('data', (chunk) => (output += chunk))
    child.stderr.on('data', (chunk) => (output += chunk))

    const timer = setTimeout(() => {
      output += `\n[timed out after ${DRIVER_TIMEOUT_MS}ms]`
      child.kill('SIGKILL')
    }, DRIVER_TIMEOUT_MS)

    child.on('error', (error) => {
      clearTimeout(timer)
      resolvePromise({ code: 127, output: `${output}\n${error.message}` })
    })
    child.on('close', (code) => {
      clearTimeout(timer)
      resolvePromise({ code: code ?? 1, output })
    })
  })
}

function report(results) {
  const passed = results.filter((r) => r.status === 'passed').length
  const failed = results.filter((r) => r.status === 'failed')
  const skipped = results.filter((r) => r.status === 'skipped').length

  console.log(`\n${passed} passed, ${failed.length} failed, ${skipped} skipped\n`)
  if (failed.length > 0) {
    console.log('failed scenarios:')
    for (const result of failed) {
      console.log(`  - ${result.scenario.id}: ${result.scenario.title}`)
    }
    console.log('')
  }
  return failed.length === 0 ? 0 : 1
}

function loadScenarios() {
  const dir = join(ROOT, 'scenarios')
  return readdirSync(dir)
    .filter((name) => name.endsWith('.yaml'))
    .sort()
    .map((name) => {
      const scenario = parseYaml(readFileSync(join(dir, name), 'utf8'))
      if (!scenario.id) {
        throw new Error(`${name} has no id`)
      }
      return scenario
    })
}

/**
 * A fresh key pair per scenario, handed to the driver as PEM and kept here to verify the tokens it
 * signs. Generating it beats committing one: a private key in a repository is a finding in every
 * scanner that looks, however clearly it is labelled a test key.
 */
function generateRsaKeyPair() {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  return {
    privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    publicKeyPem: publicKey.export({ type: 'spki', format: 'pem' }),
  }
}

function parseArgs(argv) {
  const parsed = {}
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--list') parsed.list = true
    else if (arg === '--verbose') parsed.verbose = true
    else if (arg === '--client') parsed.client = argv[++i]
    else if (arg === '--scenario') parsed.scenario = argv[++i]
    else {
      console.error(`unknown argument ${arg}`)
      process.exit(2)
    }
  }
  return parsed
}

function indent(text) {
  return text
    .split('\n')
    .map((line) => `  | ${line}`)
    .join('\n')
}
