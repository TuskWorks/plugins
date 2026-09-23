'use strict'
// End-to-end tests for TuskCrates on a real server.
//
//   node run.js --mc 1.21.11            Paper + bot scenarios
//   node run.js --mc 26.1.2 --folia     Folia + bot scenarios
//   node run.js --mc 26.3               console-only smoke test (no bot protocol support yet)

const fs = require('node:fs')
const path = require('node:path')
const mineflayer = require('mineflayer')
const minecraftData = require('minecraft-data')
const { Server, sleep } = require('./server')

const args = process.argv.slice(2)
const opt = name => { const i = args.indexOf(`--${name}`); return i >= 0 ? args[i + 1] : undefined }
const mc = opt('mc') ?? '1.21.11'
const project = args.includes('--folia') ? 'folia' : 'paper'
const port = Number(opt('port') ?? 25_590)
const botVersion = mineflayerVersionFor(mc)
const withBot = !args.includes('--no-bot') && botVersion !== undefined

/** A version mineflayer supports that speaks the same protocol as `version` (26.1.2 -> 26.1). */
function mineflayerVersionFor (version) {
  const supported = minecraftData.supportedVersions.pc
  if (supported.includes(version)) return version
  const protocol = minecraftData.versionsByMinecraftVersion.pc[version]?.version
  return supported.find(v => minecraftData.versionsByMinecraftVersion.pc[v]?.version === protocol)
}

const libs = path.join(__dirname, '..', 'build', 'libs')
const pluginJar = fs.existsSync(libs) && fs.readdirSync(libs).find(f => /^TuskCrates-.*\.jar$/.test(f))
if (!pluginJar) {
  console.error('Build the plugin first: ./gradlew :tuskcrates:build')
  process.exit(2)
}

const results = []
async function test (name, fn) {
  const started = Date.now()
  try {
    await fn()
    results.push({ name, ok: true })
    console.log(`  ✔ ${name} (${Date.now() - started} ms)`)
  } catch (e) {
    results.push({ name, ok: false })
    console.log(`  ✘ ${name}\n      ${String(e.message).replace(/\n/g, '\n      ')}`)
  }
}

function assert (condition, message) {
  if (!condition) throw new Error(message)
}

async function until (check, message, timeout = 5000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await check()) return
    await sleep(100)
  }
  throw new Error(message)
}

// ── bot helpers ──────────────────────────────────────────────

class Bot {
  static async join (name) {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, version: botVersion, auth: 'offline' })
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${name} did not spawn`)), 60_000)
      bot.once('spawn', () => { clearTimeout(timer); resolve() })
      bot.once('kicked', r => { clearTimeout(timer); reject(new Error(`kicked: ${JSON.stringify(r)}`)) })
      bot.once('error', e => { clearTimeout(timer); reject(e) })
    })
    return new Bot(bot)
  }

  constructor (bot) {
    this.bot = bot
    this.messages = []
    bot.on('messagestr', text => {
      this.messages.push(text)
      if (process.env.E2E_VERBOSE) console.log(`  [bot] ${text}`)
    })
  }

  mark () { return this.messages.length }

  async expect (re, { since = 0, timeout = 5000 } = {}) {
    const deadline = Date.now() + timeout
    while (Date.now() < deadline) {
      const hit = this.messages.slice(since).find(m => re.test(m))
      if (hit) return hit
      await sleep(50)
    }
    const recent = this.messages.slice(since).slice(-6).map(m => `  | ${m}`).join('\n')
    throw new Error(`bot never saw ${re}\n${recent || '  | (no messages)'}`)
  }

  async command (cmd, re, opts) {
    const since = this.mark()
    this.bot.chat(cmd)
    return this.expect(re, { since, ...opts })
  }

  nextWindow (timeout = 5000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.bot.off('windowOpen', onOpen); reject(new Error('no window opened')) }, timeout)
      const onOpen = window => { clearTimeout(timer); resolve(window) }
      this.bot.once('windowOpen', onOpen)
    })
  }

  windowClosed (timeout = 10_000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('window never closed')), timeout)
      this.bot.once('windowClose', () => { clearTimeout(timer); resolve() })
    })
  }

  itemCount (name) {
    return this.bot.inventory.items().filter(i => !name || i.name === name).reduce((n, i) => n + i.count, 0)
  }

  sneak (state) {
    this.bot.setControlState('sneak', state)
    // mineflayer sends sneaking via player_input from 1.21.3, but until 1.21.6 servers
    // still read it from entity_action.
    const actions = minecraftData(this.bot.version).protocol.play.toServer.types.packet_entity_action[1][1].type[1].mappings
    if (this.bot.supportFeature('newPlayerInputPacket') && Object.values(actions).includes('start_sneaking')) {
      this.bot._client.write('entity_action', {
        entityId: this.bot.entity.id,
        actionId: state ? 'start_sneaking' : 'stop_sneaking',
        jumpBoost: 0
      })
    }
  }

  /** Selects an empty hotbar slot. mineflayer's dig-time maths chokes on 1.21+ item components. */
  emptyHand () {
    const slot = [...Array(9).keys()].find(i => !this.bot.inventory.slots[36 + i])
    if (slot === undefined) throw new Error('hotbar is full')
    this.bot.setQuickBarSlot(slot)
  }

  entitiesNear (type, pos, radius = 2) {
    const center = pos.offset(0.5, 0.5, 0.5)
    return Object.values(this.bot.entities).filter(e => e.name === type && e.position.distanceTo(center) <= radius)
  }
}

const titleOf = window => JSON.stringify(window.title)

// ── scenarios ────────────────────────────────────────────────

async function botScenarios (server) {
  let bot = await Bot.join('TuskBot')
  await server.run('op TuskBot', /Made TuskBot a server operator|Nothing changed/)
  await sleep(500)

  const home = bot.bot.entity.position.floored()
  const at = { common: home.offset(2, 0, 0), rare: home.offset(0, 0, 2), legendary: home.offset(-2, 0, 0) }
  const goHome = async () => {
    await bot.command(`/tp @s ${home.x + 0.5} ${home.y} ${home.z + 0.5}`, /Teleported/)
    await sleep(300)
  }
  const block = name => bot.bot.blockAt(at[name])

  for (const name of Object.keys(at)) {
    const p = at[name]
    await bot.command(`/setblock ${p.x} ${p.y} ${p.z} minecraft:chest`, /Changed the block/)
  }

  await test('set crate by looking at the block', async () => {
    await bot.bot.lookAt(at.common.offset(0.5, 0.5, 0.5), true)
    await sleep(300)
    await bot.command('/tc set common', /now a Common crate/)
  })

  await test('set crates by coordinates (console and player)', async () => {
    const r = at.rare
    await server.run(`tc set rare ${r.x} ${r.y} ${r.z}`, /now a Rare crate/)
    const l = at.legendary
    await bot.command(`/tc set legendary ${l.x} ${l.y} ${l.z}`, /now a Legendary crate/)
  })

  await test('holograms appear above every crate', async () => {
    await until(() => Object.keys(at).every(n => bot.entitiesNear('text_display', at[n].offset(0, 1, 0)).length === 1),
      'expected exactly one text display above each crate')
  })

  await test('opening without a key is refused', async () => {
    await goHome()
    const since = bot.mark()
    await bot.bot.activateBlock(block('common'))
    await bot.expect(/You need a Common key/, { since })
  })

  await test('virtual key opens the roulette and grants the reward', async () => {
    await server.run('tc give TuskBot common 2 virtual', /Gave 2x Common key/)
    await goHome()
    const before = bot.itemCount()
    const since = bot.mark()
    const opened = bot.nextWindow()
    await bot.bot.activateBlock(block('common'))
    const window = await opened
    assert(/Opening/.test(titleOf(window)), `unexpected title ${titleOf(window)}`)
    await bot.expect(/You won/, { since, timeout: 15_000 })
    await bot.windowClosed()
    await until(() => bot.itemCount() > before, 'reward items never arrived')
    await bot.command('/tc keys', /Common: 1/)
  })

  await test('closing the roulette early still grants the reward and uses the physical key', async () => {
    await server.run('tc give TuskBot rare 1 physical', /Gave 1x Rare key/)
    await until(() => bot.itemCount('tripwire_hook') === 1, 'physical key never arrived')
    await bot.bot.equip(bot.bot.inventory.items().find(i => i.name === 'tripwire_hook'), 'hand')
    await goHome()
    const since = bot.mark()
    const opened = bot.nextWindow()
    await bot.bot.activateBlock(block('rare'))
    bot.bot.closeWindow(await opened)
    await bot.expect(/You won/, { since, timeout: 3000 })
    await until(() => bot.itemCount('tripwire_hook') === 0, 'physical key was not consumed')
  })

  await test('display animation spins an item above the crate, then cleans up', async () => {
    await server.run('tc give TuskBot legendary 1 virtual', /Gave 1x Legendary key/)
    await goHome()
    const since = bot.mark()
    await bot.bot.activateBlock(block('legendary'))
    await until(() => bot.entitiesNear('item_display', at.legendary.offset(0, 1, 0)).length === 1,
      'no item display spawned', 3000)
    await bot.expect(/You won/, { since, timeout: 15_000 })
    await until(() => bot.entitiesNear('item_display', at.legendary.offset(0, 1, 0)).length === 0,
      'item display was not removed', 8000)
    assert(bot.entitiesNear('text_display', at.legendary.offset(0, 1, 0), 1.5).length === 1,
      'reveal label was not removed (or the hologram vanished)')
  })

  await test('left-click opens a read-only preview', async () => {
    await goHome()
    bot.emptyHand()
    const opened = bot.nextWindow()
    bot.bot.dig(block('rare'), true).catch(() => {}) // aborted on purpose below
    const window = await opened
    bot.bot.stopDigging()
    assert(/Preview/.test(titleOf(window)), `unexpected title ${titleOf(window)}`)
    assert(window.slots[0], 'preview is empty')
    await bot.bot.clickWindow(0, 0, 0).catch(() => {})
    await sleep(500)
    assert(window.slots[0], 'reward item could be taken out of the preview')
    const before = bot.itemCount()
    bot.bot.closeWindow(window)
    await sleep(500)
    assert(bot.itemCount() === before, 'preview leaked an item into the inventory')
  })

  await test('/crates menu opens a crate with a virtual key', async () => {
    const since = bot.mark()
    const menuOpened = bot.nextWindow()
    bot.bot.chat('/crates')
    const menu = await menuOpened
    assert(/Crates/.test(titleOf(menu)), `unexpected title ${titleOf(menu)}`)
    const roulette = bot.nextWindow()
    await bot.bot.clickWindow(0, 0, 0) // common: sorted first
    const window = await roulette
    assert(/Opening/.test(titleOf(window)), `unexpected title ${titleOf(window)}`)
    await bot.expect(/You won/, { since, timeout: 15_000 })
    await bot.command('/tc keys', /Common: 0/)
  })

  await test('keyall gives everyone online virtual keys', async () => {
    const since = bot.mark()
    await server.run('tc keyall rare 2', /Everyone online received 2x Rare key/)
    await bot.expect(/Everyone online received 2x Rare key/, { since })
    await bot.command('/tc keys', /Rare: 2/)
  })

  await test('leaving mid-animation still grants the reward', async () => {
    await goHome()
    const before = bot.itemCount()
    const opened = bot.nextWindow()
    await bot.bot.activateBlock(block('rare'))
    await opened
    bot.bot.quit()
    await sleep(2000)
    bot = await Bot.join('TuskBot')
    await sleep(1000)
    assert(bot.itemCount() > before, `inventory did not grow (${before} -> ${bot.itemCount()})`)
    await bot.command('/tc keys', /Rare: 1/)
  })

  await test('reload keeps crates and holograms', async () => {
    await server.run('tc reload', /Reloaded 3 crate\(s\)/)
    await sleep(1000)
    await until(() => Object.keys(at).every(n => bot.entitiesNear('text_display', at[n].offset(0, 1, 0)).length === 1),
      'holograms missing or duplicated after reload')
  })

  await test('sneak-breaking a crate as admin removes it', async () => {
    await goHome()
    const since = bot.mark()
    bot.emptyHand()
    bot.sneak(true)
    await sleep(300)
    const started = Date.now()
    let previewOpened = false
    const onOpen = () => { previewOpened = true }
    bot.bot.on('windowOpen', onOpen)
    await bot.bot.dig(block('common'), true).catch(e => console.log('      dig:', e.message))
    bot.bot.off('windowOpen', onOpen)
    assert(!previewOpened, 'the server did not see the bot sneaking (preview opened instead)')
    if (process.env.E2E_VERBOSE) console.log(`      dig took ${Date.now() - started} ms, block is now ${block('common')?.name}`)
    bot.sneak(false)
    await bot.expect(/Removed the Common crate/, { since, timeout: 8000 })
    await until(() => bot.entitiesNear('text_display', at.common.offset(0, 1, 0)).length === 0,
      'hologram stayed after the crate was removed')
  })

  bot.bot.quit()
  return at
}

async function consoleScenarios (server) {
  await test('plugin enables and loads the bundled crates', async () => {
    await server.waitFor(/\[TuskCrates\] Loaded 3 crate\(s\)/)
  })

  await test('list shows the bundled crates', async () => {
    await server.run('tc list', /legendary .*display/)
  })

  await test('set a crate by coordinates and spawn its hologram', async () => {
    await server.run('forceload add 0 0', /Marked chunk|already marked|forceloaded/i)
    await server.run('setblock 0 -60 0 minecraft:chest', /Changed the block|Could not set/)
    await server.run('tc set common 0 -60 0', /now a Common crate/)
    await sleep(1000)
    await server.run('execute positioned 0.5 -58.65 0.5 if entity @e[type=minecraft:text_display,distance=..1]', /Test passed/)
  })

  await test('reload', async () => {
    await server.run('tc reload', /Reloaded 3 crate\(s\)/)
  })

  await test('remove the crate and its hologram', async () => {
    await server.run('tc remove 0 -60 0', /Removed the Common crate/)
    await sleep(1000)
    await server.run('execute positioned 0.5 -58.65 0.5 unless entity @e[type=minecraft:text_display,distance=..1]', /Test passed/)
  })
}

// ── main ─────────────────────────────────────────────────────

async function main () {
  console.log(`TuskCrates e2e: ${project} ${mc}${withBot ? ' + bot' : ' (console only)'}`)
  const server = new Server({ project, mc, pluginJar: path.join(libs, pluginJar), port })
  let exitCode = 0
  try {
    await server.start()
    if (withBot) {
      await botScenarios(server)
    } else {
      await consoleScenarios(server)
    }
    await sleep(500)
    await server.stop()

    if (withBot) {
      await test('virtual keys and crate locations are saved on shutdown', async () => {
        const data = path.join(server.dir, 'plugins', 'TuskCrates')
        const players = fs.readdirSync(path.join(data, 'data', 'players')).filter(f => f.endsWith('.yml'))
        assert(players.length === 1, `expected one player file, found ${players.length}`)
        const yml = fs.readFileSync(path.join(data, 'data', 'players', players[0]), 'utf8')
        assert(/rare: 1/.test(yml), `unexpected key file:\n${yml}`)
        const locations = fs.readFileSync(path.join(data, 'locations.yml'), 'utf8')
        assert(/crate: rare/.test(locations) && /crate: legendary/.test(locations) && !/crate: common/.test(locations),
          `unexpected locations.yml:\n${locations}`)
      })
    }
    await test('no warnings or errors from TuskCrates in the server log', async () => {
      const problems = server.problems()
      assert(problems.length === 0, problems.slice(0, 15).join('\n'))
    })
  } catch (e) {
    console.error(`  setup failed: ${e.stack}`)
    exitCode = 1
  } finally {
    if (server.proc && server.proc.exitCode === null) server.proc.kill()
  }

  const failed = results.filter(r => !r.ok).length
  console.log(`\n${results.length - failed}/${results.length} passed`)
  process.exit(exitCode || (failed ? 1 : 0))
}

main()
