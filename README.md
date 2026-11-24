<div align="center">

**English** | [日本語](README.ja.md)

# AutoChunkLoader

**Keep chunks loaded with a short command. Even when nobody is nearby.**

A Paper plugin that holds chunks open with plugin chunk tickets, so hoppers, redstone and item sorters keep running far from players.

<a href="https://papermc.io/"><img src="https://img.shields.io/badge/Paper-26.3-4b8bbe?style=flat-square" alt="Paper 26.3"></a>
<a href="https://adoptium.net/"><img src="https://img.shields.io/badge/Java-25-e8833a?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25"></a>
<img src="https://img.shields.io/badge/Minecraft-Java%20Edition-62b47a?style=flat-square" alt="Minecraft Java Edition">
<img src="https://img.shields.io/badge/Languages-ja%20%7C%20en-97ca00?style=flat-square" alt="ja | en">

[Quick start](#quick-start) · [Commands](#commands) · [Configuration](#configuration) · [Limits](#limits)

</div>

## Overview

Register the chunk you stand in with `/acl add`. The plugin keeps it loaded until you remove it. Groups can cover many chunks (a radius, or a box between two block coordinates) and work in the Overworld, Nether and End.

## Features

- Short commands for the chunk you stand in: `/acl add`, `/acl remove`, `/acl here`
- Groups by radius, chunk coordinates, two block coordinates (`~` allowed, y ignored) or `pos1` / `pos2`
- `list`, `info`, `resize`, `expand`, `rename`, with clickable buttons, hover text and paging
- Inspector (`/acl i`): draws only the outer edges of the kept chunks with particles (top and bottom lines plus corner pillars; nothing on the walls or inside), visible only to you. `inspector.mode: full` restores the old rings-and-posts wall
- Tickets are reference counted, so overlapping groups are safe
- Groups survive restarts, in every world
- Japanese and English messages, editable language files

## Quick start

Requires Paper 26.3 and Java 25.

1. Put `AutoChunkLoader-0.1.0.jar` in `plugins/` and start the server.
2. Stand in a chunk and run `/acl add`. Check it with `/acl here`.
3. Remove it with `/acl remove`.

## Commands

`/acl` is the same as `/autochunkloader`.

<details>
<summary>All commands</summary>

| Command | What it does |
|---|---|
| `/acl here` | Status of the current chunk: registered or not, group, load level |
| `/acl add [name] [radius]` | Register the current chunk (auto-named when no name is given) |
| `/acl add <name> <x> <z> [radius] [world]` | Register by chunk coordinates |
| `/acl add <name> <x1> <y1> <z1> <x2> <y2> <z2> [world]` | Register every chunk between two block positions |
| `/acl pos1`, `/acl pos2`, `/acl add <name> sel` | Select two points, then register |
| `/acl remove [name]` | Remove a group and release its tickets (asks to confirm for several chunks) |
| `/acl remove here`, `/acl remove chunk [name] [x z]` | Take only this (or the given) chunk out of a group. An empty group is deleted; the rest may split into separate regions |
| `/acl add here <name>`, `/acl add chunk <name> [x z]` | Add only this (or the given) chunk to an existing group |
| `/acl list [page]` | List groups; world names are coloured by dimension |
| `/acl info [name]` | Details of a group |
| `/acl resize <name> <+n/-n>` | Grow or shrink by n chunks on every side |
| `/acl expand [name]` | Grow to include the current chunk |
| `/acl rename <old> <new>` | Rename |
| `/acl inspector`, `/acl i` | Toggle the outline |
| `/acl reload` | Reload config and language files |

</details>

### Permissions

| Permission | Allows | Default |
|---|---|---|
| `autochunkloader.use` | list, info, here, inspector, help | everyone |
| `autochunkloader.manage` | add, remove, resize, expand, rename your own groups | op |
| `autochunkloader.admin` | any group, reload, ignore limits | op |

## Configuration

`plugins/AutoChunkLoader/config.yml`: `language` (`ja` / `en`), `limits` (radius, chunks per group, chunks in total, groups per player), `inspector` (`mode`: `edges` (default) or `full`, radius, height, step, ring and post spacing (`full` only), distance falloff, particle cap, edge and corner colour and size) and `list.page-size`. Edit `lang/<code>.yml` to change messages.

## Limits

> [!IMPORTANT]
> A ticket keeps a chunk loaded and running, but vanilla ties random ticks and mob spawning to nearby players. In tests on Paper 26.3 (chunks about 3000 blocks from any player, load level `ENTITY_TICKING`):
>
> - Hoppers kept working.
> - Crops did not grow far away (age 0 after 3 minutes, while the same crop near a player reached age 7).
> - No mobs spawned in the far group, while mobs spawned near a player.
>
> Expect machines that move items to work, and farms or mob traps that rely on spawning or random ticks not to.

> [!NOTE]
> Not verified: redstone clocks far away, loading a world after the plugin starts, and custom worlds beyond the Nether and the End.
