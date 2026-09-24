'use strict'
// Thin wrapper around mineflayer bots with chat expectations.

const mineflayer = require('mineflayer')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

class TestBot {
  /** `name` is needed until the bot has logged in; bot.username is only set by then. */
  constructor (bot, name = bot.username) {
    this.bot = bot
    this.name = name
    this.messages = []
    // Servers that restyle chat (like our tag renderer) send the result as unsigned
    // content, which is what a real client displays — so prefer it when present.
    bot.on('message', msg => {
      const text = (msg.unsigned ?? msg).toString()
      this.messages.push(text)
      if (process.env.E2E_VERBOSE) console.log(`  [${this.name}] ${text}`)
    })
  }

  static async join ({ host = '127.0.0.1', port, username, version }) {
    const bot = mineflayer.createBot({ host, port, username, version, auth: 'offline', hideErrors: false })
    // Wrap before spawning so messages sent while joining (PlayerJoinEvent) are kept too.
    const testBot = new TestBot(bot, username)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${username} did not spawn in time`)), 60_000)
      bot.once('spawn', () => { clearTimeout(timer); resolve() })
      bot.once('kicked', reason => { clearTimeout(timer); reject(new Error(`${username} kicked: ${JSON.stringify(reason)}`)) })
      bot.once('error', err => { clearTimeout(timer); reject(err) })
    })
    return testBot
  }

  /** Index to pass as `since` so later expectations ignore older messages. */
  mark () {
    return this.messages.length
  }

  say (text) {
    this.bot.chat(text)
  }

  async expect (re, { since = 0, timeout = 5000 } = {}) {
    const deadline = Date.now() + timeout
    while (Date.now() < deadline) {
      const hit = this.messages.slice(since).find(m => re.test(m))
      if (hit) return hit
      await sleep(50)
    }
    const recent = this.messages.slice(since).slice(-8).map(m => `    | ${m}`).join('\n')
    throw new Error(`${this.name} never saw ${re}\n  last messages:\n${recent || '    | (none)'}`)
  }

  async expectNone (re, { since = 0, wait = 1500 } = {}) {
    await sleep(wait)
    const hit = this.messages.slice(since).find(m => re.test(m))
    if (hit) throw new Error(`${this.name} unexpectedly saw: ${hit}`)
  }

  /** Sends a command and waits for a reply matching `re`. */
  async run (command, re, opts = {}) {
    const since = this.mark()
    this.say(command)
    return this.expect(re, { since, ...opts })
  }

  entityOf (other) {
    return this.bot.players[other.name]?.entity
  }

  quit () {
    this.bot.quit()
  }
}

module.exports = { TestBot, sleep }
