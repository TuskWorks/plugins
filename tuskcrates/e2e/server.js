'use strict'
// Downloads, configures and drives a throwaway Paper/Folia server.

const crypto = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawn } = require('node:child_process')

const UA = { 'User-Agent': 'TuskWorks-e2e (github.com/TuskWorks/plugins)' }
const CACHE = path.join(__dirname, '.servers')
const WORK = path.join(__dirname, '.work')
const ANSI = /\u001b\[[0-9;]*[A-Za-z]/g

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function getJson (url) {
  const res = await fetch(url, { headers: UA })
  if (!res.ok) throw new Error(`GET ${url} -> ${res.status}`)
  return res.json()
}

/** Latest build of paper/folia for a Minecraft version, verified against its sha256. */
async function serverJar (project, mc) {
  const base = `https://fill.papermc.io/v3/projects/${project}/versions/${mc}`
  const info = await getJson(base)
  const build = await getJson(`${base}/builds/latest`)
  const file = build.downloads['server:default']
  const dest = path.join(CACHE, file.name)
  const sha256 = f => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex')
  if (!fs.existsSync(dest) || sha256(dest) !== file.checksums.sha256) {
    fs.mkdirSync(CACHE, { recursive: true })
    console.log(`  downloading ${file.name}`)
    const res = await fetch(file.url, { headers: UA })
    if (!res.ok) throw new Error(`GET ${file.url} -> ${res.status}`)
    fs.writeFileSync(dest, Buffer.from(await res.arrayBuffer()))
    if (sha256(dest) !== file.checksums.sha256) throw new Error(`checksum mismatch for ${file.name}`)
  }
  return { jar: dest, build: build.id, java: info.version.java.version.minimum }
}

/**
 * A java binary for the given major version: E2E_JAVA_<n>, then Temurin installs,
 * then JDKs provisioned by the Gradle toolchain resolver.
 */
function javaFor (major) {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java'
  const env = process.env[`E2E_JAVA_${major}`]
  if (env) return path.join(env, 'bin', exe)
  const roots = [
    ['C:\\Program Files\\Eclipse Adoptium', name => name.startsWith(`jdk-${major}.`) || name === `jdk-${major}`],
    [path.join(os.homedir(), '.gradle', 'jdks'), name => new RegExp(`[-_]${major}[-_.]`).test(name) && !name.endsWith('.zip') && !name.endsWith('.lock')]
  ]
  for (const [root, match] of roots) {
    if (!fs.existsSync(root)) continue
    for (const name of fs.readdirSync(root).filter(match).sort().reverse()) {
      const bin = path.join(root, name, 'bin', exe)
      if (fs.existsSync(bin)) return bin
    }
  }
  throw new Error(`No Java ${major} found. Run ./gradlew :tuskcrates:compileJava -PpaperApi=26.2.build.128-stable once, or set E2E_JAVA_${major}.`)
}

class Server {
  constructor ({ project, mc, pluginJar, port }) {
    this.project = project
    this.mc = mc
    this.pluginJar = pluginJar
    this.port = port
    this.dir = path.join(WORK, `${project}-${mc}`)
    this.lines = []
  }

  async start () {
    const { jar, build, java } = await serverJar(this.project, this.mc)
    console.log(`  ${this.project} ${this.mc} build ${build} (Java ${java})`)
    fs.rmSync(this.dir, { recursive: true, force: true })
    fs.mkdirSync(path.join(this.dir, 'plugins'), { recursive: true })
    fs.copyFileSync(this.pluginJar, path.join(this.dir, 'plugins', path.basename(this.pluginJar)))
    fs.writeFileSync(path.join(this.dir, 'eula.txt'), 'eula=true\n')
    fs.writeFileSync(path.join(this.dir, 'server.properties'), [
      'online-mode=false',
      'enforce-secure-profile=false',
      'white-list=false',
      `server-port=${this.port}`,
      'level-type=minecraft\\:flat',
      'generate-structures=false',
      'spawn-protection=0',
      'difficulty=peaceful',
      'spawn-monsters=false',
      'view-distance=4',
      'simulation-distance=4',
      'motd=TuskCrates e2e'
    ].join('\n') + '\n')

    this.proc = spawn(javaFor(java), ['-Xms1G', '-Xmx2G', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', jar, '--nogui'], { cwd: this.dir })
    let buffer = ''
    const onData = chunk => {
      buffer += chunk.toString('utf8')
      let nl
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl).replace(ANSI, '').replace(/\r$/, '')
        buffer = buffer.slice(nl + 1)
        this.lines.push(line)
        if (process.env.E2E_VERBOSE) console.log(`  | ${line}`)
      }
    }
    this.proc.stdout.on('data', onData)
    this.proc.stderr.on('data', onData)
    this.exited = new Promise(resolve => this.proc.once('exit', code => resolve(code)))
    await this.waitFor(/Done \([\d.,]+s\)!/, { timeout: 300_000 })
  }

  mark () {
    return this.lines.length
  }

  async waitFor (re, { since = 0, timeout = 10_000 } = {}) {
    const deadline = Date.now() + timeout
    while (Date.now() < deadline) {
      const hit = this.lines.slice(since).find(line => re.test(line))
      if (hit) return hit
      if (this.proc.exitCode !== null) throw new Error(`server exited while waiting for ${re}`)
      await sleep(50)
    }
    const tail = this.lines.slice(since).slice(-10).map(l => `    | ${l}`).join('\n')
    throw new Error(`server never logged ${re}\n  last lines:\n${tail || '    | (none)'}`)
  }

  /** Runs a console command and waits for a log line matching `re`. */
  async run (command, re, opts = {}) {
    const since = this.mark()
    this.proc.stdin.write(command + '\n')
    return re ? this.waitFor(re, { since, ...opts }) : undefined
  }

  async stop () {
    if (!this.proc || this.proc.exitCode !== null) return
    this.proc.stdin.write('stop\n')
    const code = await Promise.race([this.exited, sleep(60_000).then(() => 'timeout')])
    if (code === 'timeout') {
      this.proc.kill()
      throw new Error('server did not stop within 60s')
    }
  }

  /** Log lines that point at a problem in our plugin. */
  problems () {
    const out = []
    this.lines.forEach((line, i) => {
      const ours = /TuskCrates|io\.github\.tuskworks/.test(line)
      const bad = /\b(WARN|ERROR|SEVERE)\b|Exception|Error:/.test(line)
      if (ours && bad) out.push(line)
      // Stack traces: an exception line followed by one of our frames.
      if (/at io\.github\.tuskworks/.test(line) && !out.includes(this.lines[i - 1])) out.push(line)
    })
    return out
  }
}

module.exports = { Server, sleep }
