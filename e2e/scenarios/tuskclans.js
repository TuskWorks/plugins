'use strict'
// End-to-end scenario for TuskClans: three real clients drive the plugin through
// clan lifecycle, chat channels, friendly fire, alliances, homes and persistence.

const fs = require('node:fs')
const path = require('node:path')
const { TestBot, sleep } = require('../lib/bots')

const LIBS = path.join(__dirname, '..', '..', 'tuskclans', 'build', 'libs')

function pluginJar () {
  const jars = fs.existsSync(LIBS) ? fs.readdirSync(LIBS).filter(f => /^TuskClans-.*\.jar$/.test(f)) : []
  if (jars.length === 0) throw new Error('TuskClans jar not found — run ./gradlew :tuskclans:build first')
  return jars.map(f => path.join(LIBS, f)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0]
}

async function hit (attacker, victim) {
  const target = attacker.entityOf(victim)
  if (!target) throw new Error(`${attacker.name} can't see ${victim.name}`)
  await attacker.bot.lookAt(target.position.offset(0, 1.6, 0), true)
  attacker.bot.attack(target)
  await sleep(700)
}

async function healthAfterHit (attacker, victim, server) {
  server.command(`effect clear ${victim.name}`)
  server.command(`effect give ${victim.name} minecraft:instant_health 1 5 true`)
  await sleep(1200)
  const before = victim.bot.health
  await hit(attacker, victim)
  return { before, after: victim.bot.health }
}

module.exports = {
  name: 'tuskclans',
  logName: 'TuskClans',
  packagePrefix: 'io.github.tuskworks.clans',
  plugins: () => [pluginJar()],

  /** Console-only checks for server versions the bots can't join yet. */
  async smoke ({ server, step }) {
    await step('plugin enables', async () => {
      await server.waitFor(/\[TuskClans\] Loaded 0 clan\(s\)/, 1000, 0)
    })
    await step('console commands', async () => {
      const expectConsole = async (cmd, re) => {
        const since = server.lines.length
        server.command(cmd)
        await server.waitFor(re, 5000, since)
      }
      await expectConsole('clan help', /TuskClans/)
      await expectConsole('clan list', /There are no clans yet/)
      await expectConsole('clan top', /There are no clans yet/)
      await expectConsole('clan info NOPE', /No clan with the tag NOPE/)
      await expectConsole('clan create TUSK Tusk', /Only players can use this command/)
      await expectConsole('clan admin reload', /TuskClans reloaded/)
    })
  },

  async run ({ server, join, step }) {
    let alice = await join('Alice')
    const bob = await join('Bob')
    let carol = await join('Carol')
    server.command('gamemode survival @a')
    server.command('difficulty easy')

    await step('help and tab completion', async () => {
      await alice.run('/clan help', /TuskClans/)
      const completions = await alice.bot.tabComplete('/clan inv')
      const names = completions.map(c => (typeof c === 'string' ? c : c.match))
      if (!names.includes('invite')) throw new Error(`tab completion gave ${JSON.stringify(names)}`)
    })

    await step('create validates input', async () => {
      await alice.run('/clan create X Nope', /must be 2-6 characters/)
      await alice.run('/clan create TOOLONG Nope', /must be 2-6 characters/)
      await alice.run('/clan create TUSK Tusk Riders', /Clan \[TUSK\] Tusk Riders created/)
      await carol.run('/clan create tusk Copycat', /already taken/)
    })

    await step('invite and accept', async () => {
      const bobSince = bob.mark()
      await alice.run('/clan invite Bob', /Invited Bob/)
      await bob.expect(/Alice invited you to \[TUSK\] Tusk Riders/, { since: bobSince })
      const aliceSince = alice.mark()
      await bob.run('/clan accept', /You joined \[TUSK\] Tusk Riders/)
      await alice.expect(/Bob joined the clan/, { since: aliceSince })
      await bob.run('/clan invite Carol', /rank is too low/)
    })

    await step('clan menu', async () => {
      const opened = new Promise((resolve, reject) => {
        alice.bot.once('windowOpen', resolve)
        setTimeout(() => reject(new Error('menu did not open')), 5000)
      })
      alice.say('/clan')
      const window = await opened
      if (window.slots[4]?.name !== 'white_banner') throw new Error(`slot 4 is ${window.slots[4]?.name}`)
      const heads = window.slots.slice(9, 45).filter(item => item?.name === 'player_head').length
      if (heads !== 2) throw new Error(`expected 2 member heads, got ${heads}`)
      const since = alice.mark()
      await alice.bot.clickWindow(46, 0, 0)
      await alice.expect(/talking in clan chat/, { since })
      await alice.run('/clan chat', /talking in public chat/)
    })

    await step('clan chat reaches members only', async () => {
      const a = alice.mark()
      const c = carol.mark()
      bob.say('/clan chat hello team')
      await alice.expect(/\[Clan\] Bob: hello team/, { since: a })
      await carol.expectNone(/hello team/, { since: c })
    })

    await step('clan chat toggle', async () => {
      await bob.run('/clan chat', /talking in clan chat/)
      const a = alice.mark()
      const c = carol.mark()
      bob.say('secret plans')
      await alice.expect(/\[Clan\] Bob: secret plans/, { since: a })
      await carol.expectNone(/secret plans/, { since: c })
      await bob.run('/clan chat', /talking in public chat/)
    })

    await step('public chat shows clan tag', async () => {
      const c = carol.mark()
      alice.say('hello world')
      await carol.expect(/\[TUSK\].*Alice.*hello world/, { since: c })
    })

    await step('friendly fire is blocked between clanmates', async () => {
      server.command('tp Alice Bob')
      server.command('tp Carol Bob')
      await sleep(1500)
      const own = await healthAfterHit(alice, bob, server)
      if (own.after < own.before) throw new Error(`clanmate hit went through: ${own.before} -> ${own.after}`)
      const other = await healthAfterHit(carol, bob, server)
      if (!(other.after < other.before)) throw new Error(`outsider hit was blocked: ${other.before} -> ${other.after}`)
    })

    await step('friendly fire toggle', async () => {
      const b = bob.mark()
      await alice.run('/clan ff', /Friendly fire is now ON/)
      await bob.expect(/Friendly fire is now ON/, { since: b })
      const on = await healthAfterHit(alice, bob, server)
      if (!(on.after < on.before)) throw new Error(`hit blocked with ff on: ${on.before} -> ${on.after}`)
      await alice.run('/clan ff', /Friendly fire is now OFF/)
    })

    await step('alliances', async () => {
      await carol.run('/clan create MAMO Mammoths', /Clan \[MAMO\] Mammoths created/)
      const c = carol.mark()
      await alice.run('/clan ally MAMO', /Alliance request sent to MAMO/)
      await carol.expect(/TUSK wants to become allies/, { since: c })
      const a = alice.mark()
      await carol.run('/clan ally TUSK', /now allied with TUSK/)
      await alice.expect(/allied with MAMO/, { since: a })

      const allyHit = await healthAfterHit(carol, bob, server)
      if (allyHit.after < allyHit.before) throw new Error(`ally hit went through: ${allyHit.before} -> ${allyHit.after}`)

      const b = bob.mark()
      carol.say('/clan allychat hi allies')
      await bob.expect(/\[Ally\] \[MAMO\] Carol: hi allies/, { since: b })
    })

    await step('ranks: promote, kick protection, leave', async () => {
      await alice.run('/clan promote Bob', /Bob is now an officer/)
      await bob.run('/clan kick Alice', /their rank is equal or higher/)
      await alice.run('/clan leave', /Leaders can't leave/)
    })

    await step('clan home with warmup', async () => {
      await alice.run('/clan sethome', /Clan home set/)
      const home = alice.bot.entity.position.clone()
      server.command('execute as Alice at @s run tp @s ~40 ~ ~40')
      await sleep(1500)
      if (alice.bot.entity.position.distanceTo(home) < 20) throw new Error('setup teleport did not happen')
      const since = alice.mark()
      await alice.run('/clan home', /Teleporting to the clan home in 3s/)
      await alice.expect(/Welcome to the clan home/, { timeout: 8000, since })
      await sleep(500)
      const dist = alice.bot.entity.position.distanceTo(home)
      if (dist > 2) throw new Error(`ended ${dist.toFixed(1)} blocks from home`)
      await alice.run('/clan home', /Wait \d+s before using the clan home again/)
    })

    await step('info, list and top', async () => {
      await carol.run('/clan info TUSK', /Leader: Alice/)
      await carol.expect(/Allies: MAMO/)
      await carol.run('/clan list', /\[TUSK\] Tusk Riders — 2 members/)
      await carol.run('/clan top', /Top clans/)
    })

    await step('data survives a restart', async () => {
      await server.restart()
      carol = await join('Carol')
      await carol.run('/clan info TUSK', /Leader: Alice/)
      await carol.expect(/Members \(.*2\/20\): .*Bob/)
      await carol.expect(/Allies: MAMO/)
      alice = await join('Alice')
    })

    await step('disband needs confirmation and cleans up', async () => {
      await alice.run('/clan disband', /disband confirm/)
      await alice.run('/clan disband confirm', /Clan TUSK has been disbanded/)
      await carol.run('/clan info MAMO', /Allies: none/)
      await carol.run('/clan info TUSK', /No clan with the tag TUSK/)
    })

    for (const bot of [alice, carol]) bot.quit()
  }
}
