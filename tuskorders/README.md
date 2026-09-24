# TuskOrders

SMP-style buy orders for Paper. Players post what they want to buy and how much they pay per item; anyone can deliver through a chest menu and gets paid instantly.

- **Escrow**: the full order value is taken when the order is placed, so every delivery is paid, even if the buyer is offline
- **Chest-menu delivery**: open an order, drop items in, close. Matching items are paid for, everything else comes back
- **Honest items only**: only plain items count (nothing renamed, enchanted, damaged or filled), so buyers get what they paid for
- **Partial fills, cancel, expiry**: cancelling or expiring refunds exactly the unfilled part; delivered items stay collectable
- **Chat prompts**: `/orders create` walks you through item, amount and price, with a confirm screen before any money moves
- **Sorting and search**: highest price, most recent or biggest payout
- **Folia-ready**, one jar for Paper 1.21.4 through 26.x
- English and 繁體中文 messages, item names shown in each player's own game language

Requires [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin (EssentialsX, CMI, …).

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

## Config highlights

- `orders.expire-after-days`: unfilled orders close and refund after this long (0 = never)
- `orders.creation-fee-percent` / `orders.delivery-tax-percent`: optional money sinks
- `orders.min-price-each` / `orders.max-price-each` / `orders.max-amount`
- `blacklist`: items that can't be ordered, with `*` wildcards (spawn eggs, spawners and creative-only items by default)

## Development

```bash
./gradlew :tuskorders:build :e2e-test-economy:build
cd e2e && npm install && node run.js --scenario tuskorders --mc 26.1.2
```

End-to-end: `e2e/scenarios/tuskorders.js` drives two real clients through the main flows against a headless server, and `tuskorders-extra.js` covers the edge cases: limits, fees and taxes, admin cancel, expiry refunds, damaged items, and logging out or a server stop with items still in the delivery menu. `e2e/fixtures/test-economy` stands in for Vault.

Developed with AI assistance (Claude).
