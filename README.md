# WorldItems

A Paper plugin that gives players locked, per-world items (compass, book, etc.) that run
configurable actions when clicked: transfer to another server, open menus, show live server
info, run commands, and more. Everything is defined in `config.yml`.

## Features
- Items per world (`worlds: ["world"]` or `["*"]`), re-given on join, respawn and world change
- Items can't be dropped, moved, swapped, or lost on death
- Actions: `transfer`, `menu`, `close`, `message`, `broadcast`, `actionbar`, `title`, `sound`, `console`, `player`, `teleport`
- Config-driven chest menus with placeholders (`%player%`, `%online%`, `%max%`, `%world%`, `%world_online%`, `%ping%`)
- Per-item `click`, `cooldown`, `permission`, `glow`, `enabled`

## Commands (permission: `worlditems.admin`)
| Command | Description |
|---|---|
| `/worlditems reload` | Reload `config.yml` |
| `/worlditems list` | Show items and ON/OFF state |
| `/worlditems on\|off\|toggle <id>` | Enable/disable an item |

## Build
Edit `paper.version` in `pom.xml` to match your server, then:

```
mvn package
```

The jar is created in `target/`. Pushing to GitHub also builds it automatically
(Actions tab → latest run → Artifacts).

## Transferring to a Forge server
The target server needs `accept-transfers=true` in `server.properties`.
Players need the matching Forge version and mods on their client.
