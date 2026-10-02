# irDeathHandler

Deathbans for Paper 1.21.11 (it also runs on Purpur).

When a player dies:
- their loot drops
- every online player hears the wither die
- the player is removed instantly, and everyone else just sees the usual yellow "*name* left the game", with no ban message
- they can't rejoin until you pardon them

`/immortal` keeps chosen players alive. Immortal players can't drop below a minimum health unless they're holding a totem.

It works with the other plugins:
- **Rename:** death, kill and leave messages show the player's nickname, and obscured nicknames stay scrambled. Bans are stored by UUID, so a nickname can't be used to sneak back in. Lookups accept either the real name or the nickname.
- **TeamSplit:** deathbanned players stay on their team (shown as offline). Pardon them and they come back on the same side.
- **IRKit and every other plugin that takes selectors:** `/immortal` takes vanilla selectors too, e.g. `/immortal @a[team=red] on`.

## Install

1. Download the jar: open this repo's **Actions** tab, click **Build**, open the latest run, and download **irDeathHandler** under **Artifacts**. It's a zip; the jar is inside.
2. Put it in your server's `plugins/` folder and restart.

Deathbans are **on** as soon as it's installed. Use `/deathban off` while setting up a scene.

## Commands

| Command | What it does |
|---|---|
| `/deathban list` | Everyone who's deathbanned, with the nickname they died as, the death message, how long ago, and a clickable **[pardon]**. |
| `/deathban info <player>` | That player's last 10 deaths: cause or killer, nickname, world and coordinates, and whether they were banned. The history survives pardons. |
| `/deathban pardon <player>` | Let one player back in. Accepts their real name or the nickname they died as. |
| `/deathban pardon -all` | Let everyone back in. |
| `/deathban revive <player>` | Pardon them, and when they next join they reappear exactly where they died. |
| `/deathban on` / `off` | Turn deathbans on or off. Off means deaths are normal again. Remembered across restarts. |
| `/deathban status` | On or off, how many players are banned, and who's immortal. |
| `/immortal` | Toggle immortal for yourself. |
| `/immortal <targets> [on\|off]` | Toggle or set immortal for any players, with any selector. |
| `/immortal list` | Who's immortal. |
| `/deathban reload` | Reload `config.yml`. |

Permissions:
- `deathhandler.admin` (default: op): use the commands.
- `deathhandler.exempt` (default: nobody, not even ops): never deathbanned. Their deaths are still logged.

## Immortal

- Immortal players can't go below `immortal.min-health` (one heart by default). Every hit, fall, burn, `/kill` or void tick that would take them lower leaves them at that minimum instead. They still get hurt and knocked back, so it looks natural on camera.
- If they hold a **totem of undying** in either hand, the hit goes through and the totem pops, just like in vanilla.
- Immortal players are never deathbanned.
- If something still manages to kill them, the death is cancelled.
- Immortal status is remembered across restarts.

## Configuration

`plugins/irDeathHandler/config.yml`:

```yaml
deathbans-enabled: true              # first-start default; /deathban on|off after that
kick-message: "<red>You died."       # what the dead player sees on their disconnect screen
force-drops: true                    # drop loot and XP even with keepInventory on
sound:
  name: "minecraft:entity.wither.death"
  volume: 1.0
  pitch: 1.0
immortal:
  min-health: 2.0                    # half hearts; 2.0 = one heart
```

Bans, the death log, immortal players and revives are stored in `plugins/irDeathHandler/deathhandler.db`, a single SQLite file with nothing to set up. irDeathHandler doesn't use the vanilla ban list, so `/ban`, `/pardon` and other plugins are unaffected.

## Building

You need JDK 21. Alternatively, let GitHub build it: every push runs the **Build** workflow, which uploads the jar as an artifact.

```
./gradlew build          # jar ends up in build/libs/
./gradlew runServer      # starts a local Paper 1.21.11 test server with the plugin installed
```
