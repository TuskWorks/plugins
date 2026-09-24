'use strict'
// Helpers shared by scenarios: throttled commands, windows, inventory counts, config edits.

const fs = require('node:fs')
const path = require('node:path')
const minecraftData = require('minecraft-data')
const { sleep } = require('./bots')

/** Newest jar in `dir` whose name matches `pattern`. */
function newestJar (dir, pattern, hint = 'build it first') {
  const jars = fs.existsSync(dir) ? fs.readdirSync(dir).filter(f => pattern.test(f)) : []
  if (jars.length === 0) throw new Error(`${pattern} not found in ${dir} — ${hint}`)
  return jars.map(f => path.join(dir, f)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0]
}

/** Like newestJar, but null instead of an error. */
function optionalJar (dir, pattern) {
  try {
    return newestJar(dir, pattern)
  } catch {
    return null
  }
}

/** Ends the current step as skipped rather than failed. */
function skip (reason) {
  const e = new Error(reason)
  e.skip = true
  throw e
}

// Vanilla kicks non-ops whose chat + command score passes 200 (+20 per message, -1 per tick).
// Real players never type this fast, so bots pace themselves; set `tb.op = true` to skip it.
const spam = new Map()
async function say (tb, text) {
  if (!tb.op) {
    const now = Date.now()
    const prev = spam.get(tb.name) ?? { score: 0, at: now }
    let score = Math.max(0, prev.score - (now - prev.at) / 50)
    if (score + 20 > 140) {
      await sleep((score + 20 - 140) * 50)
      score = 120
    }
    spam.set(tb.name, { score: score + 20, at: Date.now() })
  }
  tb.say(text)
}

/** Sends a command and resolves with the first reply matching `re`. */
async function run (tb, text, re, opts = {}) {
  const since = tb.mark()
  await say(tb, text)
  return tb.expect(re, { since, ...opts })
}

/** Runs a console command and resolves with the first log line matching `re`. */
async function consoleRun (server, cmd, re, timeout = 5000) {
  const since = server.lines.length
  server.command(cmd)
  return server.waitFor(re, timeout, since)
}

async function until (fn, message, timeout = 5000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await fn()) return
    await sleep(50)
  }
  throw new Error(typeof message === 'function' ? message() : message)
}

/** Items in the bot's inventory, optionally only one item type. */
function count (tb, name) {
  return tb.bot.inventory.items().filter(i => !name || i.name === name).reduce((n, i) => n + i.count, 0)
}

/** Resolves with the next window the bot opens after `action` runs. */
async function opens (tb, action, timeout = 5000) {
  const opened = new Promise((resolve, reject) => {
    const onOpen = window => { clearTimeout(timer); resolve(window) }
    const timer = setTimeout(() => { tb.bot.off('windowOpen', onOpen); reject(new Error(`${tb.name}: no window opened`)) }, timeout)
    tb.bot.once('windowOpen', onOpen)
  })
  await action()
  return opened
}

function windowClosed (tb, timeout = 10_000) {
  return new Promise((resolve, reject) => {
    const onClose = () => { clearTimeout(timer); resolve() }
    const timer = setTimeout(() => { tb.bot.off('windowClose', onClose); reject(new Error(`${tb.name}: window never closed`)) }, timeout)
    tb.bot.once('windowClose', onClose)
  })
}

function topSlots (window) {
  return window.slots.slice(0, window.inventoryStart)
}

function titleOf (window) {
  return JSON.stringify(window.title)
}

/** Selects an empty hotbar slot: mineflayer's dig maths chokes on 1.21+ item components. */
function emptyHand (tb) {
  const slot = [...Array(9).keys()].find(i => !tb.bot.inventory.slots[36 + i])
  if (slot === undefined) throw new Error(`${tb.name}: hotbar is full`)
  tb.bot.setQuickBarSlot(slot)
}

/** Sneaking that servers see: mineflayer sends player_input from 1.21.3, but until 1.21.6 servers read entity_action. */
function sneak (tb, state) {
  tb.bot.setControlState('sneak', state)
  const actions = minecraftData(tb.bot.version).protocol.play.toServer.types.packet_entity_action[1][1].type[1].mappings
  if (tb.bot.supportFeature('newPlayerInputPacket') && Object.values(actions).includes('start_sneaking')) {
    tb.bot._client.write('entity_action', {
      entityId: tb.bot.entity.id,
      actionId: state ? 'start_sneaking' : 'stop_sneaking',
      jumpBoost: 0
    })
  }
}

/** Entities of a type (e.g. text_display) within `radius` of a block's center. */
function entitiesNear (tb, type, pos, radius = 2) {
  const center = pos.offset(0.5, 0.5, 0.5)
  return Object.values(tb.bot.entities).filter(e => e.name === type && e.position.distanceTo(center) <= radius)
}

/** Replaces one whole `key: value` line in a YAML file; comments that mention the key are left alone. */
function setYamlLine (file, from, to) {
  const text = fs.readFileSync(file, 'utf8')
  const escaped = from.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const line = new RegExp(`^([ \\t]*)${escaped}([ \\t]*\\r?)$`, 'm')
  if (!line.test(text)) throw new Error(`${path.basename(file)} has no line "${from}"`)
  fs.writeFileSync(file, text.replace(line, (_, indent, end) => `${indent}${to}${end}`))
}

module.exports = {
  sleep,
  newestJar,
  optionalJar,
  skip,
  say,
  run,
  consoleRun,
  until,
  count,
  opens,
  windowClosed,
  topSlots,
  titleOf,
  emptyHand,
  sneak,
  entitiesNear,
  setYamlLine
}
