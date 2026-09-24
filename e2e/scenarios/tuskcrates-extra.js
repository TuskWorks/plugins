'use strict'
// Extended scenario for TuskCrates: config reloads and broken crate files, every key path,
// instant crates with command rewards, permissions, knockback, explosion/piston/craft/place
// protection, /tc additem, preview paging, two players on one display crate, autosave and restart.

const fs = require('node:fs')
const path = require('node:path')
const h = require('../lib/helpers')
const { sleep } = h

const LIBS = path.join(__dirname, '..', '..', 'tuskcrates', 'build', 'libs')

const bigRewards = Array.from({ length: 50 }, (_, i) =>
  `  r${i + 1}:\n    weight: 1\n    item:\n      material: STONE\n      amount: ${i + 1}\n`).join('')

const CRATE_FILES = {
  'instant.yml': `display-name: "<green>Instant"
animation: instant
hologram: []
rewards:
  bundle:
    weight: 1
    name: "<green>Emerald Bundle"
    give-item: false
    broadcast: true
    item:
      material: EMERALD
    commands:
      - "give <player> minecraft:emerald 5"
      - "/give {player} minecraft:emerald 1"
    extra-items:
      - material: APPLE
        amount: 2
`,
  'big.yml': `display-name: "<white>Big"
show-in-menu: false
rewards:
${bigRewards}`,
  'solo.yml': `display-name: "<white>Solo"
animation: instant
show-in-menu: false
hologram: []
rewards:
  placeholder:
    weight: 0.000001
    item:
      material: DIRT
`,
  'partial.yml': `display-name: "<yellow>Partial"
rewards:
  good:
    weight: 1
    item:
      material: BREAD
  bad-enchant:
    weight: 1
    item:
      material: DIAMOND_SWORD
      enchantments:
        not_an_enchantment: 1
  zero:
    weight: 0
    item:
      material: DIRT
`,
  'no-rewards.yml': `display-name: "Empty"
rewards:
  nope:
    weight: 1
    item:
      material: NOT_A_REAL_ITEM
`,
  'bad-animation.yml': `animation: spin
rewards:
  a:
    item:
      material: DIRT
`,
  'broken-yaml.yml': 'rewards: [unclosed\n  - : :\n',
  'Bad Name.yml': 'rewards:\n  a:\n    item:\n      material: DIRT\n'
}

module.exports = {
  name: 'tuskcrates-extra',
  logName: 'TuskCrates',
  packagePrefix: 'io.github.tuskworks.crates',
  // Provoked on purpose by the broken crate files above
  expectedLog: [/Skipping crates\//, /crates\/partial\.yml: /, /crates\/no-rewards\.yml: /],
  plugins: () => [h.newestJar(LIBS, /^TuskCrates-.*\.jar$/, 'run ./gradlew :tuskcrates:build')],

  async run ({ server, join, step }) {
    const data = path.join(server.dir, 'plugins', 'TuskCrates')
    const cfgFile = path.join(data, 'config.yml')
    const folia = /folia/i.test(path.basename(server.jar))
    let lastReload = 0
    const reload = async count => {
      await h.consoleRun(server, 'tc reload', new RegExp(`Reloaded ${count} crate\\(s\\)`))
      lastReload = Date.now()
    }

    let admin = await join('Admin')
    await h.consoleRun(server, 'op Admin', /Made Admin a server operator|Nothing changed/)
    admin.op = true
    let guest = await join('Guest')
    server.command('gamemode survival @a')
    await sleep(1000)

    const home = admin.bot.entity.position.floored()
    const at = {
      common: home.offset(3, 0, 0),
      rare: home.offset(0, 0, 3),
      legendary: home.offset(-3, 0, 0),
      instant: home.offset(0, 0, -2)
    }
    const spots = { admin: home, guest: home.offset(0, 0, 1) }
    const standAt = async (tb, pos) => {
      await h.run(admin, `/tp ${tb.name} ${pos.x + 0.5} ${pos.y} ${pos.z + 0.5}`, /Teleported/)
      await sleep(400)
    }
    const setblock = (pos, block) => h.run(admin, `/setblock ${pos.x} ${pos.y} ${pos.z} ${block}`, /Changed the block|Could not set the block/)
    const hologramsAt = names => names.map(n => h.entitiesNear(admin, 'text_display', at[n].offset(0, 1, 0)).length)

    await step('setup: three bundled crates on chests', async () => {
      for (const name of ['common', 'rare', 'legendary']) {
        const p = at[name]
        await setblock(p, 'minecraft:chest')
        await h.run(admin, `/tc set ${name} ${p.x} ${p.y} ${p.z}`, /now a .* crate/)
      }
      await setblock(at.instant, 'minecraft:chest')
    })

    await step('reload loads new crate files and skips broken ones with a clear warning', async () => {
      for (const [file, text] of Object.entries(CRATE_FILES)) fs.writeFileSync(path.join(data, 'crates', file), text)
      h.setYamlLine(cfgFile, 'autosave-minutes: 5', 'autosave-minutes: 1')
      const since = server.lines.length
      await reload(7)
      const log = server.lines.slice(since)
      for (const re of [
        /Skipping crates\/Bad Name\.yml: file names may only use a-z, 0-9, _ and -/,
        /Skipping crates\/broken-yaml\.yml: invalid YAML/,
        /Skipping crates\/bad-animation\.yml: Unknown animation 'spin'/,
        /crates\/no-rewards\.yml: skipping reward 'nope': invalid material 'NOT_A_REAL_ITEM'/,
        /Skipping crates\/no-rewards\.yml: no valid rewards/,
        /crates\/partial\.yml: skipping reward 'bad-enchant': unknown enchantment 'not_an_enchantment'/,
        /crates\/partial\.yml: reward 'zero' has no positive weight/
      ]) {
        if (!log.some(l => re.test(l))) throw new Error(`missing warning ${re}`)
      }
    })

    await step('tc list shows every crate with its reward count and animation', async () => {
      const since = server.lines.length
      server.command('tc list')
      await server.waitFor(/solo .*\(1 rewards, instant\)/, 5000, since)
      const out = server.lines.slice(since).join('\n')
      for (const re of [/big .*\(50 rewards, roulette\)/, /common .*\(6 rewards, roulette\)/, /instant .*\(1 rewards, instant\)/,
        /legendary .*\(6 rewards, display\)/, /partial .*\(1 rewards, roulette\)/, /rare .*\(6 rewards, roulette\)/]) {
        if (!re.test(out)) throw new Error(`tc list is missing ${re}\n${out}`)
      }
    })

    await step('turning holograms off and on with tc reload', async () => {
      h.setYamlLine(cfgFile, 'enabled: true', 'enabled: false')
      await reload(7)
      await h.until(() => hologramsAt(['common', 'rare', 'legendary']).every(n => n === 0),
        () => `holograms left after disabling them: ${hologramsAt(['common', 'rare', 'legendary'])}`)
      h.setYamlLine(cfgFile, 'enabled: false', 'enabled: true')
      await reload(7)
      await h.until(() => hologramsAt(['common', 'rare', 'legendary']).every(n => n === 1),
        () => `holograms after re-enabling: ${hologramsAt(['common', 'rare', 'legendary'])}`)
      // Given now so the 1-minute autosave (rescheduled by every reload) picks them up later
      await h.consoleRun(server, 'tc give Guest big 7', /Gave 7x Big key/)
    })

    await step('clear errors: unknown crate, not a crate, looking at air, console-only use', async () => {
      await h.run(admin, '/tc give Admin nope 1', /Unknown crate: nope/)
      const air = home.offset(6, 2, 6)
      await h.run(admin, `/tc remove ${air.x} ${air.y} ${air.z}`, /That block is not a crate/)
      await admin.bot.look(0, Math.PI / 2, true)
      await sleep(300)
      await h.run(admin, '/tc set common', /Look at a block within 6 blocks/)
      await h.consoleRun(server, 'tc keys', /Only players can use this/)
      await h.consoleRun(server, 'tc open common', /Only players can use this/)
    })

    await step('tc give accepts selectors and tc take removes at most what the player has', async () => {
      await h.consoleRun(server, 'tc give @a rare 2 virtual', /Gave 2x Rare key\(s\) to 2 player/)
      await h.consoleRun(server, 'tc take Guest rare 5', /Took 2x Rare key\(s\) from Guest/)
      await h.run(admin, '/tc keys Guest', /Rare: 0/)
      await h.consoleRun(server, 'tc take Admin rare 2', /Took 2x Rare key/)
    })

    await step('100 physical keys arrive as a stack of 64 plus 36', async () => {
      await h.consoleRun(server, 'tc give Admin common 100 physical', /Gave 100x Common key/)
      await h.until(() => h.count(admin, 'tripwire_hook') === 100, () => `have ${h.count(admin, 'tripwire_hook')} keys`)
      const stacks = admin.bot.inventory.items().filter(i => i.name === 'tripwire_hook').map(i => i.count).sort((a, b) => b - a)
      if (stacks.join(',') !== '64,36') throw new Error(`stacks: ${stacks}`)
    })

    await step('opening with a stack of physical keys uses exactly one', async () => {
      await standAt(admin, spots.admin)
      await admin.bot.equip(admin.bot.inventory.items().find(i => i.name === 'tripwire_hook' && i.count === 64), 'hand')
      const since = admin.mark()
      const win = await h.opens(admin, () => admin.bot.activateBlock(admin.bot.blockAt(at.common)))
      admin.bot.closeWindow(win)
      await admin.expect(/You won/, { since })
      await h.until(() => h.count(admin, 'tripwire_hook') === 99, () => `have ${h.count(admin, 'tripwire_hook')} keys, expected 99`)
    })

    await step('a key for another crate neither opens it nor gets used up', async () => {
      const before = new Set(admin.bot.inventory.items().filter(i => i.name === 'tripwire_hook').map(i => i.slot))
      await h.consoleRun(server, 'tc give Admin rare 1 physical', /Gave 1x Rare key/)
      await h.until(() => admin.bot.inventory.items().some(i => i.name === 'tripwire_hook' && !before.has(i.slot)), 'rare key never arrived')
      await admin.bot.equip(admin.bot.inventory.items().find(i => i.name === 'tripwire_hook' && !before.has(i.slot)), 'hand')
      await standAt(admin, spots.admin)
      const since = admin.mark()
      await admin.bot.activateBlock(admin.bot.blockAt(at.common))
      await admin.expect(/You need a Common key/, { since })
      await sleep(500)
      if (h.count(admin, 'tripwire_hook') !== 100) throw new Error(`key count changed to ${h.count(admin, 'tripwire_hook')}`)
    })

    await step('a second opening is refused while one is running', async () => {
      await h.consoleRun(server, 'tc give Admin common 1 virtual', /Gave 1x Common key/)
      await standAt(admin, spots.admin)
      const win = await h.opens(admin, () => admin.bot.activateBlock(admin.bot.blockAt(at.common)))
      await h.run(admin, '/tc open rare', /already opening a crate/)
      const since = admin.mark()
      admin.bot.closeWindow(win)
      await admin.expect(/You won/, { since })
      await h.run(admin, '/tc keys', /Common: 0/)
    })

    await step('a full inventory refuses to open and keeps the key', async () => {
      await h.consoleRun(server, 'tc give Admin rare 1 virtual', /Gave 1x Rare key/)
      await h.run(admin, '/give Admin minecraft:stone 2304', /Gave/)
      await h.until(() => admin.bot.inventory.emptySlotCount() === 0, 'inventory never filled up')
      await standAt(admin, spots.admin)
      const since = admin.mark()
      await admin.bot.activateBlock(admin.bot.blockAt(at.rare))
      await admin.expect(/Free up an inventory slot/, { since })
      await h.run(admin, '/tc keys', /Rare: 1/)
      await h.run(admin, '/kill @e[type=minecraft:item]', /Killed|No entity was found/)
      await h.run(admin, '/clear Admin minecraft:stone', /Removed/)
      await h.consoleRun(server, 'tc take Admin rare 1', /Took 1x Rare key/)
    })

    await step('instant crate: console commands, extra items, give-item false and a broadcast', async () => {
      const p = at.instant
      await h.run(admin, `/tc set instant ${p.x} ${p.y} ${p.z}`, /now a Instant crate/)
      await h.consoleRun(server, 'tc give Guest instant 1', /Gave 1x Instant key/)
      await standAt(guest, spots.guest)
      const emeralds = h.count(guest, 'emerald')
      const apples = h.count(guest, 'apple')
      const g = guest.mark()
      const a = admin.mark()
      let windowOpened = false
      const onOpen = () => { windowOpened = true }
      guest.bot.on('windowOpen', onOpen)
      await guest.bot.activateBlock(guest.bot.blockAt(p))
      await guest.expect(/You won Emerald Bundle/, { since: g })
      await admin.expect(/Guest won Emerald Bundle from Instant/, { since: a })
      await h.until(() => h.count(guest, 'emerald') === emeralds + 6 && h.count(guest, 'apple') === apples + 2,
        () => `got ${h.count(guest, 'emerald') - emeralds} emeralds (want 6: 5 + 1 from commands, none from the display item) and ${h.count(guest, 'apple') - apples} apples (want 2)`)
      guest.bot.off('windowOpen', onOpen)
      if (windowOpened) throw new Error('the instant crate opened a window')
      if (h.entitiesNear(admin, 'text_display', p.offset(0, 1, 0), 1.5).length !== 0) throw new Error('hologram: [] still spawned a text display')
    })

    await step('non-op players: no /tc, but /crates lists visible crates and right-click previews', async () => {
      await h.run(guest, '/tc list', /Unknown or incomplete command|Unknown command/i)
      const menu = await h.opens(guest, () => h.say(guest, '/crates'))
      if (!/Crates/.test(h.titleOf(menu))) throw new Error(`menu title ${h.titleOf(menu)}`)
      const shown = h.topSlots(menu).filter(Boolean).length
      if (shown !== 5) throw new Error(`menu shows ${shown} crates; expected 5 (big and solo are show-in-menu: false)`)
      const preview = await h.opens(guest, () => guest.bot.clickWindow(0, 1, 0))
      if (!/Preview/.test(h.titleOf(preview))) throw new Error(`right-click opened ${h.titleOf(preview)}`)
      guest.bot.closeWindow(preview)
    })

    await step('right-clicking without a key pushes the player back', async () => {
      await standAt(admin, home.offset(0, 0, -5)) // out of the way
      await standAt(guest, home.offset(1, 0, 0))
      // Checked on the wire: mineflayer doesn't apply velocity packets to itself, a real client does.
      const pushes = []
      const onPacket = (packet, meta) => {
        if (meta.name === 'entity_velocity' && packet.entityId === guest.bot.entity.id) pushes.push(packet)
      }
      guest.bot._client.on('packet', onPacket)
      const since = guest.mark()
      await guest.bot.activateBlock(guest.bot.blockAt(at.common))
      await guest.expect(/You need a Common key/, { since })
      await sleep(1000)
      guest.bot._client.off('packet', onPacket)
      const vx = p => p.velocity?.x ?? (p.velocityX !== undefined ? p.velocityX / 8000 : 0)
      if (!pushes.some(p => vx(p) < -0.3)) throw new Error(`no push away from the crate: ${JSON.stringify(pushes)}`)
    })

    await step('players without admin rights cannot break a crate', async () => {
      await standAt(guest, home.offset(1, 0, 0))
      h.emptyHand(guest)
      const since = guest.mark()
      await Promise.race([guest.bot.dig(guest.bot.blockAt(at.common), true).catch(() => {}), sleep(7000)])
      try { guest.bot.stopDigging() } catch {}
      if (guest.bot.currentWindow) guest.bot.closeWindow(guest.bot.currentWindow)
      await sleep(800)
      // Not the digger's own view: mineflayer predicts the block as broken client-side.
      const b = admin.bot.blockAt(at.common)
      if (b?.name !== 'chest') throw new Error(`the crate block is now ${b?.name}`)
      await guest.expectNone(/Removed the|Sneak while breaking/, { since, wait: 100 })
      const g = guest.mark()
      await standAt(guest, home.offset(1, 0, 0))
      await guest.bot.activateBlock(guest.bot.blockAt(at.common))
      await guest.expect(/You need a Common key/, { since: g })
    })

    await step('admins who left-click without sneaking see the preview and a removal hint (once a minute)', async () => {
      await standAt(admin, home.offset(1, 0, 0))
      h.emptyHand(admin)
      const since = admin.mark()
      const dig = async () => {
        await Promise.race([admin.bot.dig(admin.bot.blockAt(at.common), true).catch(() => {}), sleep(7000)])
        try { admin.bot.stopDigging() } catch {}
        await sleep(300)
        if (admin.bot.currentWindow) admin.bot.closeWindow(admin.bot.currentWindow)
        await sleep(500)
      }
      const preview = h.opens(admin, dig)
      if (!/Preview/.test(h.titleOf(await preview))) throw new Error('left-click did not open the preview')
      await admin.expect(/Sneak while breaking to remove this crate/, { since })
      await dig()
      const hints = admin.messages.slice(since).filter(m => /Sneak while breaking/.test(m)).length
      if (hints !== 1) throw new Error(`hint shown ${hints} times, expected once within the cooldown`)
      const seen = guest.bot.blockAt(at.common)?.name
      if (seen !== 'chest') throw new Error(`the crate was broken without sneaking (other player sees ${seen})`)
      if (!folia) await h.consoleRun(server, `execute if block ${at.common.x} ${at.common.y} ${at.common.z} minecraft:chest`, /Test passed/)
    })

    await step('TNT does not destroy crates', async () => {
      const crate = home.offset(20, 0, 0)
      const plain = home.offset(22, 0, 0)
      const tnt = home.offset(21, 0, 0)
      await setblock(crate, 'minecraft:chest')
      await setblock(plain, 'minecraft:chest')
      await h.run(admin, `/tc set rare ${crate.x} ${crate.y} ${crate.z}`, /now a Rare crate/)
      await h.run(admin, `/summon minecraft:tnt ${tnt.x + 0.5} ${tnt.y} ${tnt.z + 0.5} {fuse:1}`, /Summoned new/)
      await h.until(() => admin.bot.blockAt(plain)?.name !== 'chest', 'the TNT did not go off (control chest still there)', 8000)
      await sleep(500)
      if (admin.bot.blockAt(crate)?.name !== 'chest') throw new Error(`crate is now ${admin.bot.blockAt(crate)?.name}`)
      await h.run(admin, `/tc remove ${crate.x} ${crate.y} ${crate.z}`, /Removed the Rare crate/)
    })

    await step('pistons can neither push nor pull a crate', async () => {
      const B = home.offset(8, 0, 8)
      const rows = { pushCrate: B, pushPlain: B.offset(0, 0, 3), pullCrate: B.offset(0, 0, 6), pullPlain: B.offset(0, 0, 9) }
      const goldOf = name => rows[name].offset(name.startsWith('pull') ? 3 : 2, 0, 0)
      for (const name of Object.keys(rows)) {
        const gold = goldOf(name)
        await setblock(gold, 'minecraft:gold_block')
        if (name.endsWith('Crate')) await h.run(admin, `/tc set legendary ${gold.x} ${gold.y} ${gold.z}`, /now a Legendary crate/)
        await setblock(rows[name].offset(1, 0, 0), name.startsWith('pull') ? 'minecraft:sticky_piston[facing=east]' : 'minecraft:piston[facing=east]')
      }
      for (const r of Object.values(rows)) await setblock(r, 'minecraft:redstone_block')
      await sleep(1000)
      for (const name of ['pullCrate', 'pullPlain']) await setblock(rows[name], 'minecraft:air')
      await sleep(1000)
      const name = p => admin.bot.blockAt(p)?.name
      const problems = []
      if (name(rows.pushPlain.offset(3, 0, 0)) !== 'gold_block') problems.push('control: the piston did not push a plain block')
      if (name(rows.pullPlain.offset(2, 0, 0)) !== 'gold_block') problems.push('control: the sticky piston did not pull a plain block')
      if (name(goldOf('pushCrate')) !== 'gold_block') problems.push(`pushed crate moved (now ${name(goldOf('pushCrate'))})`)
      if (name(goldOf('pullCrate')) !== 'gold_block') problems.push(`pulled crate moved (now ${name(goldOf('pullCrate'))})`)
      for (const n of ['pushCrate', 'pullCrate']) {
        const g = goldOf(n)
        await h.run(admin, `/tc remove ${g.x} ${g.y} ${g.z}`, /Removed the Legendary crate|That block is not a crate/)
      }
      if (problems.length) throw new Error(problems.join('; '))
    })

    await step('/tc additem stores the held item exactly (base64) and it can be won', async () => {
      await standAt(admin, spots.admin)
      h.emptyHand(admin)
      await h.run(admin, '/tc additem solo 5', /Hold the item you want to add/)
      await h.run(admin, '/give Admin minecraft:diamond_sword 1', /Gave/)
      await h.until(() => h.count(admin, 'diamond_sword') === 1, 'sword never arrived')
      await admin.bot.equip(admin.bot.inventory.items().find(i => i.name === 'diamond_sword'), 'hand')
      await h.run(admin, '/enchant Admin minecraft:sharpness 5', /Applied enchantment/)
      await h.run(admin, '/tc additem solo 1000000', /Added your held item to Solo with weight 1000000/)
      const yml = fs.readFileSync(path.join(data, 'crates', 'solo.yml'), 'utf8')
      if (!/diamond_sword:\s*\r?\n\s*weight: 1000000/.test(yml) || !/base64: /.test(yml)) throw new Error(`solo.yml:\n${yml}`)
      await h.run(admin, '/tc open solo', /You won/)
      await h.until(() => h.count(admin, 'diamond_sword') === 2, 'the won sword never arrived')
      // Folia has no /data; there the enchantment round trip is only checked on Paper.
      if (!folia) {
        const nbt = await h.run(admin, '/data get entity @s Inventory', /has the following entity data/)
        const sharp = (nbt.match(/sharpness"?\s*:\s*5/g) ?? []).length
        if (sharp < 2) throw new Error(`expected both swords to have Sharpness V, found ${sharp}`)
      }
      await h.run(admin, '/tc list', /solo .*\(2 rewards, instant\)/)
    })

    await step('preview pages through 50 rewards, 45 per page, and leaks nothing', async () => {
      const w = await h.opens(admin, () => h.say(admin, '/tc preview big'))
      const shown = () => h.topSlots(w).slice(0, 45).filter(Boolean).length
      if (shown() !== 45) throw new Error(`page 1 shows ${shown()}`)
      if (w.slots[53]?.name !== 'arrow' || w.slots[45]?.name === 'arrow') throw new Error('page 1 arrows are wrong')
      await admin.bot.clickWindow(53, 0, 0)
      await h.until(() => shown() === 5, () => `page 2 shows ${shown()}`)
      if (w.slots[45]?.name !== 'arrow') throw new Error('page 2 has no previous-page arrow')
      await admin.bot.clickWindow(45, 0, 0)
      await h.until(() => shown() === 45, 'previous page did not go back')
      await admin.bot.clickWindow(3, 0, 0).catch(() => {})
      const closed = h.windowClosed(admin)
      await admin.bot.clickWindow(49, 0, 0)
      await closed
      await sleep(500)
      if (h.count(admin, 'stone') !== 0) throw new Error('a preview item ended up in the inventory')
    })

    await step('display crate: a second player has to wait while the first opens it', async () => {
      await h.consoleRun(server, 'tc give Admin legendary 1 virtual', /Gave 1x Legendary/)
      await h.consoleRun(server, 'tc give Guest legendary 2 virtual', /Gave 2x Legendary/)
      await standAt(admin, spots.admin)
      await standAt(guest, spots.guest)
      h.emptyHand(admin)
      const a = admin.mark()
      const g = guest.mark()
      const item = () => h.entitiesNear(admin, 'item_display', at.legendary.offset(0, 1, 0)).length
      await admin.bot.activateBlock(admin.bot.blockAt(at.legendary))
      await h.until(() => item() === 1, 'no item display appeared', 3000)
      await guest.bot.activateBlock(guest.bot.blockAt(at.legendary))
      await guest.expect(/Someone is opening this crate/, { since: g })
      await admin.expect(/You won/, { since: a, timeout: 15_000 })
      await h.until(() => item() === 0, 'item display stayed after the animation', 8000)
      await h.run(admin, '/tc keys Guest', /Legendary: 2/)
      const g2 = guest.mark()
      await guest.bot.activateBlock(guest.bot.blockAt(at.legendary))
      await guest.expect(/You won/, { since: g2, timeout: 15_000 })
      await h.until(() => item() === 0, 'item display stayed after the second animation', 8000)
    })

    await step('logging out during the display animation still grants the reward', async () => {
      await standAt(guest, spots.guest)
      const before = h.count(guest)
      await guest.bot.activateBlock(guest.bot.blockAt(at.legendary))
      await h.until(() => h.entitiesNear(admin, 'item_display', at.legendary.offset(0, 1, 0)).length === 1, 'no item display appeared', 3000)
      guest.quit()
      await sleep(1500)
      guest = await join('Guest')
      await sleep(1500)
      if (h.count(guest) <= before) throw new Error(`inventory did not grow (${before} -> ${h.count(guest)})`)
      await h.until(() => h.entitiesNear(admin, 'item_display', at.legendary.offset(0, 1, 0)).length === 0, 'item display left behind', 8000)
      await h.run(admin, '/tc keys Guest', /Legendary: 0/)
    })

    let keySlot
    await step('physical keys cannot be placed', async () => {
      await h.consoleRun(server, 'tc give Guest common 1 physical', /Gave 1x Common key/)
      await h.until(() => h.count(guest, 'tripwire_hook') === 1, 'key never arrived')
      const wall = home.offset(0, 0, 6)
      await setblock(wall, 'minecraft:stone')
      await standAt(guest, home.offset(0, 0, 4))
      await guest.bot.equip(guest.bot.inventory.items().find(i => i.name === 'tripwire_hook'), 'hand')
      keySlot = guest.bot.inventory.items().find(i => i.name === 'tripwire_hook').slot
      const face = home.minus(home.offset(0, 0, 1)) // (0,0,-1): the side facing the player
      await guest.bot.placeBlock(guest.bot.blockAt(wall), face).catch(() => {})
      await sleep(800)
      const placed = admin.bot.blockAt(wall.offset(0, 0, -1))?.name
      if (placed !== 'air') throw new Error(`the key was placed as ${placed}`)
      if (h.count(guest, 'tripwire_hook') !== 1) throw new Error('the key vanished')
    })

    await step('physical keys cannot be used in crafting (control: a plain tripwire hook can)', async () => {
      await h.run(admin, '/give Guest minecraft:chest 1', /Gave/)
      await h.run(admin, '/give Guest minecraft:tripwire_hook 1', /Gave/)
      await h.until(() => h.count(guest, 'chest') === 1 && h.count(guest, 'tripwire_hook') === 2, 'items never arrived')
      const inv = guest.bot.inventory
      // The key and the plain hook don't stack, so the plain one lands in another slot.
      const key = inv.items().find(i => i.name === 'tripwire_hook' && i.slot === keySlot)
      const plain = inv.items().find(i => i.name === 'tripwire_hook' && i.slot !== keySlot)
      if (!key || !plain) throw new Error('could not tell the key from the plain hook')
      const chest = inv.items().find(i => i.name === 'chest')
      await guest.bot.moveSlotItem(chest.slot, 2)
      await guest.bot.moveSlotItem(key.slot, 1)
      await sleep(800)
      const withKey = inv.slots[0]?.name ?? 'nothing'
      await guest.bot.moveSlotItem(1, key.slot)
      await guest.bot.moveSlotItem(plain.slot, 1)
      await sleep(800)
      const withPlain = inv.slots[0]?.name ?? 'nothing'
      await guest.bot.moveSlotItem(1, plain.slot).catch(() => {})
      await guest.bot.moveSlotItem(2, chest.slot).catch(() => {})
      if (withPlain !== 'trapped_chest') throw new Error(`control failed: plain hook + chest crafts ${withPlain}`)
      if (withKey !== 'nothing') throw new Error(`crate key + chest crafts ${withKey}`)
    })

    await step('virtual keys are autosaved (autosave-minutes: 1) without a restart', async () => {
      const dir = path.join(data, 'data', 'players')
      const saved = () => fs.existsSync(dir) && fs.readdirSync(dir).filter(f => f.endsWith('.yml'))
        .some(f => /big: 7/.test(fs.readFileSync(path.join(dir, f), 'utf8')))
      await h.until(saved, 'no player file with big: 7 was written by the autosave', Math.max(5000, lastReload + 80_000 - Date.now()))
    })

    await step('after a restart: crate blocks, holograms and key balances are back', async () => {
      admin.quit()
      guest.quit()
      const since = server.lines.length
      await server.restart()
      await server.waitFor(/Loaded 7 crate\(s\) and 4 crate block\(s\)/, 1000, since)
      admin = await join('Admin')
      admin.op = true
      guest = await join('Guest')
      await sleep(1500)
      await h.until(() => hologramsAt(['common', 'rare', 'legendary']).every(n => n === 1) && hologramsAt(['instant'])[0] === 0,
        () => `holograms after restart: ${hologramsAt(['common', 'rare', 'legendary', 'instant'])}`)
      await h.run(admin, '/tc keys Guest', /Big: 7/)
      await h.consoleRun(server, 'tc give Admin common 1 virtual', /Gave 1x Common key/)
      await standAt(admin, spots.admin)
      h.emptyHand(admin)
      const since2 = admin.mark()
      const win = await h.opens(admin, () => admin.bot.activateBlock(admin.bot.blockAt(at.common)))
      admin.bot.closeWindow(win)
      await admin.expect(/You won/, { since: since2 })
    })

    admin.quit()
    guest.quit()
  }
}
