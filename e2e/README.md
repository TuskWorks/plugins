# E2E tests

Real Minecraft clients ([mineflayer](https://github.com/PrismarineJS/mineflayer)) against a throwaway
headless Paper server.

```bash
./gradlew :tuskclans:build        # from the repo root
cd e2e
npm install
node run.js --mc 26.1.2           # or 1.21.11, 26.3 …
E2E_VERBOSE=1 node run.js --mc 26.3   # stream server + bot output
```

What the runner does:

1. Downloads the latest Paper build for `--mc` from `fill.papermc.io` into `.servers/` (checksum-verified, cached).
2. Finds a JDK that version needs (Paper 26.x needs Java 25). It checks `E2E_JAVA_<n>`, `JAVA_HOME_<n>_X64`
   (set by `actions/setup-java`) and `JAVA_HOME`, then falls back to downloading Temurin into `.jdks/`.
3. If mineflayer doesn't speak that version yet (it tops out at 26.1), runs the scenario's console-only
   `smoke()` instead. `--via` bridges the bots through ViaVersion + ViaBackwards from Modrinth, but on 26.2+
   they currently get kicked with "Invalid move player packet" (an upstream translation issue).
4. Starts the server in `.run/<scenario>-<version>/` (offline mode, flat world, no mobs) and runs the scenario.
5. Fails if any step fails or the server log contains an error mentioning the plugin.

Scenarios live in `scenarios/`; each exports `plugins()` (jars to install), `run({ server, join, step })`
for bot tests and `smoke({ server, step })` for console-only checks.
