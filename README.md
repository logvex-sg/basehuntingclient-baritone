# Base Hunting Client

A Meteor Client addon for anarchy servers, built around Baritone's elytra pathfinder. It flies long
routes with fireworks, flags chunks that were generated after the world was created, finds stashes,
tracks players across sessions, and pushes all of it to a Discord webhook.

Built for Minecraft 1.21.11 (Fabric).

## What it does

- **Base Hunter** — elytra autopilot. Grid, spiral, compass-heading, waypoint and coordinate routes,
  with firework accounting, optional pause near players and auto-disconnect on arrival.
- **Base Hunter V2** — firework-free elytra autopilot. Flies itself by recasting the elytra, so a trip
  is limited only by elytra durability, not by rockets. Ranks new-chunk detections, flies them in a
  nearest-neighbour tour, follows terrain altitude and pushes a frontier outward when nothing new is
  found. Any durability of elytra works; spares are swapped in automatically.
- **New Chunks** — scores chunks on inhabited time, missing light data and flowing liquids to find
  terrain nobody has ever visited. Newly generated chunks are where player activity starts.
- **Stash Finder** — counts storage blocks, spawners and shulker boxes as chunks stream in, scores
  each chunk and records anything above your threshold.
- **Player Logger** — records where players are seen and remembers the exact spot they logged out at.
- **Auto Log** — disconnects on low health, a nearby player, a totem pop, a breaking elytra or an
  empty firework supply.
- **Webhook Notifier** — Discord alerts for joins, leaves, player proximity, new chunks, stashes and
  disconnect reasons, with @everyone / role pings and per-server filtering.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Put [Meteor Client](https://meteorclient.com/) for 1.21.11 in your `mods` folder.
3. Optionally add [Baritone for Meteor](https://meteorclient.com/) — the autopilot needs it, the rest
   of the addon works without it.
4. Drop `basehunting-1.0.0.jar` into `mods`.
5. Launch the game and look for the **Base Hunting** category in Meteor's GUI.

Baritone is a `recommends`, not a `depends`, so the addon still loads if you skip it. `baritone` is
resolved reflectively at runtime; commands that need pathing report a clear error instead of crashing.

## Commands

All commands are under `/basehunt` (alias `/bh`).

| Command | What it does |
| --- | --- |
| `/basehunt` or `/basehunt status` | Server, dimension, position, Baritone availability, chunk and stash counts |
| `/basehunt fly <x> <z>` | Enable Base Hunter and start a route toward those coordinates |
| `/basehunt v2` | Elytra status and Base Hunter V2 progress |
| `/basehunt v2 start` | Enable Base Hunter V2 and begin hunting |
| `/basehunt v2 stop` | Stop Base Hunter V2 |
| `/basehunt v2 refresh` | Rebuild the target queue from current new-chunk detections |
| `/basehunt v2 elytra` | Report elytra status, equipping a spare if none is worn |
| `/basehunt stop` | Stop the autopilot, or cancel Baritone pathing if it was not running |
| `/basehunt waypoint add` | Add your current position as a route waypoint |
| `/basehunt waypoint add-at <x> <z>` | Add a specific position as a waypoint |
| `/basehunt waypoint clear` | Remove all waypoints |
| `/basehunt stashes` | List stashes recorded for the current server |
| `/basehunt players` | List players seen on the current server and their logout spots |
| `/basehunt clear` | Forget player records and logout spots for the current server |
| `/basehunt webhook test` | Send a test message to the configured webhook |

## Webhook setup

1. In Discord: **Server Settings → Integrations → Webhooks → New Webhook**, then copy the URL.
2. Enable the `webhook-notifier` module in Meteor.
3. Paste the URL into `webhook-url`.
4. Run `/basehunt webhook test` to confirm it works.

Each module that can notify has its own `Webhook` settings group. The common options are:

- `webhook-enabled` — turn notifications on for that module.
- `webhook-url` — the Discord webhook URL. **Stored in plain text** in your Meteor config folder, so
  treat it like a password and do not share your config folder.
- `webhook-username` — display name override.
- `mention-everyone` — prepend `@everyone`.
- `mention-role-id` — ping a specific role by ID.
- `only-on-servers` / `server-whitelist` — restrict sending to servers whose address contains one of
  the listed strings.

Mentions are applied to embed messages as well as plain text, so `@everyone` and role pings work for
every alert type.

## Modules

All under the **Base Hunting** category in Meteor's module list.

| Module | Default | Purpose |
| --- | --- | --- |
| `base-hunter` | off | Baritone-powered elytra autopilot |
| `base-hunter-v2` | off | Firework-free elytra autopilot; adaptive targets, any elytra durability |
| `new-chunks` | off | Detects chunks generated after world creation |
| `stash-finder` | off | Counts storage containers to find stashes |
| `player-logger` | off | Records player positions and logout spots |
| `auto-log` | off | Disconnects when in danger |
| `webhook-notifier` | off | Discord alerts for session and player events |

There is also a **Base Hunting** HUD element with optional server, new chunk, stash, player,
autopilot and firework readouts.

## Configuration notes

Everything is configurable from Meteor's module screen; there is no separate config file. A few
settings are worth understanding:

- **Base Hunter → mode** — `Grid` covers a bounded area methodically, `Spiral` expands outward from
  the centre, `Heading` flies a single compass bearing, `Waypoints` visits a list, `Coordinates`
  goes to one point. Straight-line flight misses almost everything, which is why the sweeps exist.
- **Base Hunter → fireworks-to-keep** — lands and stops when you run low. Set `0` to disable.
- **Base Hunter V2 → elytra-replace-durability** — swaps in your best spare elytra when the worn one
  drops to this much durability. `0` disables. This is what makes any durability of elytra usable.
- **Base Hunter V2 → cruise-y / terrain-follow / clearance** — preferred altitude, whether to raise it
  over higher ground, and how much clearance to keep. Terrain-following probes the ground ahead.
- **Base Hunter V2 → cluster-radius / interest-radius** — how close new chunks must be to count as one
  target, and how near a known stash or logout spot earns a target a priority bonus.
- **Base Hunter V2 → frontier-after-seconds / frontier-step** — how long to hunt without finding
  anything before pushing a waypoint outward, and how far out to push. `0` disables the push.
- **New Chunks → min-confidence** — how many of the three signals must agree before a chunk is
  reported. Raise it if you get false positives.
- **New Chunks → min-distance-from-spawn** — ignores chunks near spawn, where everything looks new.
- **Stash Finder → min-score** — the threshold for recording a chunk as a stash. Tune per server.
- **Player Logger → expire-hours** — how long logout spots are remembered.
- **Auto Log → player-names** — only disconnect for specific players. Empty means anyone.

## Building from source

Requires JDK 21.

```bash
./gradlew build
```

The jar lands in `build/libs/`. `./gradlew runClient` starts a development client with the addon and
Meteor loaded; Baritone is compile-only, so add it to the run config separately if you need to test
pathing.

## Project layout

```
src/main/java/dev/basehunt/bhclient/
├── BaseHuntingAddon.java        addon entrypoint, registers everything
├── commands/BaseHuntCommand.java
├── hud/BaseHuntingHud.java
├── modules/                     the seven user-facing modules
├── systems/                     StashManager, PlayerTracker (saved state)
└── utils/                       BaritoneHelper, ElytraController, ElytraManager, FlightPlanner,
                                 WebhookManager, ServerUtils

src/test/java/dev/basehunt/bhclient/
├── PlannerTest.java             FlightPlanner maths, no display needed
└── ApiContractTest.java         asserts the Meteor/Minecraft members the addon drives still exist
```

## License

See `LICENSE`.
