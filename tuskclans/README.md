# TuskClans

Lightweight clans & teams for SMP servers — clan chat, friendly-fire protection, alliances,
clan homes and a kill leaderboard. Built for **Paper 1.21.4 – 26.x** and **Folia**.

- No dependencies — PlaceholderAPI and Vault are optional
- Folia-safe schedulers throughout
- Clean MiniMessage messages, English and 繁體中文 included
- Data stored as plain JSON (one file per clan), written off the main thread

## Features

| | |
|---|---|
| **Menu** | `/clan` opens a chest menu: clan info, member heads, one-click home, chat toggles, friendly fire and more |
| **Clans** | `/clan create <tag> <name>`, invites with clickable accept/deny, open clans anyone can join |
| **Ranks** | Leader → Officer → Member. Officers invite, kick members and set the home; the leader promotes, demotes, transfers and disbands |
| **Chat** | `/clan chat <msg>` or toggle clan chat with `/clan chat`; ally chat with `/clan allychat`; clan tag in public chat |
| **Friendly fire** | Clanmates (and allies, optionally) can't hurt each other — melee and projectiles. Toggle per clan with `/clan ff` |
| **Alliances** | `/clan ally <tag>` sends a request; the other clan accepts by allying back. Configurable limit |
| **Clan home** | `/clan sethome`, `/clan home` with warmup, move-cancel and cooldown |
| **Stats** | Clan kills/deaths, `/clan top`, `/clan list`, `/clan info` |

## Commands

`/clan` (alias `/clans`, configurable — add `team` for SMP-style servers)

| Command | Who |
|---|---|
| *(no arguments)*, `menu` | everyone — opens the clan menu |
| `help` | everyone |
| `create <tag> <name>` | anyone without a clan |
| `info [tag]`, `list [page]`, `top` | everyone |
| `invite <player>` | officer+ |
| `accept [tag]`, `deny [tag]`, `join <tag>` | players without a clan |
| `leave` | members and officers |
| `kick <player>` | officer+ (only lower ranks) |
| `promote <player>`, `demote <player>`, `transfer <player>`, `disband` | leader |
| `chat [msg]`, `allychat [msg]` | members |
| `home` | members |
| `sethome`, `delhome`, `ff`, `open`, `ally <tag>`, `unally <tag>` | officer+ |
| `admin reload`, `admin disband <tag>` | `tuskclans.admin` |

## Permissions

| Permission | Default | |
|---|---|---|
| `tuskclans.use` | everyone | Use `/clan` |
| `tuskclans.create` | everyone | Create clans |
| `tuskclans.home` | everyone | Use `/clan home` |
| `tuskclans.admin` | op | Reload and manage any clan |
| `tuskclans.bypass.cost` | op | Create clans for free |
| `tuskclans.bypass.cooldown` | op | Skip home warmup and cooldown |

## Placeholders (PlaceholderAPI)

| Placeholder | |
|---|---|
| `%tuskclans_has_clan%` | `true` / `false` |
| `%tuskclans_tag%`, `%tuskclans_tag_formatted%` | raw tag, or styled with `chat.tag-format` |
| `%tuskclans_name%`, `%tuskclans_role%`, `%tuskclans_leader%` | |
| `%tuskclans_members%`, `%tuskclans_online%`, `%tuskclans_allies%` | |
| `%tuskclans_kills%`, `%tuskclans_deaths%`, `%tuskclans_kdr%` | |
| `%tuskclans_top_<n>_<tag\|name\|kills\|deaths\|members>%` | leaderboard, e.g. `%tuskclans_top_1_tag%` |

Using a chat or tab plugin? Set `chat.show-tag: false` and put `%tuskclans_tag_formatted%` in its format.

## Configuration

See [`config.yml`](src/main/resources/config.yml). Highlights: tag length and pattern, member and
ally limits, creation cost (Vault), home warmup/cooldown, worlds where homes and protection are off.
Messages live in `plugins/TuskClans/lang/`; pick one with `language:`.

## Building

```bash
./gradlew :tuskclans:build
```

The jar lands in `tuskclans/build/libs/`. End-to-end tests with real clients live in [`../e2e`](../e2e).

---

Developed by TuskWorks with AI assistance (Claude). Every release is checked by unit tests,
end-to-end tests with real Minecraft clients on Paper 1.21.4, 1.21.11 and 26.1.2 and on Folia
1.21.11 and 26.1.2, a server smoke test on Paper 26.3, and a compile check against the Paper 26.2 API.
