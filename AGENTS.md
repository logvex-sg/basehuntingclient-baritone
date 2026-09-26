# AGENTS.md

Meteor Client addon for Minecraft 1.21.11 (Fabric) providing an anarchy base-hunting toolkit built on
Baritone's elytra pathfinder.

## Build

JDK 21 required. The Gradle wrapper is the only entry point.

```bash
./gradlew build          # jar -> build/libs/basehunting-1.0.0.jar
./gradlew compileJava    # fast syntax/type check
./gradlew runClient      # dev client with Meteor + addon loaded
```

`./gradlew runClient` needs a display. In a headless container install Xvfb and use software GL:

```bash
sudo apt-get install -y xvfb mesa-utils libgl1-mesa-dri
nohup setsid Xvfb :99 -screen 0 1280x720x24 -nolisten tcp </dev/null >/tmp/xvfb.log 2>&1 &
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ./gradlew runClient
```

Run Xvfb detached with `setsid`/`nohup`. If it is a plain background job of the agent shell it gets
stopped and GLFW hangs forever inside `glfwInit` with no error output. Client logs land in
`run/logs/latest.log` and `run/logs/debug.log`.

Expect these harmless errors on every headless run: `libflite.so not found`, `Failed to open OpenAL
device`, `401` on user properties, and the Realms `SignedJWT` failure. None are addon problems.

## Verification without a display

Minecraft's dev jar relies on fabric-loader for access widening, so classes cannot just be loaded
from a plain `URLClassLoader` — `net.minecraft.registry.SimpleRegistry` throws `IllegalAccessError`
on `RegistryEntry$Reference.setRegistryKey`. Two workable approaches:

1. **Real client** (preferred, exercises everything): `./gradlew runClient` as above. A successful
   load prints `Initializing Base Hunting Client` followed by `(Meteor Client) Loading`.
2. **Targeted unit test**: put the compiled classes, the loom-merged Minecraft jar, and the rest of
   the compile classpath on the *system* classpath, then call `net.minecraft.Bootstrap.initialize()`
   only if the code under test touches registries. `WebhookManager` does not, so it needs no bootstrap.

The loom-merged jar is at
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/<version>/minecraft-merged-*.jar`.
Note that `./gradlew build` regenerates it; a stale path from a previous session will fail to load.

## Architecture

```
src/main/java/dev/basehunt/bhclient/
├── BaseHuntingAddon.java        MeteorAddon entrypoint; registers systems, modules, commands, HUD
├── commands/BaseHuntCommand.java  /basehunt (alias /bh)
├── hud/BaseHuntingHud.java
├── modules/                     BaseHunter, NewChunks, StashFinder, PlayerLogger, AutoLog, WebhookNotifier
├── systems/                     StashManager, PlayerTracker — persisted per-server state
└── utils/                       BaritoneHelper, WebhookManager, ServerUtils, WebhookSettings
```

## Conventions and gotchas

**Baritone is compile-only.** It is a `recommends` in `fabric.mod.json`, not a `depends`, so the
addon must load without it. Never reference `baritone.*` types from a class that is initialised
unconditionally — that throws `NoClassDefFoundError` when Baritone is absent. All access goes through
`BaritoneHelper`, which probes for `baritone.api.BaritoneAPI` in a static block and exposes
`AVAILABLE`. Anything needing Baritone must check `AVAILABLE` first and report a clear chat error.

**Systems must be registered before modules load.** `Systems.add(...)` for `StashManager` and
`PlayerTracker` happens before `Modules.get().add(...)` in `onInitialize()`; otherwise their saved
data is discarded on startup.

**Webhook sending is async and rate limited.** `WebhookManager` is a singleton with one daemon
thread draining a bounded queue. Never send from the tick loop synchronously. It dedupes messages
within a 3s window — the dedupe key includes the embed body via `Embed.fingerprint()`, so if you add
a new field to `Embed`, add it to `fingerprint()` too or distinct alerts will collapse into one.

**Mentions ride as message content.** Discord embeds cannot carry a mention, so `WebhookSettings.send`
passes `prefix()` as `content` alongside the embed. Preserve that when adding new send paths.

**Meteor settings** are declared with builders in each module; group them with `settings.createGroup`
and use `.visible(...)` to hide options that do not apply. Settings are the config UI — there is no
separate config file to update.

**Saved state** uses `StashManager`/`PlayerTracker` with NBT serialisation, keyed per server address.

## Version pins

`gradle/libs.versions.toml` holds all of them: Minecraft 1.21.11, Yarn `1.21.11+build.3`, Fabric
Loader 0.18.2, Fabric Loom 1.14-SNAPSHOT, Meteor `1.21.11-SNAPSHOT`, Baritone `1.21.11-SNAPSHOT`.
Meteor and Baritone come from `maven.meteordev.org`. Bumping the Minecraft version generally means
bumping all of these together.
