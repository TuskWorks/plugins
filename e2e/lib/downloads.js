'use strict'
// Fetches and caches Paper builds, plugin jars and JDKs, verifying checksums.

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const { Readable } = require('node:stream')
const { pipeline } = require('node:stream/promises')

const UA = { 'User-Agent': 'TuskWorks-e2e (github.com/TuskWorks/plugins)' }
const CACHE = path.join(__dirname, '..', '.servers')
const JDKS = path.join(__dirname, '..', '.jdks')

async function getJson (url) {
  const res = await fetch(url, { headers: UA })
  if (!res.ok) throw new Error(`GET ${url} -> ${res.status}`)
  return res.json()
}

async function download (url, dest, algorithm, expected, attempts = 4) {
  if (fs.existsSync(dest) && hashFile(dest, algorithm) === expected) return dest
  fs.mkdirSync(path.dirname(dest), { recursive: true })
  const tmp = `${dest}.part`
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(url, { headers: UA })
      if (!res.ok) throw new Error(`GET ${url} -> ${res.status}`)
      // Stream to disk: JDK archives are ~200MB.
      await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp))
      const actual = hashFile(tmp, algorithm)
      if (actual !== expected) throw new Error(`Checksum mismatch for ${url}: ${actual} != ${expected}`)
      fs.renameSync(tmp, dest)
      return dest
    } catch (e) {
      fs.rmSync(tmp, { force: true })
      if (attempt >= attempts) throw e
      console.log(`  download failed (${e.message}), retrying ${attempt}/${attempts - 1}...`)
      await new Promise(resolve => setTimeout(resolve, 2000 * attempt))
    }
  }
}

function hashFile (file, algorithm) {
  const hash = crypto.createHash(algorithm)
  const fd = fs.openSync(file, 'r')
  try {
    const buf = Buffer.alloc(1 << 20)
    let n
    while ((n = fs.readSync(fd, buf, 0, buf.length, null)) > 0) hash.update(buf.subarray(0, n))
  } finally {
    fs.closeSync(fd)
  }
  return hash.digest('hex')
}

/** Latest Paper (or Folia) build for a Minecraft version, plus the Java version it needs. */
async function paper (mcVersion, project = 'paper') {
  const base = `https://fill.papermc.io/v3/projects/${project}/versions/${mcVersion}`
  const info = await getJson(base)
  const build = await getJson(`${base}/builds/latest`)
  const file = build.downloads['server:default']
  const jar = await download(file.url, path.join(CACHE, file.name), 'sha256', file.checksums.sha256)
  return { jar, build: build.id, channel: build.channel, java: info.version.java.version.minimum }
}

/** Newest release of a Modrinth project that supports the given loader + game version. */
async function modrinth (project, mcVersion, loader = 'paper') {
  const q = new URLSearchParams({ loaders: JSON.stringify([loader]), game_versions: JSON.stringify([mcVersion]) })
  const versions = await getJson(`https://api.modrinth.com/v2/project/${project}/version?${q}`)
  const release = versions.find(v => v.version_type === 'release') ?? versions[0]
  if (!release) throw new Error(`No ${project} build for ${loader} ${mcVersion}`)
  const file = release.files.find(f => f.primary) ?? release.files[0]
  return download(file.url, path.join(CACHE, 'plugins', file.filename), 'sha512', file.hashes.sha512)
}

function javaMajor (javaBin) {
  try {
    const out = execFileSync(javaBin, ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    return parseVersion(out)
  } catch (e) {
    return parseVersion(String(e.stderr ?? '')) // `java -version` prints to stderr
  }
}

function parseVersion (text) {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(text)
  if (!m) return 0
  return m[1] === '1' ? Number(m[2]) : Number(m[1])
}

function javaBinOf (home) {
  return path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
}

/**
 * A java binary with at least the given major version. Looks at E2E_JAVA_<n>,
 * JAVA_HOME_<n>_X64 (actions/setup-java), JAVA_HOME and cached JDKs before
 * downloading Temurin from Adoptium.
 */
async function java (minMajor) {
  const candidates = [
    process.env[`E2E_JAVA_${minMajor}`],
    process.env[`JAVA_HOME_${minMajor}_X64`],
    process.env.JAVA_HOME
  ].filter(Boolean)
  for (const home of candidates) {
    const bin = javaBinOf(home)
    if (fs.existsSync(bin) && javaMajor(bin) >= minMajor) return bin
  }
  const target = path.join(JDKS, String(minMajor))
  const cached = findJdkHome(target)
  if (cached) return javaBinOf(cached)

  const os = { win32: 'windows', darwin: 'mac', linux: 'linux' }[process.platform]
  const arch = process.arch === 'arm64' ? 'aarch64' : 'x64'
  const assets = await getJson(`https://api.adoptium.net/v3/assets/latest/${minMajor}/hotspot?os=${os}&architecture=${arch}&image_type=jdk&vendor=eclipse`)
  const pkg = assets[0]?.binary?.package
  if (!pkg) throw new Error(`No Temurin ${minMajor} build for ${os}/${arch}`)
  console.log(`Downloading Temurin ${minMajor} (${pkg.name})...`)
  const archive = await download(pkg.link, path.join(JDKS, pkg.name), 'sha256', pkg.checksum)
  fs.mkdirSync(target, { recursive: true })
  // On Windows use the bundled bsdtar (handles .zip); Git Bash's GNU tar treats "C:" as a host.
  const tar = process.platform === 'win32' ? path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe') : 'tar'
  execFileSync(tar, ['-xf', archive, '-C', target], { stdio: 'inherit' })
  fs.rmSync(archive)
  const home = findJdkHome(target)
  if (!home) throw new Error(`Extracted JDK not found in ${target}`)
  return javaBinOf(home)
}

function findJdkHome (dir) {
  if (!fs.existsSync(dir)) return null
  for (const entry of fs.readdirSync(dir)) {
    for (const home of [path.join(dir, entry), path.join(dir, entry, 'Contents', 'Home')]) {
      if (fs.existsSync(javaBinOf(home))) return home
    }
  }
  return null
}

module.exports = { paper, modrinth, java }
