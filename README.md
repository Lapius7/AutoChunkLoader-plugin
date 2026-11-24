<div align="center">

<p><b>English</b> | <a href="README.ja.md">日本語</a></p>

<h1>AutoChunkLoader</h1>

<h3>Keep chunks loaded with one short command, even when nobody is nearby.</h3>

<p>A Paper plugin that holds chunks open with plugin chunk tickets, so hoppers, redstone and item sorters keep running far from players.</p>

<p>
<a href="https://github.com/Lapius7/AutoChunkLoader-plugin/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/Lapius7/AutoChunkLoader-plugin?label=release&style=flat-square&color=e8833a&logo=github"></a>
<a href="https://papermc.io/"><img alt="Paper 26.3" src="https://img.shields.io/badge/Paper-26.3-4b8bbe?style=flat-square"></a>
<a href="https://adoptium.net/"><img alt="Java 25" src="https://img.shields.io/badge/Java-25-f89820?style=flat-square&logo=openjdk&logoColor=white"></a>
<img alt="Minecraft Java Edition" src="https://img.shields.io/badge/Minecraft-Java%20Edition-62b47a?style=flat-square">
<img alt="Languages: ja, en" src="https://img.shields.io/badge/Languages-ja%20%7C%20en-97ca00?style=flat-square">
<a href="https://github.com/Lapius7/AutoChunkLoader-plugin/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/Lapius7/AutoChunkLoader-plugin?style=flat-square&color=e3b341&logo=github"></a>
<a href="https://github.com/Lapius7/AutoChunkLoader-plugin/commits/main"><img alt="Last commit" src="https://img.shields.io/github/last-commit/Lapius7/AutoChunkLoader-plugin?style=flat-square&color=2f81f7"></a>
</p>

<p><a href="#quick-start">Quick start</a> · <a href="#commands">Commands</a> · <a href="#configuration">Configuration</a> · <a href="#limits">Limits</a></p>

<p><img src="assets/chat.png" alt="Chat output of /acl add, /acl info and /acl remove chunk on a Paper 26.3 test server" width="640"></p>

</div>

## Overview

Stand in a chunk and run `/acl add`. The plugin keeps that chunk loaded until you remove it. A group can also cover many chunks: a radius around you, or the box between two block positions. Groups work in every dimension.

> [!NOTE]
> Checked on a Paper 26.3 test server in the Overworld and the Nether. The End and the `full` Inspector mode were not re-checked for 0.1.0.

## Features

- Short commands for the chunk you stand in: `/acl add`, `/acl remove`, `/acl here`
- Add by radius, by chunk coordinates, or by two block positions (`~` allowed, y ignored), or with `pos1` / `pos2`
- Add or remove a single chunk of an existing group, so groups need not be rectangles
- `list`, `info`, `resize`, `expand` and `rename`, with clickable buttons and hover text
- Inspector (`/acl i`): outlines the kept chunks with particles that only you can see. The default `edges` mode draws only the outer edges; `full` draws rings and posts over the whole wall
- Tickets are reference counted, so overlapping groups are safe
- Groups survive restarts, in every world
- Japanese and English messages; both language files are editable

## Quick start

> [!NOTE]
> Requires Paper 26.3 and Java 25.

1. Download `AutoChunkLoader-0.1.0.jar` from [Releases](https://github.com/Lapius7/AutoChunkLoader-plugin/releases/latest) and put it in `plugins/`, then start the server.
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
| `/acl add here <name>`, `/acl add chunk <name> [x z]` | Add only the current (or given) chunk to an existing group |
| `/acl remove [name]`, `/acl remove chunk <name> [x z]` | Delete a group (multi-chunk groups ask for confirmation), or remove one chunk from it |
| `/acl list [page]` | List groups; world names are coloured by dimension |
| `/acl info [name]` | Details of a group |
| `/acl resize <name> <+n/-n>` | Grow or shrink by n chunks on every side |
| `/acl expand [name]` | Grow to include the current chunk |
| `/acl rename <old> <new>` | Rename a group |
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

`plugins/AutoChunkLoader/config.yml`:

| Key | Default | Meaning |
|---|---|---|
| `language` | `ja` | `ja` or `en`. Edit `lang/<code>.yml` to change messages |
| `limits.max-radius` | `4` | Largest radius for center + radius groups; size is (2r+1)² |
| `limits.max-chunks-per-group` | `100` | Chunks in one group |
| `limits.max-chunks-total` | `200` | Chunks kept on the whole server |
| `limits.max-groups-per-player` | `5` | Groups per player |
| `inspector.mode` | `edges` | `edges` or `full` |
| `inspector.*` | | radius, height, interval, step, spacing, falloff, particle cap, colours and sizes |
| `list.page-size` | `8` | Groups per page of `/acl list` |

## Limits

> [!IMPORTANT]
> A ticket keeps a chunk loaded and running, but vanilla ties random ticks and mob spawning to nearby players. In tests on Paper 26.3 (chunks about 3000 blocks from any player, load level `ENTITY_TICKING`):
> - Hoppers kept working.
> - Crops did not grow far away (age 0 after 3 minutes, while the same crop near a player reached age 7).
> - No mobs spawned in the far group, while mobs spawned near a player.
>
> Expect machines that move items to work, and farms and mob traps to need a player nearby.

<details>
<summary>Build from source</summary>

Java 25 is required. The jar is written to `build/libs/`.

```sh
./gradlew build
```

</details>
