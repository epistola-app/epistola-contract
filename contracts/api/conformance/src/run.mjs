// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Runs the conformance scenarios against one client.
 *
 *   node src/run.mjs --client kotlin [--scenario collect-backoff] [--list]
 *
 *   --client is one of kotlin, jakarta, dotnet, python, node.
 *
 * For each scenario it starts a scripted server, runs that client's driver against it, and judges
 * the request journal. The driver is told what to do over HTTP, so the only thing a driver needs to
 * know is a base URL — no scenario files, no YAML parser, nothing to keep in step in five
 * languages.
 */

import { spawn } from 'node:child_process'
import { generateKeyPairSync } from 'node:crypto'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseYaml } from 'yaml'

import { judge } from './expect.mjs'
import { checkFixtures } from './fixtures.mjs'
import { startServer } from './server.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO = resolve(ROOT, '..', '..', '..')
const PRISM = join(REPO, 'contracts/api/tools/node_modules/.bin/prism')
const BUNDLED_SPEC = join(REPO, 'contracts/api/build/openapi.yaml')
const CLIENTS = ['kotlin', 'jakarta', 'dotnet', 'python', 'node']
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

  // Before anything is built or run: a fixture that does not match the contract is a scenario bug,
  // and it is far cheaper to say so here than to watch four clients fail to deserialize it.
  const fixtureProblems = scenarios.flatMap((scenario) =>
    scenario.backend === 'prism' ? [] : checkFixtures(scenario, BUNDLED_SPEC),
  )
  if (fixtureProblems.length > 0) {
    console.error(`\n${fixtureProblems.length} scripted response(s) do not match the contract:\n`)
    for (const problem of fixtureProblems) {
      console.error(`  ${problem}`)
    }
    console.error('')
    return 2
  }

  await prepareDriver(options.client)

  // Started once for the whole run, not once per scenario: Prism takes several seconds to parse the
  // spec and come up, which would dwarf the scenarios themselves.
  const prism = scenarios.some((s) => s.backend === 'prism') ? await startPrism() : null

  console.log(`\nconformance: ${options.client} — ${scenarios.length} scenario(s)\n`)
  const results = []

  try {
    for (const scenario of scenarios) {
      results.push(await runScenario(scenario, options.client, prism))
    }
  } finally {
    prism?.stop()
  }

  return report(results)
}

/**
 * Prism serves the contract itself: it validates each request against the spec and answers from the
 * declared schemas. Every schema constraint on every operation is then checked without a scenario
 * having to name it — which is the half the scripted server cannot do, just as Prism cannot do
 * timing, streaming, or the identity headers the spec deliberately does not model.
 */
async function startPrism() {
  if (!existsSync(BUNDLED_SPEC)) {
    throw new Error(`bundled spec not found at ${BUNDLED_SPEC} — run \`make bundle\``)
  }
  const port = 4030 + (process.pid % 500)
  process.stdout.write(`starting prism on the bundled spec (port ${port}) `)

  const child = spawn(PRISM, ['mock', BUNDLED_SPEC, '-p', String(port), '--errors'], { stdio: 'pipe' })
  let output = ''
  child.stdout.on('data', (chunk) => (output += chunk))
  child.stderr.on('data', (chunk) => (output += chunk))

  const url = `http://127.0.0.1:${port}`
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`prism exited with ${child.exitCode}:\n${output}`)
    }
    try {
      // Any answer at all means it is serving; a 404 or 401 is as good as a 200 here.
      await fetch(`${url}/ping`, { method: 'POST' })
      console.log('— ready')
      return { url, stop: () => child.kill('SIGKILL') }
    } catch {
      await new Promise((r) => setTimeout(r, 250))
    }
  }
  child.kill('SIGKILL')
  throw new Error(`prism did not come up within 60s:\n${output}`)
}

async function runScenario(scenario, client, prism) {
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

  if (scenario.backend === 'prism' && !prism) {
    throw new Error(`${scenario.id} needs the prism backend but it was not started`)
  }
  const server = await startServer(scenario, action, scenario.backend === 'prism' ? prism.url : null)
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
  if (driverExit.code === 0 && !server.done?.error) {
    const expected = keys ? withPublicKey(scenario, keys.publicKeyPem) : scenario
    failures.push(...judge(expected, server))
  } else {
    // A validating backend rejects a bad request, so the driver fails with an HTTP error and the
    // reason is in the violations rather than anywhere the driver can see. Reporting the exception
    // alone would say "400" and leave the cause on the floor.
    failures.push(...judge({ expect: {} }, server))
  }
  // Whatever the driver wrote, on any failure. A collector that swallowed an exception and stopped
  // polling looks exactly like one that chose not to poll until you read its output.
  if (failures.length > 0 && driverExit.output.trim()) {
    failures.push(indent(driverExit.output.trim()))
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
