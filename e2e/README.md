# E2E tests

Real Minecraft clients ([mineflayer](https://github.com/PrismarineJS/mineflayer)) against a throwaway
headless Paper or Folia server, shared by every plugin in this repo.

```bash
./gradlew build                   # from the repo root: all plugins plus the test economy
cd e2e
npm install
node run.js --scenario tuskcrates --mc 26.1.2     # or 1.21.4, 1.21.11, 26.3 …
node run.js --scenario tuskclans-extra --server folia --mc 1.21.11
E2E_VERBOSE=1 node run.js --scenario tuskorders --mc 26.3   # stream server + bot output
```

| Scenario | Plugin | Covers |
|---|---|---|
| `tuskclans` (default) | TuskClans | clan lifecycle, chat channels, friendly fire, alliances, homes, restart |
| `tuskclans-extra` | TuskClans | invites and open clans, kicks, transfer, limits, expiry, arrows, stats, Vault cost, zh_TW |
| `tuskcrates` | TuskCrates | crate blocks, holograms, every key type and animation, previews, `/crates`, keyall |
| `tuskcrates-extra` | TuskCrates | broken crate files, command rewards, permissions, explosions, pistons, crafting, autosave, restart |
| `tuskorders` | TuskOrders | placing, delivering, collecting and cancelling orders with money checked at every step |
| `tuskorders-extra` | TuskOrders | limits, fees and taxes, expiry refunds, damaged items, logout or server stop mid-delivery |

`fixtures/test-economy` is an in-memory economy named Vault (Gradle project `:e2e-test-economy`); scenarios
that need money install it instead of a real economy plugin.

What the runner does:

1. Downloads the latest Paper build for `--mc` from `fill.papermc.io` into `.servers/` (checksum-verified, cached).
2. Finds a JDK that version needs (Paper 26.x needs Java 25). It checks `E2E_JAVA_<n>`, `JAVA_HOME_<n>_X64`
   (set by `actions/setup-java`) and `JAVA_HOME`, then falls back to downloading Temurin into `.jdks/`.
3. If mineflayer doesn't speak that version yet (it tops out at 26.1), runs the scenario's console-only
   `smoke()` instead. `--via` bridges the bots through ViaVersion + ViaBackwards from Modrinth, but on 26.2+
   they currently get kicked with "Invalid move player packet" (an upstream translation issue).
4. Starts the server in `.run/<scenario>-<version>/` (offline mode, flat world, no mobs) and runs the scenario.
   Natural mob spawning is switched off by game rule (`doMobSpawning` up to 1.21.10, `spawn_mobs` from 1.21.11):
   from 1.21.11 `spawn-monsters` in server.properties no longer applies, and superflat slimes would otherwise
   kill bots in longer scenarios.
5. Fails if any step fails or the server log contains a warning or error from the plugin.

Scenarios live in `scenarios/` and `--scenario <name>` loads `scenarios/<name>.js`. Each exports
`plugins()` (jars to install), `run({ server, join, step })` for bot tests and `smoke({ server, step })`
for console-only checks, plus `logName`/`packagePrefix` (whose log lines count as errors) and optionally
`expectedLog` (regexes for warnings the scenario provokes on purpose). Shared helpers are in
`lib/helpers.js`; `helpers.skip(reason)` marks a step as skipped instead of failed.

`<plugin>` covers the main flows; `<plugin>-extra` covers edge cases, limits, config changes and restarts.
