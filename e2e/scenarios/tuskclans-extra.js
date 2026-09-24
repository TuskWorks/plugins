'use strict'
// Extended scenario for TuskClans: the rules and edge cases tuskclans.js doesn't reach —
// invites, open clans, kicks (also offline), transfer, limits, expiry, arrows, stats,
// home cancel/bypass, Vault cost, zh_TW and admin disband.

const path = require('node:path')
const h = require('../lib/helpers')
const { sleep } = h

const ROOT = path.join(__dirname, '..', '..')
const CLANS = path.join(ROOT, 'tuskclans', 'build', 'libs')
const ECONOMY = path.join(ROOT, 'e2e', 'fixtures', 'test-economy', 'build', 'libs')
const economyJar = () => h.optionalJar(ECONOMY, /^TestEconomy.*\.jar$/)

module.exports = {
  name: 'tuskclans-extra',
  logName: 'TuskClans',
  packagePrefix: 'io.github.tuskworks.clans',
  // The in-memory Vault is optional: without it only the creation cost step is skipped.
  plugins: () => [h.newestJar(CLANS, /^TuskClans-.*\.jar$/, 'run ./gradlew :tuskclans:build'), economyJar()].filter(Boolean),

  async run ({ server, join, step }) {
    const cfgFile = path.join(server.dir, 'plugins', 'TuskClans', 'config.yml')
    const setConfig = async (from, to) => {
      h.setYamlLine(cfgFile, from, to)
      await h.consoleRun(server, 'clan admin reload', /TuskClans (reloaded|已重新載入)/)
    }
    const r = h.run

    let alice = await join('Alice')
    let bob = await join('Bob')
    let carol = await join('Carol')
    let dave = await join('Dave')
    server.command('gamemode survival @a')
    server.command('difficulty easy')
    await sleep(500)

    await step('tag and name validation', async () => {
      await r(bob, '/clan create AB! Name', /contains characters that are not allowed/)
      await r(bob, `/clan create ABC ${'x'.repeat(25)}`, /Clan names must be 1-24 characters long/)
      await r(bob, '/clan info', /You are not in a clan/)
    })

    await step('declining an invite', async () => {
      await r(alice, '/clan create TUSK Tusk Riders', /Clan \[TUSK\] Tusk Riders created/)
      const b = bob.mark()
      await r(alice, '/clan invite Bob', /Invited Bob/)
      await bob.expect(/Alice invited you to \[TUSK\] Tusk Riders/, { since: b })
      await r(bob, '/clan deny', /You declined the invite from TUSK/)
      await r(bob, '/clan accept', /You have no pending clan invites/)
    })

    await step('open clans can be joined with /clan join, closed ones cannot', async () => {
      await r(carol, '/clan create MAMO Mammoths', /Clan \[MAMO\] Mammoths created/)
      await r(dave, '/clan join MAMO', /MAMO is invite-only/)
      await r(alice, '/clan open', /Your clan is now open/)
      const a = alice.mark()
      await r(bob, '/clan join TUSK', /You joined \[TUSK\] Tusk Riders/)
      await alice.expect(/Bob joined the clan/, { since: a })
      await r(dave, '/clan join tusk', /You joined \[TUSK\] Tusk Riders/)
    })

    await step('officers kick members; members cannot kick', async () => {
      await r(dave, '/clan kick Bob', /rank is too low/)
      await r(alice, '/clan promote Bob', /Bob is now an officer/)
      await r(dave, '/clan chat', /talking in clan chat/)
      const d = dave.mark()
      await r(bob, '/clan kick Dave', /Kicked Dave from the clan/)
      await dave.expect(/You were kicked from TUSK by Bob/, { since: d })
      // A kicked player in clan chat mode is switched back to public chat and told so
      await dave.expect(/talking in public chat/, { since: d })
      await r(dave, '/clan info', /You are not in a clan/)
    })

    await step('an offline member can be kicked', async () => {
      await r(dave, '/clan join TUSK', /You joined/)
      dave.quit()
      await sleep(1000)
      await r(bob, '/clan kick Dave', /Kicked Dave from the clan/)
      dave = await join('Dave')
      await r(dave, '/clan info', /You are not in a clan/)
    })

    await step('demote, transfer with confirmation, and leave', async () => {
      await r(alice, '/clan demote Bob', /Bob is now a member/)
      await r(alice, '/clan demote Bob', /Bob is not an officer/)
      await r(alice, '/clan transfer Bob', /This makes Bob the clan leader/)
      await r(carol, '/clan info TUSK', /Leader: Alice/)
      await r(alice, '/clan transfer Bob confirm', /Bob is now the clan leader\. You are an officer/)
      await r(carol, '/clan info TUSK', /Leader: Bob/)
      await r(alice, '/clan disband confirm', /rank is too low/)
      const b = bob.mark()
      await r(alice, '/clan leave', /You left TUSK/)
      await bob.expect(/Alice left the clan/, { since: b })
    })

    await step('alliances can be ended with /clan unally', async () => {
      await r(bob, '/clan ally MAMO', /Alliance request sent to MAMO/)
      await r(carol, '/clan ally TUSK', /now allied with TUSK/)
      const b = bob.mark()
      await r(carol, '/clan unally TUSK', /alliance with TUSK has ended/)
      await bob.expect(/alliance with MAMO has ended/, { since: b })
      await r(carol, '/clan info TUSK', /Allies: none/)
    })

    await step('ally limit (max-allies: 1)', async () => {
      await setConfig('max-allies: 3', 'max-allies: 1')
      await r(alice, '/clan create ALCE Alices', /Clan \[ALCE\] Alices created/)
      await r(bob, '/clan ally MAMO', /Alliance request sent to MAMO/)
      await r(carol, '/clan ally TUSK', /now allied with TUSK/)
      await r(bob, '/clan ally ALCE', /already has the maximum of 1 allies/)
    })

    await step('member limit (max-members: 2) for invites and open joins', async () => {
      await setConfig('max-members: 20', 'max-members: 2')
      const d = dave.mark()
      await r(bob, '/clan invite Dave', /Invited Dave/)
      await dave.expect(/Bob invited you to \[TUSK\]/, { since: d })
      await r(dave, '/clan accept', /You joined \[TUSK\]/)
      await r(alice, '/clan disband confirm', /Clan ALCE has been disbanded/)
      await r(alice, '/clan join TUSK', /The clan is full \(2 members\)/)
      await r(bob, '/clan invite Alice', /The clan is full \(2 members\)/)
      await setConfig('max-members: 2', 'max-members: 20')
    })

    await step('pending invites are shown again on join', async () => {
      await r(bob, '/clan invite Alice', /Invited Alice/)
      alice.quit()
      await sleep(1000)
      alice = await join('Alice')
      try {
        await alice.expect(/You have pending clan invites: TUSK/, { timeout: 5000 })
      } finally {
        await r(alice, '/clan deny', /You declined the invite from TUSK/)
      }
    })

    await step('invites expire (invite-expire-seconds: 5)', async () => {
      await setConfig('invite-expire-seconds: 120', 'invite-expire-seconds: 5')
      await r(bob, '/clan invite Alice', /Invited Alice/)
      await sleep(6500)
      await r(alice, '/clan accept', /You have no pending clan invites/)
      await setConfig('invite-expire-seconds: 5', 'invite-expire-seconds: 120')
    })

    const giveItem = async (tb, item, n) => {
      const before = h.count(tb, item)
      server.command(`give ${tb.name} minecraft:${item} ${n}`)
      await h.until(() => h.count(tb, item) >= before + n, `${tb.name} never received ${item}`)
    }
    const heal = async tb => {
      server.command(`effect give ${tb.name} minecraft:instant_health 1 5 true`)
      await sleep(1200)
    }
    const shoot = async (shooter, target) => {
      await shooter.bot.equip(shooter.bot.inventory.items().find(i => i.name === 'bow'), 'hand')
      const victim = shooter.entityOf(target)
      if (!victim) throw new Error(`${shooter.name} can't see ${target.name}`)
      await shooter.bot.lookAt(victim.position.offset(0, 1.2, 0), true)
      shooter.bot.activateItem()
      await sleep(1200)
      shooter.bot.deactivateItem()
      await sleep(1500)
    }

    await step('arrows from clanmates do no damage; arrows from others do', async () => {
      await giveItem(dave, 'bow', 1)
      await giveItem(dave, 'arrow', 16)
      await giveItem(alice, 'bow', 1)
      await giveItem(alice, 'arrow', 16)
      const base = bob.bot.entity.position.floored()
      server.command(`tp Dave ${base.x + 6.5} ${base.y} ${base.z + 0.5}`)
      server.command(`tp Alice ${base.x + 6.5} ${base.y} ${base.z + 4.5}`)
      await sleep(1500)
      await heal(bob)
      const before = bob.bot.health
      await shoot(dave, bob)
      if (bob.bot.health < before) throw new Error(`clanmate arrow hurt: ${before} -> ${bob.bot.health}`)
      let hurt = false
      for (let attempt = 0; attempt < 3 && !hurt; attempt++) {
        await heal(bob)
        const b = bob.bot.health
        await shoot(alice, bob)
        hurt = bob.bot.health < b
      }
      if (!hurt) throw new Error('control failed: arrows from a non-member never hurt Bob')
    })

    await step('kills and deaths count per clan, friendly kills don\'t, and stats survive a restart', async () => {
      const p = bob.bot.entity.position
      for (const tb of [alice, carol, dave]) server.command(`tp ${tb.name} ${p.x} ${p.y} ${p.z}`)
      await sleep(1500)
      for (const name of ['Carol', 'Alice', 'Bob']) await h.consoleRun(server, `op ${name}`, new RegExp(`Made ${name} a server operator`))
      // Clanless Alice dies to MAMO's Carol: MAMO +1 kill
      await r(carol, '/damage Alice 1000 minecraft:player_attack by Carol', /Applied 1000/)
      await sleep(2500)
      // MAMO's Carol dies to clanless Alice: MAMO +1 death
      await r(alice, '/damage Carol 1000 minecraft:player_attack by Alice', /Applied 1000/)
      await sleep(2500)
      // Friendly fire is off in TUSK, so even /damage attributed to Bob can't hurt Dave
      await heal(dave)
      const hp = dave.bot.health
      await r(bob, '/damage Dave 1000 minecraft:player_attack by Bob', /Applied 1000|invulnerable/)
      await sleep(800)
      if (dave.bot.health < hp) throw new Error(`friendly fire is off, but Dave took damage (${hp} -> ${dave.bot.health})`)
      // With friendly fire on the kill happens, but it doesn't count for either side
      await r(bob, '/clan ff', /Friendly fire is now ON/)
      await r(bob, '/damage Dave 1000 minecraft:player_attack by Bob', /Applied 1000/)
      await sleep(2500)
      await r(bob, '/clan ff', /Friendly fire is now OFF/)
      for (const name of ['Carol', 'Alice', 'Bob']) await h.consoleRun(server, `deop ${name}`, /no longer a server operator/)
      await r(carol, '/clan info MAMO', /Kills: 1\s+Deaths: 1/)
      await r(carol, '/clan info TUSK', /Kills: 0\s+Deaths: 0/)
      await r(carol, '/clan top', /1\. \[MAMO\] Mammoths/)
      for (const tb of [alice, bob, carol, dave]) tb.quit()
      await server.restart()
      carol = await join('Carol')
      await r(carol, '/clan info MAMO', /Kills: 1\s+Deaths: 1/)
      alice = await join('Alice')
      bob = await join('Bob')
      dave = await join('Dave')
    })

    await step('home: moving cancels the warmup; delhome; no home', async () => {
      await r(bob, '/clan sethome', /Clan home set/)
      server.command('execute as Bob at @s run tp @s ~15 ~ ~15')
      await sleep(1500)
      await r(bob, '/clan home', /Teleporting to the clan home in 3s/)
      const since = bob.mark()
      server.command('execute as Bob at @s run tp @s ~2 ~ ~')
      await bob.expect(/Teleport cancelled because you moved/, { since, timeout: 5000 })
      await r(bob, '/clan delhome', /Clan home removed/)
      await r(bob, '/clan home', /Your clan has no home/)
    })

    await step('ops skip the home warmup and cooldown', async () => {
      await r(bob, '/clan sethome', /Clan home set/)
      const home = bob.bot.entity.position.clone()
      await h.consoleRun(server, 'op Dave', /Made Dave a server operator/)
      server.command('execute as Dave at @s run tp @s ~20 ~ ~20')
      await sleep(1500)
      const since = dave.mark()
      await r(dave, '/clan home', /Welcome to the clan home/)
      await sleep(500)
      if (dave.bot.entity.position.distanceTo(home) > 2) throw new Error('Dave did not arrive at the home')
      await r(dave, '/clan home', /Welcome to the clan home/)
      if (dave.messages.slice(since).some(m => /Teleporting to the clan home in|Wait \d+s/.test(m))) throw new Error('op still got a warmup or cooldown')
      await h.consoleRun(server, 'deop Dave', /no longer a server operator/)
    })

    await step('creating a clan costs money when Vault is present (create-cost: 100)', async () => {
      if (!economyJar()) h.skip('the test economy (e2e/fixtures/test-economy) is not built')
      await setConfig('create-cost: 0', 'create-cost: 100')
      await h.consoleRun(server, 'testeco set Alice 50', /balance Alice set to 50/)
      await r(alice, '/clan create RICH Rich', /Creating a clan costs/)
      await r(alice, '/clan info', /You are not in a clan/)
      await h.consoleRun(server, 'testeco set Alice 150', /balance Alice set to 150/)
      // Requests that can't succeed are rejected before any money moves
      await r(alice, '/clan create TUSK Copycats', /The tag TUSK is already taken/)
      await r(alice, '/clan create RI! Rich', /contains characters that are not allowed/)
      await h.consoleRun(server, 'testeco balance Alice', /balance Alice = 150\.00/)
      await r(alice, '/clan create RICH Rich', /Paid .* to create the clan|Clan \[RICH\] Rich created/)
      await alice.expect(/Clan \[RICH\] Rich created/)
      await h.consoleRun(server, 'testeco balance Alice', /balance Alice = 50\.00/)
      await setConfig('create-cost: 100', 'create-cost: 0')
    })

    await step('Traditional Chinese messages (language: zh_TW)', async () => {
      await setConfig('language: en', 'language: zh_TW')
      await r(carol, '/clan info NOPE', /找不到標籤為 NOPE 的公會/)
      await setConfig('language: zh_TW', 'language: en')
      await r(carol, '/clan info NOPE', /No clan with the tag NOPE/)
    })

    await step('admins can disband any clan', async () => {
      await r(bob, '/clan ally MAMO', /Alliance request sent|already allied/)
      await r(carol, '/clan info MAMO', /Allies: TUSK/)
      await r(carol, '/clan allychat', /talking in ally chat/)
      const c = carol.mark()
      await h.consoleRun(server, 'clan admin disband MAMO', /Disbanded MAMO/)
      await carol.expect(/Your clan MAMO has been disbanded/, { since: c })
      await carol.expect(/talking in public chat/, { since: c })
      await r(bob, '/clan info TUSK', /Allies: none/)
      await r(carol, '/clan info MAMO', /No clan with the tag MAMO/)
    })

    for (const tb of [alice, bob, carol, dave]) tb.quit()
  }
}
