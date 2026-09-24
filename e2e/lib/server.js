'use strict'
// Runs a throwaway headless Paper server and exposes its console.

const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')

// eslint-disable-next-line no-control-regex
const ANSI = /\x1b\[[0-9;]*[A-Za-z]/g

const PROPERTIES = {
  'online-mode': 'false',
  'white-list': 'false', // 26.x servers default to an enabled whitelist
  'enforce-secure-profile': 'false',
  'level-type': 'minecraft\\:flat',
  'generate-structures': 'false',
  'spawn-monsters': 'false',
  'spawn-animals': 'false',
  'spawn-npcs': 'false',
  'spawn-protection': '0',
  difficulty: 'easy',
  pvp: 'true',
  'view-distance': '4',
  'simulation-distance': '4',
  'max-players': '10',
  motd: 'TuskWorks e2e'
}

class PaperServer {
  constructor ({ dir, jar, java, port, plugins }) {
    this.dir = dir
    this.jar = jar
    this.java = java
    this.port = port
    this.plugins = plugins
    this.lines = []
    this.waiters = []
    this.proc = null
  }

  /** Fresh server directory; plugin data is kept across restart() but not across runs. */
  prepare () {
    fs.rmSync(this.dir, { recursive: true, force: true })
    fs.mkdirSync(path.join(this.dir, 'plugins'), { recursive: true })
    fs.writeFileSync(path.join(this.dir, 'eula.txt'), 'eula=true\n')
    const props = { ...PROPERTIES, 'server-port': String(this.port) }
    fs.writeFileSync(path.join(this.dir, 'server.properties'),
      Object.entries(props).map(([k, v]) => `${k}=${v}`).join('\n') + '\n')
    // Bots join back to back; the default 4s connection throttle would kick them.
    fs.writeFileSync(path.join(this.dir, 'bukkit.yml'), 'settings:\n  connection-throttle: -1\n')
    for (const plugin of this.plugins) {
      fs.copyFileSync(plugin, path.join(this.dir, 'plugins', path.basename(plugin)))
    }
  }

  async start (timeoutMs = 240_000) {
    const mark = this.lines.length
    this.proc = spawn(this.java, ['-Xms1G', '-Xmx2G', '-Dpaper.disableChannelLimit=true',
      // Windows consoles aren't UTF-8 by default, which garbles non-English messages
      '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
      '-jar', this.jar, '--nogui'], { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
    // Chunks can end mid-line; keep the tail until its newline arrives.
    const partial = { stdout: '', stderr: '' }
    const onData = stream => chunk => {
      const parts = (partial[stream] + chunk.toString('utf8')).split(/\r?\n/)
      partial[stream] = parts.pop()
      for (const raw of parts) {
        const line = raw.replace(ANSI, '') // console colours split words apart
        if (!line) continue
        this.lines.push(line)
        if (process.env.E2E_VERBOSE) console.log(`  [server] ${line}`)
        for (const w of [...this.waiters]) {
          if (w.re.test(line)) {
            this.waiters.splice(this.waiters.indexOf(w), 1)
            clearTimeout(w.timer)
            w.resolve(line)
          }
        }
      }
    }
    this.proc.stdout.on('data', onData('stdout'))
    this.proc.stderr.on('data', onData('stderr'))
    this.exited = new Promise(resolve => this.proc.on('exit', code => resolve(code)))
    const ready = this.waitFor(/Done \([\d.,]+s\)!/, timeoutMs, mark)
    const died = this.exited.then(code => { throw new Error(`Server exited early with code ${code}`) })
    await Promise.race([ready, died])
    // Superflat slimes kill bots in longer runs. From 1.21.11 spawn-monsters in
    // server.properties no longer applies, so switch natural spawning off by game rule:
    // doMobSpawning up to 1.21.10, spawn_mobs from 1.21.11 (the other name is rejected).
    this.command('gamerule doMobSpawning false')
    this.command('gamerule spawn_mobs false')
  }

  /** Resolves with the first console line (at or after index `since`) matching `re`. */
  waitFor (re, timeoutMs = 10_000, since = this.lines.length) {
    const hit = this.lines.slice(since).find(l => re.test(l))
    if (hit) return Promise.resolve(hit)
    return new Promise((resolve, reject) => {
      const waiter = { re, resolve }
      this.waiters.push(waiter)
      // Cleared on a match; a pending timer would keep node alive after the run.
      waiter.timer = setTimeout(() => {
        const i = this.waiters.indexOf(waiter)
        if (i >= 0) {
          this.waiters.splice(i, 1)
          reject(new Error(`Timed out waiting for server log ${re}`))
        }
      }, timeoutMs)
    })
  }

  command (cmd) {
    this.proc.stdin.write(cmd + '\n')
  }

  async stop () {
    if (!this.proc || this.proc.exitCode !== null) return
    this.command('stop')
    const timeout = new Promise(resolve => setTimeout(() => resolve('timeout'), 60_000))
    if (await Promise.race([this.exited, timeout]) === 'timeout') this.proc.kill('SIGKILL')
  }

  async restart () {
    await this.stop()
    await this.start()
  }

  /**
   * Error/warning entries (with their stack trace) that mention the given plugin.
   * Continuation lines are the ones without the usual "[12:34:56 LEVEL]:" prefix.
   */
  errorsFrom (pluginName, packagePrefix) {
    const out = []
    const prefixed = /^\[\d{2}:\d{2}:\d{2} [A-Z]+\]/
    for (let i = 0; i < this.lines.length; i++) {
      if (!/^\[\d{2}:\d{2}:\d{2} (ERROR|WARN)\]/.test(this.lines[i])) continue
      const entry = [this.lines[i]]
      for (let j = i + 1; j < this.lines.length && !prefixed.test(this.lines[j]); j++) entry.push(this.lines[j])
      const text = entry.join('\n')
      if (text.includes(packagePrefix) || text.includes(`[${pluginName}]`)) out.push(text)
    }
    return out
  }
}

module.exports = { PaperServer }
