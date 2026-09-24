'use strict'
// End-to-end scenario for TuskOrders: a buyer and a seller drive the real menus through
// placing, delivering, collecting and cancelling orders, with money checked at every step
// against the in-memory test economy (e2e/fixtures/test-economy).

const fs = require('node:fs')
const path = require('node:path')
const { sleep } = require('../lib/bots')

const ROOT = path.join(__dirname, '..', '..')

function newestJar (dir, pattern, hint) {
  const jars = fs.existsSync(dir) ? fs.readdirSync(dir).filter(f => pattern.test(f)) : []
  if (jars.length === 0) throw new Error(`${pattern} not found in ${dir} — run ${hint} first`)
  return jars.map(f => path.join(dir, f)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0]
}

// ---- helpers ---------------------------------------------------------------------------

// Vanilla kicks players whose chat + commands score passes 200 (+20 per message, -1 per tick).
// Real players never type this fast, so the bots pace themselves to stay well under it.
const spam = new Map()

async function say (tb, text) {
  const now = Date.now()
  const prev = spam.get(tb.name) ?? { score: 0, at: now }
  let score = Math.max(0, prev.score - (now - prev.at) / 50)
  if (score + 20 > 140) {
    await sleep((score + 20 - 140) * 50)
    score = 120
  }
  spam.set(tb.name, { score: score + 20, at: Date.now() })
  tb.say(text)
}

async function run (tb, text, re, opts = {}) {
  const since = tb.mark()
  await say(tb, text)
  return tb.expect(re, { since, ...opts })
}

async function waitUntil (what, fn, timeout = 5000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) return
    await sleep(50)
  }
  throw new Error(`Timed out waiting for ${what}`)
}

function count (tb, name) {
  return tb.bot.inventory.items().filter(i => i.name === name).reduce((sum, i) => sum + i.count, 0)
}

async function give (server, tb, item, amount) {
  const before = count(tb, item)
  server.command(`give ${tb.name} minecraft:${item} ${amount}`)
  await waitUntil(`${tb.name} to receive ${amount} ${item}`, () => count(tb, item) >= before + amount)
}

async function balance (server, name) {
  const since = server.lines.length
  server.command(`testeco balance ${name}`)
  const line = await server.waitFor(new RegExp(`balance ${name} = ([\\d.]+)`), 5000, since)
  return Number(/= ([\d.]+)/.exec(line)[1])
}

async function expectBalance (server, name, expected) {
  const actual = await balance(server, name)
  if (Math.abs(actual - expected) > 0.001) throw new Error(`${name} balance ${actual}, expected ${expected}`)
}

/** Runs a command (or clicks) and resolves with the window it opens. */
async function opens (tb, action) {
  const opened = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${tb.name}: no window opened`)), 5000)
    tb.bot.once('windowOpen', window => { clearTimeout(timer); resolve(window) })
  })
  await action()
  return opened
}

function topSlots (window) {
  return window.slots.slice(0, window.inventoryStart)
}

function slotOf (window, name) {
  const slot = topSlots(window).findIndex(s => s?.name === name)
  if (slot < 0) throw new Error(`no ${name} in window: ${topSlots(window).map(s => s?.name ?? '-').join(',')}`)
  return slot
}

/** Shift-clicks every stack of the given items from the player's inventory into the open window. */
async function shiftIn (tb, window, names) {
  for (let i = window.inventoryStart; i < window.slots.length; i++) {
    if (names.includes(window.slots[i]?.name)) await tb.bot.clickWindow(i, 0, 1)
  }
}

async function deliver (tb, itemName, stacks = [itemName]) {
  const board = await opens(tb, () => say(tb, '/orders'))
  const deliverMenu = await opens(tb, () => tb.bot.clickWindow(slotOf(board, itemName), 0, 0))
  await shiftIn(tb, deliverMenu, stacks)
  const since = tb.mark()
  tb.bot.closeWindow(deliverMenu)
  return since
}

async function confirm (tb, window) {
  if (!topSlots(window)[11]?.name?.includes('lime')) throw new Error('confirm button missing')
  await tb.bot.clickWindow(11, 0, 0)
}

// ---- scenario --------------------------------------------------------------------------

module.exports = {
  name: 'tuskorders',
  logName: 'TuskOrders',
  packagePrefix: 'io.github.tuskworks.orders',
  plugins: () => [
    newestJar(path.join(ROOT, 'tuskorders', 'build', 'libs'), /^TuskOrders-.*\.jar$/, './gradlew :tuskorders:build'),
    newestJar(path.join(ROOT, 'e2e', 'fixtures', 'test-economy', 'build', 'libs'), /^TestEconomy.*\.jar$/,
      './gradlew :e2e-test-economy:build')
  ],

  /** Console-only checks for server versions the bots can't join yet. */
  async smoke ({ server, step }) {
    const expectConsole = async (cmd, re) => {
      const since = server.lines.length
      server.command(cmd)
      await server.waitFor(re, 5000, since)
    }
    await step('plugin enables and finds the Vault economy', async () => {
      await server.waitFor(/\[TuskOrders\] Loaded 0 order\(s\)/, 1000, 0)
      await server.waitFor(/\[TuskOrders\] Using economy/, 5000, 0)
    })
    await step('console commands', async () => {
      await expectConsole('orders help', /orders cancel \[id\]/)
      await expectConsole('orders reload', /Reloaded config and messages/)
      await expectConsole('orders cancel 1', /There is no order #1/)
      await expectConsole('orders mine', /Only players can do that/)
      await expectConsole('orders nope', /Unknown subcommand nope/)
    })
  },

  async run ({ server, join, step }) {
    let buyer = await join('Buyer')
    let seller = await join('Seller')
    server.command('gamemode survival @a')
    server.command('testeco set Buyer 100000')
    server.command('testeco set Seller 0')
    await server.waitFor(/balance Seller set to/)

    await step('help and tab completion', async () => {
      await run(buyer, '/orders help', /browse open orders/)
      const completions = await buyer.bot.tabComplete('/orders create diam')
      const names = completions.map(c => (typeof c === 'string' ? c : c.match))
      if (!names.includes('diamond')) throw new Error(`tab completion gave ${JSON.stringify(names)}`)
    })

    await step('create rejects bad input before charging', async () => {
      await run(buyer, '/orders create diamond 0 5', /isn't a valid amount/)
      await run(buyer, '/orders create bedrock 1 5', /aren't allowed/)
      await run(buyer, '/orders create notanitem 1 5', /isn't an item/)
      await run(buyer, '/orders create diamond 1 5x', /isn't a valid price/)
      await run(buyer, '/orders create diamond 1 2b', /maximum price is \$1,000,000,000/)
      await expectBalance(server, 'Buyer', 100000)
    })

    await step('placing an order holds its full value', async () => {
      const menu = await opens(buyer, () => say(buyer, '/orders create diamond 100 25'))
      if (topSlots(menu)[13]?.name !== 'diamond') throw new Error('summary item missing')
      const since = buyer.mark()
      await confirm(buyer, menu)
      await buyer.expect(/Order #1 placed: 100x Diamond at \$25 each\. \$2,500 is held/, { since })
      await expectBalance(server, 'Buyer', 97500)
    })

    await step('delivering pays per item and returns non-matching items', async () => {
      await give(server, seller, 'diamond', 64)
      await give(server, seller, 'dirt', 16)
      const buyerSince = buyer.mark()
      const since = await deliver(seller, 'diamond', ['diamond', 'dirt'])
      await seller.expect(/Delivered 64x Diamond to Buyer and earned \$1,600/, { since })
      await seller.expect(/16 item\(s\) didn't match/, { since })
      await buyer.expect(/Seller delivered 64x Diamond to your order #1/, { since: buyerSince })
      await waitUntil('dirt back', () => count(seller, 'dirt') === 16)
      if (count(seller, 'diamond') !== 0) throw new Error('diamonds were not taken')
      await expectBalance(server, 'Seller', 1600)
    })

    await step('over-delivery only takes what the order still needs', async () => {
      await give(server, seller, 'diamond', 64)
      const buyerSince = buyer.mark()
      const since = await deliver(seller, 'diamond')
      await seller.expect(/Delivered 36x Diamond to Buyer and earned \$900/, { since })
      await buyer.expect(/order for 100x Diamond is complete/, { since: buyerSince })
      await waitUntil('28 diamonds back', () => count(seller, 'diamond') === 28)
      await expectBalance(server, 'Seller', 2500)
      const board = await opens(seller, () => say(seller, '/orders'))
      if (topSlots(board).some(s => s?.name === 'diamond')) throw new Error('completed order still listed')
      seller.bot.closeWindow(board)
    })

    await step('buyer collects delivered items', async () => {
      const mine = await opens(buyer, () => say(buyer, '/orders mine'))
      const since = buyer.mark()
      await buyer.bot.clickWindow(slotOf(mine, 'diamond'), 0, 0)
      await buyer.expect(/Collected 100x Diamond/, { since })
      await waitUntil('buyer has 100 diamonds', () => count(buyer, 'diamond') === 100)
      buyer.bot.closeWindow(mine)
      // Completed and fully collected orders disappear
      const again = await opens(buyer, () => say(buyer, '/orders mine'))
      if (topSlots(again).some(s => s?.name === 'diamond')) throw new Error('collected order still shown')
      buyer.bot.closeWindow(again)
    })

    await step('cancelling refunds only the unfilled part', async () => {
      const menu = await opens(buyer, () => say(buyer, '/orders create oak_log 64 10'))
      let since = buyer.mark()
      await confirm(buyer, menu)
      await buyer.expect(/Order #2 placed/, { since })
      await give(server, seller, 'oak_log', 10)
      since = await deliver(seller, 'oak_log')
      await seller.expect(/Delivered 10x Oak Log/, { since })

      const mine = await opens(buyer, () => say(buyer, '/orders mine'))
      const confirmMenu = await opens(buyer, () => buyer.bot.clickWindow(slotOf(mine, 'oak_log'), 1, 1))
      since = buyer.mark()
      // Confirming returns to the order list by itself
      const after = await opens(buyer, () => confirm(buyer, confirmMenu))
      await buyer.expect(/Order #2 cancelled and \$540 refunded/, { since })
      await expectBalance(server, 'Buyer', 97500 - 640 + 540)
      await expectBalance(server, 'Seller', 2600)

      since = buyer.mark()
      await buyer.bot.clickWindow(slotOf(after, 'oak_log'), 0, 0)
      await buyer.expect(/Collected 10x Oak Log/, { since })
      buyer.bot.closeWindow(after)
    })

    await step('a failed payout gives the items back and keeps the order open', async () => {
      const menu = await opens(buyer, () => say(buyer, '/orders create cobblestone 10 1'))
      let since = buyer.mark()
      await confirm(buyer, menu)
      await buyer.expect(/Order #3 placed/, { since })
      await give(server, seller, 'cobblestone', 10)

      server.command('testeco fail-deposits true')
      await server.waitFor(/fail-deposits true/)
      since = await deliver(seller, 'cobblestone')
      await seller.expect(/payment failed, so your items were returned/, { since })
      await waitUntil('cobblestone back', () => count(seller, 'cobblestone') === 10)
      await expectBalance(server, 'Seller', 2600)

      server.command('testeco fail-deposits false')
      await server.waitFor(/fail-deposits false/)
      since = await deliver(seller, 'cobblestone')
      await seller.expect(/Delivered 10x Cobblestone to Buyer and earned \$10/, { since })
      await expectBalance(server, 'Seller', 2610)
    })

    await step('chat prompts create an order without leaking into public chat', async () => {
      const sellerSince = seller.mark()
      await run(buyer, '/orders create', /Which item do you want to buy/)
      await run(buyer, 'emerald', /How many Emerald do you want/)
      await run(buyer, '32', /How much will you pay for each Emerald/)
      const menu = await opens(buyer, () => say(buyer, '2.5'))
      const since = buyer.mark()
      await confirm(buyer, menu)
      await buyer.expect(/Order #4 placed: 32x Emerald at \$2\.50 each/, { since })
      await seller.expectNone(/<Buyer>/, { since: sellerSince, wait: 500 })
    })

    await step('search narrows the board', async () => {
      const board = await opens(seller, () => say(seller, '/orders search emerald'))
      const shown = topSlots(board).slice(0, 45).filter(Boolean).map(s => s.name)
      if (shown.length !== 1 || shown[0] !== 'emerald') throw new Error(`search showed ${shown.join(',')}`)
      seller.bot.closeWindow(board)
    })

    await step('orders and uncollected items survive a restart', async () => {
      buyer.quit()
      seller.quit()
      await server.restart()
      seller = await join('Seller')
      const board = await opens(seller, () => say(seller, '/orders'))
      slotOf(board, 'emerald')
      seller.bot.closeWindow(board)
      buyer = await join('Buyer')
      await buyer.expect(/10 item\(s\) waiting in your orders/, { timeout: 8000 })
    })
  }
}
