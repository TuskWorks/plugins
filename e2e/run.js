'use strict'
// Usage: node run.js [--mc 26.1.2] [--server paper|folia] [--scenario tuskclans] [--port 25599] [--via]
// Set E2E_VERBOSE=1 to stream server and bot output.
//
// Versions mineflayer can't speak yet get a console-only smoke test. --via instead
// bridges bots through ViaVersion + ViaBackwards (currently kicked on 26.2+ with
// "Invalid move player packet", an upstream translation issue).

const fs = require('node:fs')
const path = require('node:path')
const { parseArgs } = require('node:util')
const mineflayer = require('mineflayer')
const minecraftData = require('minecraft-data')
const downloads = require('./lib/downloads')
const { PaperServer } = require('./lib/server')
const { TestBot } = require('./lib/bots')

/** scenarios/<name>.js; adding a file is all it takes to add a scenario. */
function loadScenario (name) {
  const file = path.join(__dirname, 'scenarios', `${name}.js`)
  if (!/^[\w-]+$/.test(name) || !fs.existsSync(file)) {
    const known = fs.readdirSync(path.join(__dirname, 'scenarios')).map(f => f.replace(/\.js$/, ''))
    throw new Error(`Unknown scenario ${name} (have: ${known.join(', ')})`)
  }
  return require(file)
}

async function main () {
  const { values } = parseArgs({
    options: {
      mc: { type: 'string', default: '26.1.2' },
      server: { type: 'string', default: 'paper' },
      scenario: { type: 'string', default: 'tuskclans' },
      port: { type: 'string', default: '25599' },
      via: { type: 'boolean', default: false }
    }
  })
  const scenario = loadScenario(values.scenario)

  const mc = values.mc
  const port = Number(values.port)
  const flavor = values.server
  const paper = await downloads.paper(mc, flavor)
  const java = await downloads.java(paper.java)
  const plugins = scenario.plugins()

  // Patch releases share a protocol with their base version (26.1.2 speaks 26.1).
  const known = minecraftData(mc)
  let botVersion = mineflayer.testedVersions.includes(mc) ? mc : known?.version.majorVersion
  let mode = 'bots'
  if (!mineflayer.testedVersions.includes(botVersion)) {
    if (values.via) {
      botVersion = mineflayer.latestSupportedVersion
      plugins.push(await downloads.modrinth('viaversion', mc), await downloads.modrinth('viabackwards', mc))
    } else {
      mode = 'smoke'
    }
  }

  const how = mode === 'smoke' ? `console smoke test (mineflayer tops out at ${mineflayer.latestSupportedVersion})` : `bots speak ${botVersion}`
  console.log(`▶ ${scenario.name} on ${flavor === 'folia' ? 'Folia' : 'Paper'} ${mc} build ${paper.build} (${paper.channel}), Java ${paper.java}+, ${how}`)
  const server = new PaperServer({
    dir: path.join(__dirname, '.run', `${scenario.name}-${flavor}-${mc}`),
    jar: paper.jar,
    java,
    port,
    plugins
  })
  server.prepare()

  const bots = []
  const join = async name => {
    const bot = await TestBot.join({ port, username: name, version: botVersion })
    bots.push(bot)
    return bot
  }
  let failures = 0
  const step = async (name, fn) => {
    const started = Date.now()
    try {
      await fn()
      console.log(`  ✔ ${name} (${Date.now() - started}ms)`)
    } catch (e) {
      if (e.skip) {
        console.log(`  - ${name} (skipped: ${e.message})`)
        return
      }
      failures++
      console.log(`  ✘ ${name}\n    ${String(e.stack ?? e).split('\n').join('\n    ')}`)
    }
  }

  try {
    await server.start()
    if (mode === 'smoke') {
      await scenario.smoke({ server, step })
    } else {
      await scenario.run({ server, join, step })
    }
  } catch (e) {
    failures++
    console.log(`  ✘ scenario aborted: ${e.stack ?? e}`)
  } finally {
    for (const bot of bots) {
      try { bot.quit() } catch {}
    }
    await server.stop()
  }

  // Warnings a scenario provokes on purpose (e.g. broken config files) are not failures.
  const expected = scenario.expectedLog ?? []
  const errors = server.errorsFrom(scenario.logName ?? 'TuskClans', scenario.packagePrefix ?? 'io.github.tuskworks')
    .filter(entry => !expected.some(re => re.test(entry)))
  if (errors.length > 0) {
    failures++
    console.log(`  ✘ server log has ${errors.length} error(s) from the plugin:\n${errors.join('\n---\n')}`)
  }
  if (failures > 0) {
    console.log(`✘ ${failures} failure(s) on ${flavor} ${mc}`)
    process.exitCode = 1
  } else {
    console.log(`✔ all steps passed on ${flavor} ${mc}`)
  }
}

main().catch(e => {
  console.error(e)
  process.exitCode = 1
})
