'use strict'
// Main flows of TuskCrates on a real server: crate blocks and holograms, virtual and physical
// keys with each animation, previews, the /crates menu, keyall, leaving mid-animation, reload,
// sneak-breaking, and what ends up on disk after a shutdown.

const fs = require('node:fs')
const path = require('node:path')
const h = require('../lib/helpers')
const { sleep } = h

const LIBS = path.join(__dirname, '..', '..', 'tuskcrates', 'build', 'libs')

module.exports = {
  name: 'tuskcrates',
  logName: 'TuskCrates',
  packagePrefix: 'io.github.tuskworks.crates',
  plugins: () => [h.newestJar(LIBS, /^TuskCrates-.*\.jar$/, 'run ./gradlew :tuskcrates:build')],

  /** Console-only checks for server versions the bots can't join yet. */
  async smoke ({ server, step }) {
    await step('plugin enables and loads the bundled crates', async () => {
      await server.waitFor(/\[TuskCrates\] Loaded 3 crate\(s\)/, 1000, 0)
    })
    await step('list shows the bundled crates', async () => {
      await h.consoleRun(server, 'tc list', /legendary .*display/)
    })
    await step('set a crate by coordinates and spawn its hologram', async () => {
      await h.consoleRun(server, 'forceload add 0 0', /Marked chunk|already marked|forceloaded/i)
      await h.consoleRun(server, 'setblock 0 -60 0 minecraft:chest', /Changed the block|Could not set/)
      await h.consoleRun(server, 'tc set common 0 -60 0', /now a Common crate/)
      await sleep(1000)
      await h.consoleRun(server, 'execute positioned 0.5 -58.65 0.5 if entity @e[type=minecraft:text_display,distance=..1]', /Test passed/)
    })
    await step('reload', async () => {
      await h.consoleRun(server, 'tc reload', /Reloaded 3 crate\(s\)/)
    })
    await step('remove the crate and its hologram', async () => {
      await h.consoleRun(server, 'tc remove 0 -60 0', /Removed the Common crate/)
      await sleep(1000)
      await h.consoleRun(server, 'execute positioned 0.5 -58.65 0.5 unless entity @e[type=minecraft:text_display,distance=..1]', /Test passed/)
    })
  },

  async run ({ server, join, step }) {
    let bot = await join('TuskBot')
    await h.consoleRun(server, 'op TuskBot', /Made TuskBot a server operator|Nothing changed/)
    bot.op = true
    await sleep(500)

    const home = bot.bot.entity.position.floored()
    const at = { common: home.offset(2, 0, 0), rare: home.offset(0, 0, 2), legendary: home.offset(-2, 0, 0) }
    const goHome = async () => {
      await h.run(bot, `/tp @s ${home.x + 0.5} ${home.y} ${home.z + 0.5}`, /Teleported/)
      await sleep(300)
    }
    const block = name => bot.bot.blockAt(at[name])
    const oneHologramEach = () => Object.keys(at).every(n => h.entitiesNear(bot, 'text_display', at[n].offset(0, 1, 0)).length === 1)

    for (const p of Object.values(at)) {
      await h.run(bot, `/setblock ${p.x} ${p.y} ${p.z} minecraft:chest`, /Changed the block/)
    }

    await step('set crate by looking at the block', async () => {
      await bot.bot.lookAt(at.common.offset(0.5, 0.5, 0.5), true)
      await sleep(300)
      await h.run(bot, '/tc set common', /now a Common crate/)
    })

    await step('set crates by coordinates (console and player)', async () => {
      const r = at.rare
      await h.consoleRun(server, `tc set rare ${r.x} ${r.y} ${r.z}`, /now a Rare crate/)
      const l = at.legendary
      await h.run(bot, `/tc set legendary ${l.x} ${l.y} ${l.z}`, /now a Legendary crate/)
    })

    await step('holograms appear above every crate', async () => {
      await h.until(oneHologramEach, 'expected exactly one text display above each crate')
    })

    await step('opening without a key is refused', async () => {
      await goHome()
      const since = bot.mark()
      await bot.bot.activateBlock(block('common'))
      await bot.expect(/You need a Common key/, { since })
    })

    await step('virtual key opens the roulette and grants the reward', async () => {
      await h.consoleRun(server, 'tc give TuskBot common 2 virtual', /Gave 2x Common key/)
      await goHome()
      const before = h.count(bot)
      const since = bot.mark()
      const window = await h.opens(bot, () => bot.bot.activateBlock(block('common')))
      if (!/Opening/.test(h.titleOf(window))) throw new Error(`unexpected title ${h.titleOf(window)}`)
      const closed = h.windowClosed(bot)
      await bot.expect(/You won/, { since, timeout: 15_000 })
      await closed
      await h.until(() => h.count(bot) > before, 'reward items never arrived')
      await h.run(bot, '/tc keys', /Common: 1/)
    })

    await step('closing the roulette early still grants the reward and uses the physical key', async () => {
      await h.consoleRun(server, 'tc give TuskBot rare 1 physical', /Gave 1x Rare key/)
      await h.until(() => h.count(bot, 'tripwire_hook') === 1, 'physical key never arrived')
      await bot.bot.equip(bot.bot.inventory.items().find(i => i.name === 'tripwire_hook'), 'hand')
      await goHome()
      const since = bot.mark()
      const window = await h.opens(bot, () => bot.bot.activateBlock(block('rare')))
      bot.bot.closeWindow(window)
      await bot.expect(/You won/, { since, timeout: 3000 })
      await h.until(() => h.count(bot, 'tripwire_hook') === 0, 'physical key was not consumed')
    })

    await step('display animation spins an item above the crate, then cleans up', async () => {
      await h.consoleRun(server, 'tc give TuskBot legendary 1 virtual', /Gave 1x Legendary key/)
      await goHome()
      const since = bot.mark()
      const spinning = () => h.entitiesNear(bot, 'item_display', at.legendary.offset(0, 1, 0)).length
      await bot.bot.activateBlock(block('legendary'))
      await h.until(() => spinning() === 1, 'no item display spawned', 3000)
      await bot.expect(/You won/, { since, timeout: 15_000 })
      await h.until(() => spinning() === 0, 'item display was not removed', 8000)
      if (h.entitiesNear(bot, 'text_display', at.legendary.offset(0, 1, 0), 1.5).length !== 1) {
        throw new Error('reveal label was not removed (or the hologram vanished)')
      }
    })

    await step('left-click opens a read-only preview', async () => {
      await goHome()
      h.emptyHand(bot)
      // Digging is aborted on purpose once the preview is open
      const window = await h.opens(bot, () => { bot.bot.dig(block('rare'), true).catch(() => {}) })
      bot.bot.stopDigging()
      if (!/Preview/.test(h.titleOf(window))) throw new Error(`unexpected title ${h.titleOf(window)}`)
      if (!window.slots[0]) throw new Error('preview is empty')
      await bot.bot.clickWindow(0, 0, 0).catch(() => {})
      await sleep(500)
      if (!window.slots[0]) throw new Error('reward item could be taken out of the preview')
      const before = h.count(bot)
      bot.bot.closeWindow(window)
      await sleep(500)
      if (h.count(bot) !== before) throw new Error('preview leaked an item into the inventory')
    })

    await step('/crates menu opens a crate with a virtual key', async () => {
      const since = bot.mark()
      const menu = await h.opens(bot, () => h.say(bot, '/crates'))
      if (!/Crates/.test(h.titleOf(menu))) throw new Error(`unexpected title ${h.titleOf(menu)}`)
      const window = await h.opens(bot, () => bot.bot.clickWindow(0, 0, 0)) // common: sorted first
      if (!/Opening/.test(h.titleOf(window))) throw new Error(`unexpected title ${h.titleOf(window)}`)
      await bot.expect(/You won/, { since, timeout: 15_000 })
      await h.run(bot, '/tc keys', /Common: 0/)
    })

    await step('keyall gives everyone online virtual keys', async () => {
      const since = bot.mark()
      await h.consoleRun(server, 'tc keyall rare 2', /Everyone online received 2x Rare key/)
      await bot.expect(/Everyone online received 2x Rare key/, { since })
      await h.run(bot, '/tc keys', /Rare: 2/)
    })

    await step('leaving mid-animation still grants the reward', async () => {
      await goHome()
      const before = h.count(bot)
      await h.opens(bot, () => bot.bot.activateBlock(block('rare')))
      bot.quit()
      await sleep(2000)
      bot = await join('TuskBot')
      bot.op = true
      await sleep(1000)
      if (h.count(bot) <= before) throw new Error(`inventory did not grow (${before} -> ${h.count(bot)})`)
      await h.run(bot, '/tc keys', /Rare: 1/)
    })

    await step('reload keeps crates and holograms', async () => {
      await h.consoleRun(server, 'tc reload', /Reloaded 3 crate\(s\)/)
      await sleep(1000)
      await h.until(oneHologramEach, 'holograms missing or duplicated after reload')
    })

    await step('sneak-breaking a crate as admin removes it', async () => {
      await goHome()
      const since = bot.mark()
      h.emptyHand(bot)
      h.sneak(bot, true)
      await sleep(300)
      let previewOpened = false
      const onOpen = () => { previewOpened = true }
      bot.bot.on('windowOpen', onOpen)
      await bot.bot.dig(block('common'), true).catch(e => console.log(`      dig: ${e.message}`))
      bot.bot.off('windowOpen', onOpen)
      h.sneak(bot, false)
      if (previewOpened) throw new Error('the server did not see the bot sneaking (preview opened instead)')
      await bot.expect(/Removed the Common crate/, { since, timeout: 8000 })
      await h.until(() => h.entitiesNear(bot, 'text_display', at.common.offset(0, 1, 0)).length === 0,
        'hologram stayed after the crate was removed')
    })

    bot.quit()
    await sleep(500)
    await server.stop()

    await step('virtual keys and crate locations are saved on shutdown', async () => {
      const data = path.join(server.dir, 'plugins', 'TuskCrates')
      const players = fs.readdirSync(path.join(data, 'data', 'players')).filter(f => f.endsWith('.yml'))
      if (players.length !== 1) throw new Error(`expected one player file, found ${players.length}`)
      const yml = fs.readFileSync(path.join(data, 'data', 'players', players[0]), 'utf8')
      if (!/rare: 1/.test(yml)) throw new Error(`unexpected key file:\n${yml}`)
      const locations = fs.readFileSync(path.join(data, 'locations.yml'), 'utf8')
      if (!/crate: rare/.test(locations) || !/crate: legendary/.test(locations) || /crate: common/.test(locations)) {
        throw new Error(`unexpected locations.yml:\n${locations}`)
      }
    })
  }
}
