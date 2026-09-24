'use strict'
// Extended scenario for TuskOrders: input limits, money edge cases (no funds, fees, taxes,
// admin cancel, expiry refunds) and item safety (damaged items, logging out or a server stop
// with items in the delivery menu, collecting into a full inventory), plus zh_TW.

const fs = require('node:fs')
const path = require('node:path')
const h = require('../lib/helpers')
const { sleep } = h

const ROOT = path.join(__dirname, '..', '..')
const ORDERS = path.join(ROOT, 'tuskorders', 'build', 'libs')
const ECONOMY = path.join(ROOT, 'e2e', 'fixtures', 'test-economy', 'build', 'libs')

async function balance (server, name) {
  const line = await h.consoleRun(server, `testeco balance ${name}`, new RegExp(`balance ${name} = ([\\d.]+)`))
  return Number(/= ([\d.]+)/.exec(line)[1])
}

async function expectBalance (server, name, expected) {
  const actual = await balance(server, name)
  if (Math.abs(actual - expected) > 0.001) throw new Error(`${name} has $${actual}, expected $${expected}`)
}

/** Gives items from the console and waits until the bot has them. */
async function give (server, tb, item, amount, name = item.replace(/\[.*$/, '')) {
  const before = h.count(tb, name)
  server.command(`give ${tb.name} minecraft:${item} ${amount}`)
  await h.until(() => h.count(tb, name) >= before + amount, `${tb.name} never received ${amount} ${item}`)
}

function slotOf (window, name) {
  const slot = h.topSlots(window).findIndex(s => s?.name === name)
  if (slot < 0) throw new Error(`no ${name} in window: ${h.topSlots(window).map(s => s?.name ?? '-').join(',')}`)
  return slot
}

/** Shift-clicks every stack of the given items from the player's inventory into the open window. */
async function shiftIn (tb, window, names) {
  for (let i = window.inventoryStart; i < window.slots.length; i++) {
    if (names.includes(window.slots[i]?.name)) await tb.bot.clickWindow(i, 0, 1)
  }
}

/** Places an order through the confirm menu and returns its id. */
async function place (tb, args) {
  await h.opens(tb, () => h.say(tb, `/orders create ${args}`))
  const since = tb.mark()
  await tb.bot.clickWindow(11, 0, 0)
  const line = await tb.expect(/Order #(\d+) placed|open orders|You need/, { since })
  const id = /Order #(\d+) placed/.exec(line)?.[1]
  if (!id) throw new Error(`order not placed: ${line}`)
  return Number(id)
}

/** Opens the delivery menu for an item and puts the listed stacks in; leaves it open. */
async function openDelivery (tb, item, stacks = [item]) {
  const board = await h.opens(tb, () => h.say(tb, `/orders search ${item}`))
  const menu = await h.opens(tb, () => tb.bot.clickWindow(slotOf(board, item), 0, 0))
  await shiftIn(tb, menu, stacks)
  await sleep(300)
  return menu
}

module.exports = {
  name: 'tuskorders-extra',
  logName: 'TuskOrders',
  packagePrefix: 'io.github.tuskworks.orders',
  plugins: () => [
    h.newestJar(ORDERS, /^TuskOrders-.*\.jar$/, 'run ./gradlew :tuskorders:build'),
    h.newestJar(ECONOMY, /^TestEconomy.*\.jar$/, 'run ./gradlew :e2e-test-economy:build')
  ],

  async run ({ server, join, step }) {
    const dataDir = path.join(server.dir, 'plugins', 'TuskOrders')
    const setConfig = async (from, to) => {
      h.setYamlLine(path.join(dataDir, 'config.yml'), from, to)
      await h.consoleRun(server, 'orders reload', /Reloaded config and messages|已重新載入/)
    }

    let buyer = await join('Buyer')
    let seller = await join('Seller')
    const other = await join('Other')
    server.command('gamemode survival @a')
    await h.consoleRun(server, 'testeco set Buyer 100000', /balance Buyer set to/)
    await h.consoleRun(server, 'testeco set Seller 0', /balance Seller set to/)
    await h.consoleRun(server, 'testeco set Other 0', /balance Other set to/)
    let expected = 100000

    await step('input limits, blacklist wildcards and the hand keyword', async () => {
      await h.run(buyer, '/orders create diamond 100001 1', /Amount must be between 1 and 100000/)
      await h.run(buyer, '/orders create diamond 10 0.001', /isn't a valid price|minimum price/)
      await h.run(buyer, '/orders create zombie_spawn_egg 1 5', /aren't allowed/)
      await h.run(buyer, '/orders create spawner 1 5', /aren't allowed/)
      h.emptyHand(buyer)
      await h.run(buyer, '/orders create hand 1 5', /'hand' isn't an item/)
      await give(server, buyer, 'cobblestone', 1)
      await buyer.bot.equip(buyer.bot.inventory.items().find(i => i.name === 'cobblestone'), 'hand')
      const menu = await h.opens(buyer, () => h.say(buyer, '/orders create hand 5 1'))
      if (h.topSlots(menu)[13]?.name !== 'cobblestone') throw new Error(`summary shows ${h.topSlots(menu)[13]?.name}`)
      const since = buyer.mark()
      await buyer.bot.clickWindow(15, 0, 0)
      await buyer.expect(/Cancelled/, { since })
      await expectBalance(server, 'Buyer', expected)
    })

    await step('an order the buyer cannot afford is refused and nothing is charged', async () => {
      await h.opens(other, () => h.say(other, '/orders create diamond 10 100'))
      const since = other.mark()
      await other.bot.clickWindow(11, 0, 0)
      await other.expect(/You need \$1,000(\.00)? to place this order/, { since })
      await expectBalance(server, 'Other', 0)
    })

    let diamondOrder
    await step('buyers cannot deliver to their own order', async () => {
      diamondOrder = await place(buyer, 'diamond 10 10')
      expected -= 100
      await expectBalance(server, 'Buyer', expected)
      const board = await h.opens(buyer, () => h.say(buyer, '/orders'))
      const since = buyer.mark()
      let opened = false
      const onOpen = () => { opened = true }
      buyer.bot.on('windowOpen', onOpen)
      await buyer.bot.clickWindow(slotOf(board, 'diamond'), 0, 0)
      await buyer.expect(/You can't deliver to your own order/, { since })
      await sleep(500)
      buyer.bot.off('windowOpen', onOpen)
      if (opened) throw new Error('a delivery menu opened for the buyer\'s own order')
      buyer.bot.closeWindow(board)
    })

    const fillers = []
    await step('at most 5 open orders per player by default', async () => {
      for (const item of ['stone', 'dirt', 'sand', 'gravel']) fillers.push(await place(buyer, `${item} 1 1`))
      expected -= 4
      await h.opens(buyer, () => h.say(buyer, '/orders create oak_log 1 1'))
      const since = buyer.mark()
      await buyer.bot.clickWindow(11, 0, 0)
      await buyer.expect(/You already have 5 open orders/, { since })
      await expectBalance(server, 'Buyer', expected)
    })

    await step('admins can cancel any order from the console; the owner is refunded', async () => {
      await h.consoleRun(server, 'orders cancel 999', /There is no order #999/)
      await h.consoleRun(server, `orders cancel ${diamondOrder}`, new RegExp(`Order #${diamondOrder} cancelled and \\$100(\\.00)? refunded`))
      expected += 100
      await expectBalance(server, 'Buyer', expected)
      await h.consoleRun(server, `orders cancel ${diamondOrder}`, /There is no order|already closed/)
    })

    await step('creation fee and delivery tax (10% / 5%)', async () => {
      await setConfig('creation-fee-percent: 0', 'creation-fee-percent: 10')
      await setConfig('delivery-tax-percent: 0', 'delivery-tax-percent: 5')
      const id = await place(buyer, 'iron_ingot 10 2')
      expected -= 22
      await expectBalance(server, 'Buyer', expected)
      await buyer.expect(new RegExp(`Order #${id} placed: 10x Iron Ingot at \\$2(\\.00)? each\\. \\$22(\\.00)? is held`))
      await give(server, seller, 'iron_ingot', 10)
      const menu = await openDelivery(seller, 'iron_ingot')
      const since = seller.mark()
      const b = buyer.mark()
      seller.bot.closeWindow(menu)
      await seller.expect(/Delivered 10x Iron Ingot to Buyer and earned \$19(\.00)?/, { since })
      await buyer.expect(/order for 10x Iron Ingot is complete/, { since: b })
      await expectBalance(server, 'Seller', 19)
      await setConfig('creation-fee-percent: 10', 'creation-fee-percent: 0')
      await setConfig('delivery-tax-percent: 5', 'delivery-tax-percent: 0')
    })

    await step('damaged items are rejected and handed back', async () => {
      for (const id of fillers) await h.consoleRun(server, `orders cancel ${id}`, new RegExp(`Order #${id} cancelled and`))
      expected += fillers.length
      await place(buyer, 'diamond_pickaxe 2 50')
      expected -= 100
      await give(server, seller, 'diamond_pickaxe', 1)
      await give(server, seller, 'diamond_pickaxe[minecraft:damage=100]', 1, 'diamond_pickaxe')
      const menu = await openDelivery(seller, 'diamond_pickaxe')
      const since = seller.mark()
      seller.bot.closeWindow(menu)
      await seller.expect(/Delivered 1x Diamond Pickaxe to Buyer and earned \$50/, { since })
      await seller.expect(/1 item\(s\) didn't match/, { since })
      await h.until(() => h.count(seller, 'diamond_pickaxe') === 1,
        () => `seller has ${h.count(seller, 'diamond_pickaxe')} pickaxes, expected the damaged one back`)
      await expectBalance(server, 'Seller', 19 + 50)
      await expectBalance(server, 'Buyer', expected)
    })

    await step('logging out with items in the delivery menu hands them in', async () => {
      await place(buyer, 'gold_ingot 20 5')
      expected -= 100
      await give(server, seller, 'gold_ingot', 20)
      await openDelivery(seller, 'gold_ingot')
      const b = buyer.mark()
      seller.quit()
      await buyer.expect(/order for 20x Gold Ingot is complete/, { since: b, timeout: 5000 })
      seller = await join('Seller')
      await sleep(1000)
      if (h.count(seller, 'gold_ingot') !== 0) throw new Error(`seller still has ${h.count(seller, 'gold_ingot')} gold`)
      await expectBalance(server, 'Seller', 19 + 50 + 100)
    })

    await step('a server stop with items in the delivery menu loses nothing; expired orders are refunded', async () => {
      const emeraldOrder = await place(buyer, 'emerald 30 3')
      const coalOrder = await place(buyer, 'coal 10 1')
      await give(server, seller, 'emerald', 30)
      await openDelivery(seller, 'emerald')
      // Stop while the seller still has the menu open, then expire the coal order while the server is down
      await server.stop()
      const coalFile = path.join(dataDir, 'orders', `${coalOrder}.json`)
      const coal = JSON.parse(fs.readFileSync(coalFile, 'utf8'))
      coal.expiresAt = 1000
      fs.writeFileSync(coalFile, JSON.stringify(coal, null, 2))
      const emerald = JSON.parse(fs.readFileSync(path.join(dataDir, 'orders', `${emeraldOrder}.json`), 'utf8'))
      await server.start()
      buyer = await join('Buyer')
      seller = await join('Seller')
      // The in-memory test economy starts empty again
      await h.consoleRun(server, 'testeco set Buyer 0', /balance Buyer set to/)
      await sleep(1000)
      const kept = h.count(seller, 'emerald')
      if (emerald.delivered + kept !== 30) {
        throw new Error(`${30 - emerald.delivered - kept} emeralds vanished (delivered ${emerald.delivered}, returned ${kept})`)
      }
      // The expiry sweep runs once a minute
      await buyer.expect(new RegExp(`order #${coalOrder} for Coal expired\\. \\$10(\\.00)? was refunded`), { timeout: 75_000 })
      await expectBalance(server, 'Buyer', 10)
    })

    await step('collecting with a full inventory keeps the rest in the order', async () => {
      // Exactly enough to fill every free slot, so nothing spills onto the ground
      server.command(`give Buyer minecraft:dirt ${buyer.bot.inventory.emptySlotCount() * 64}`)
      await h.until(() => buyer.bot.inventory.emptySlotCount() === 0, 'inventory never filled up')
      let mine = await h.opens(buyer, () => h.say(buyer, '/orders mine'))
      let since = buyer.mark()
      await buyer.bot.clickWindow(slotOf(mine, 'emerald'), 0, 0)
      await buyer.expect(/Your inventory is full/, { since })
      buyer.bot.closeWindow(mine)
      server.command('clear Buyer minecraft:dirt')
      await h.until(() => h.count(buyer, 'dirt') === 0, 'dirt was not cleared')
      mine = await h.opens(buyer, () => h.say(buyer, '/orders mine'))
      since = buyer.mark()
      await buyer.bot.clickWindow(slotOf(mine, 'emerald'), 0, 0)
      await buyer.expect(/Collected \d+x Emerald/, { since })
      buyer.bot.closeWindow(mine)
    })

    await step('Traditional Chinese messages (language: zh_TW)', async () => {
      await setConfig('language: en', 'language: zh_TW')
      await h.run(buyer, '/orders nope', /未知的子指令/)
      await setConfig('language: zh_TW', 'language: en')
      await h.run(buyer, '/orders nope', /Unknown subcommand/)
    })

    for (const tb of [buyer, seller, other]) tb.quit()
  }
}
