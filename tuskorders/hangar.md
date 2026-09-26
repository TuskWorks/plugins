# TuskOrders

SMP-style buy orders for Paper and Folia. Players post what they want to buy and how much they pay per item;
anyone can deliver through a chest menu and gets paid instantly.

- **Escrow**: the full order value is taken when the order is placed, so every delivery is paid, even if the buyer is offline
- **Chest-menu delivery**: open an order, drop items in, close. Matching items are paid for, everything else comes back
- **Honest items only**: only plain items count (nothing renamed, enchanted, damaged or filled), so buyers get what they paid for
- **Partial fills, cancel, expiry**: cancelling or expiring refunds exactly the unfilled part; delivered items stay collectable
- **Chat prompts**: `/orders create` walks you through item, amount and price, with a confirm screen before any money moves
- **Sorting and search**: highest price, most recent or biggest payout
- **Folia-ready**, one jar for Paper 1.21.4 through 26.x
- English and 繁體中文 messages, item names shown in each player's own game language

## Requirements

[Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin (EssentialsX, CMI, …).

## Commands

| Command | Description | Permission |
|---|---|---|
| `/orders` | Browse open orders and deliver | `tuskorders.use` (default) |
| `/orders create [item amount price]` | Place an order (no arguments = guided) | `tuskorders.create` (default) |
| `/orders mine` | Collect delivered items, cancel orders | `tuskorders.use` |
| `/orders search <item>` | Show orders for one item | `tuskorders.use` |
| `/orders cancel <id>` | Cancel any order and refund its owner | `tuskorders.admin` (op) |
| `/orders reload` | Reload config and messages | `tuskorders.admin` |

Prices accept shorthand such as `2.50`, `1.5k` or `2m`. `hand` picks the item you're holding.

Open orders per player default to 5; grant `tuskorders.limit.<number>` or `tuskorders.limit.unlimited` for more.

## Configuration

See [`config.yml`](https://github.com/TuskWorks/plugins/blob/main/tuskorders/src/main/resources/config.yml). Highlights:

- `orders.expire-after-days`: unfilled orders close and refund after this long (0 = never)
- `orders.creation-fee-percent` / `orders.delivery-tax-percent`: optional money sinks
- `orders.min-price-each` / `orders.max-price-each` / `orders.max-amount`
- `blacklist`: items that can't be ordered, with `*` wildcards (spawn eggs, spawners and creative-only items by default)

## Compatibility

| Server | Supported | Tested on real servers |
|---|---|---|
| Paper | 1.21.4 – 26.3 | 1.21.4, 1.21.11, 26.1.2 (real clients) · 26.3 (console) |
| Folia | 1.21.4 – 26.x | 1.21.11, 26.1.2 (real clients) |

## AI disclosure

TuskOrders is written primarily by AI (Claude by Anthropic) under the direction of TuskWorks: the code,
tests and documentation are AI-generated. Every release has to pass unit tests and end-to-end tests
with real Minecraft clients on Paper and Folia before it is published.

## Source, issues and license

Source code: [github.com/TuskWorks/plugins](https://github.com/TuskWorks/plugins/tree/main/tuskorders) ·
Bug reports: [GitHub issues](https://github.com/TuskWorks/plugins/issues)

Licensed under the GNU General Public License v3.0 only (GPL-3.0-only).
