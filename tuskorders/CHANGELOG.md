# Changelog

## 0.1.0 — 2026-09-26

First release.

- Buy orders with escrow: the full value is taken up front, so every delivery is paid even when the buyer is offline
- Deliver through a chest menu; matching items are paid instantly, everything else comes back
- Only plain items count (nothing renamed, enchanted, damaged or filled)
- Partial fills; cancelling or expiring refunds exactly the unfilled part, delivered items stay collectable in `/orders mine`
- Guided `/orders create` with a confirm screen; prices accept shorthand such as `1.5k` or `2m`
- Browse sorted by highest price, most recent or biggest payout; `/orders search <item>`
- Per-player order limits via permissions, optional creation fee and delivery tax, price and amount limits, item blacklist with wildcards
- English and Traditional Chinese messages, item names shown in each player's game language
- Requires Vault and an economy plugin; Paper 1.21.4 – 26.x and Folia
