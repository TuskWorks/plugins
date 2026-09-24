# TuskCrates

A modern crates plugin for **Paper and Folia**, from **1.21.4 up to 26.x**. One jar for all of those versions.

- **Virtual and physical keys.** SMP-style virtual keys in a `/crates` menu, physical key items, and `/tc keyall` for events.
- **Three opening animations.** A spinning **roulette** menu, an in-world **display** animation that spins the reward above the crate, or **instant**.
- **Native holograms.** Crate labels use text display entities, so you don't need a hologram plugin.
- **In-game editing.** Hold any item (custom names, enchantments, custom model data) and run `/tc additem <crate> <weight>`.
- **Fair and safe.** The reward is rolled when the crate opens, and the animation only reveals it. Players get their reward even if they close the menu early or log out mid-animation. Keys can't be placed or crafted with, and crate blocks survive explosions and pistons.
- **Folia native.** Every task runs on the right region or entity scheduler.

## Installation

1. Drop `TuskCrates-<version>.jar` into `plugins/`.
2. Start the server. Three example crates are created in `plugins/TuskCrates/crates/`: `common`, `rare` and `legendary`.
3. Look at a block (a chest works well) and run `/tc set common`.
4. Give yourself a key: `/tc give <you> common 1`.

## Commands

| Command | Description |
|---|---|
| `/crates` | Open the crates menu with your virtual keys. Left-click opens a crate, right-click previews it. |
| `/tc give <players> <crate> [amount] [virtual\|physical]` | Give keys. Accepts selectors such as `@a`. |
| `/tc keyall <crate> [amount]` | Give virtual keys to everyone online. |
| `/tc take <player> <crate> [amount]` | Take virtual keys. |
| `/tc keys [player]` | Show virtual key balances. |
| `/tc set <crate> [x y z]` | Turn the block you look at (or the given coordinates) into a crate. |
| `/tc remove [x y z]` | Remove a crate block. Admins can also sneak and break the crate. |
| `/tc additem <crate> <weight>` | Add the item in your hand as a reward. |
| `/tc preview <crate>` | Open a crate's preview. |
| `/tc open <crate>` | Open a crate without a key (for testing). |
| `/tc list` | List loaded crates. |
| `/tc reload` | Reload config, messages and crates. |

`/tc` is an alias of `/tuskcrates`.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `tuskcrates.admin` | op | Every `/tc` command, and sneak-breaking crates |
| `tuskcrates.use` | everyone | Open and preview crates |
| `tuskcrates.menu` | everyone | Use `/crates` |

## Crate files

Each file in `crates/` is one crate, and its file name is the crate id. All text uses [MiniMessage](https://docs.advntr.dev/minimessage/format).

```yaml
display-name: "<gradient:#ffd452:#ff6a00><b>Legendary</b></gradient>"
animation: display          # instant | roulette | display
show-in-menu: true

key:
  material: TRIPWIRE_HOOK
  name: "<crate> <white>Key"
  glow: true

hologram:
  - "<crate> <white>Crate"
  - "<gray>Right-click with a key"

rewards:
  god-sword:
    weight: 20              # relative chance; the preview shows percentages
    broadcast: true
    item:
      material: NETHERITE_SWORD
      name: "<gold>Sunforged Blade"
      enchantments:
        sharpness: 5
        mending: 1
  money:
    weight: 10
    give-item: false        # only show the item, run commands instead
    item:
      material: GOLD_BLOCK
      name: "<gold>$10,000"
    commands:
      - "eco give <player> 10000"
```

These item keys are supported: `material`, `amount`, `name`, `lore`, `enchantments`, `glow`, `item-model`, `custom-model-data` and `flags`. Items added with `/tc additem` are stored as `base64`, which keeps every component exactly.

## Compatibility

| Server | Supported | Tested on real servers |
|---|---|---|
| Paper | 1.21.4 – 26.3 | 1.21.4, 1.21.11, 26.1.2 (bot) · 26.2, 26.3 (console) |
| Folia | 1.21.4 – 26.x | 1.21.11, 26.1.2 (bot) |

The bot tests place crates, open them with each key type and animation, close menus early, disconnect mid-animation and check what ends up on disk.

## Building

```bash
./gradlew :tuskcrates:build
```

The jar ends up in `tuskcrates/build/libs/`. To run the end-to-end tests:

```bash
cd tuskcrates/e2e && npm install && node run.js --mc 1.21.11
```

Other options: `--mc 26.1.2`, `--folia`, `--mc 26.3` (console only).

## AI disclosure

TuskCrates is developed with the help of AI coding tools (Claude by Anthropic). Every release is tested on real Paper and Folia servers.
