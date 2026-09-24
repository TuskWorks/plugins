# TuskWorks Plugins

TuskWorks 的 Minecraft server plugin monorepo（GitHub: `TuskWorks/plugins`，public）。

## 目標與策略
- 免費 plugin 發布到 Modrinth / Hangar / CurseForge，拿廣告分潤並累積名聲
- 第 2 個月起推出付費 plugin，放在 BuiltByBit 販售；原始碼放另開的私人 repo `TuskWorks/premium`
- 開發、測試、打包、發布盡量全自動：使用 headless Paper server 加 mineflayer bot 測試，由 GitHub Actions 發布

## 規則
- API token 一律放在 GitHub Secrets，由 Tony 親自設定；Claude 不讀取、不輸入 token，也不碰金流
- **每次對外發布（上架、推新版本）都要先經 Tony 確認**
- 品牌命名避免使用 "Forge"（容易和 Forge mod loader 混淆）
- 品質優先：不量產低品質 plugin
- 依各平台的規定，如實揭露 AI 協助開發
- 名稱、描述不使用其他伺服器的品牌（例如 "Donut"）；可以寫「SMP-style」

## 目標版本
- **Paper 26.x**（目前最新是 26.3，需 Java 25），同時向下支援 **1.21.4+**（Java 21）
- Minecraft 從 2026 年起改用 `26.x` 版號，不再有 `1.22`
- 一律對 **paper-api 1.21.4** 編譯，bytecode 用 `--release 21`，避免誤用到只有新版才有的 API；26.x 的相容性靠 e2e 驗證
- 排程一律使用 Folia 相容的 scheduler（`GlobalRegionScheduler`、entity scheduler、`AsyncScheduler`），plugin.yml 要設 `folia-supported: true`

## 環境
- JDK 21（Temurin，系統安裝）：用來跑 Gradle 和 1.21.x server
- JDK 25：跑 26.x server 時需要。e2e harness 依序找 `E2E_JAVA_25`、`JAVA_HOME_25_X64`（setup-java 設的），都沒有就下載 Temurin 25 到 `e2e/.jdks/`。另外 TuskCrates 對新版 API 做相容性編譯（`-PpaperApi=26.2.build.128-stable`）時，由 Gradle toolchain（foojay）自動下載。平常編譯只用 JDK 21
- Gradle 9.x wrapper（`./gradlew`）、gh CLI、Node（mineflayer 測試用）
- Git Bash 的 PATH 可能沒有 `java`、`gh`，必要時用完整路徑（`C:\Program Files\GitHub CLI\gh.exe`）

## 結構
- `tuskclans/`：TuskClans（Teams/Clans）
- `tuskcrates/`：TuskCrates（crates，虛擬／實體鑰匙、三種開箱動畫、hologram）
- `tuskorders/`：TuskOrders（SMP-style 收購單市場，需要 Vault）
- `e2e/`：三個 plugin 共用的 headless Paper／Folia + mineflayer 端對端測試
  - `scenarios/<plugin>.js` 測主要流程，`scenarios/<plugin>-extra.js` 測邊界情況、上限、設定變更和重啟；`--scenario <name>` 會自動載入對應的檔案
  - `fixtures/test-economy`：名為 Vault 的 in-memory 經濟插件（Gradle project `:e2e-test-economy`），只給測試用、不發布
  - server jar 快取在 `e2e/.servers/`、測試伺服器在 `e2e/.run/`（都已 gitignore）
- mineflayer 最高支援 26.1。26.2 以上的 server 只跑 console smoke test（確認 plugin 能啟用、指令正常、log 沒有錯誤）
- `--via` 會改用 ViaVersion + ViaBackwards 讓 bot 連線，但目前在 26.2+ 會被踢（`Invalid move player packet`，屬於上游的封包轉換問題）

## 常用指令
- Build 加 unit test：`./gradlew build`（三個 plugin 加上 test economy）
- E2E：`cd e2e && npm install && node run.js --scenario tuskcrates --mc 26.1.2`（scenario：`tuskclans`、`tuskcrates`、`tuskorders`，以及各自的 `-extra`；版本：`1.21.4`、`1.21.11`、`26.1.2`、`26.3`；加上 `E2E_VERBOSE=1` 可以看完整輸出）
- E2E 測 Folia：`node run.js --scenario tuskorders --server folia --mc 1.21.11`
- CI：`.github/workflows/tusk{clans,crates,orders}.yml`，各自跑 build、unit test，以及 e2e 矩陣（主要流程：Paper 1.21.4／1.21.11／26.1.2／26.3 smoke、Folia 1.21.11／26.1.2；延伸測試：Paper 26.1.2、Folia 1.21.11）。TuskCrates 另外對 Paper API 26.2 做相容性編譯
