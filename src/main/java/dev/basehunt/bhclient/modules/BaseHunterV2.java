package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.ElytraController;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.FlightPlanner;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookManager;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Second generation base hunter: no fireworks, faster and reactive.
 *
 * <p>Where {@link BaseHunter} flies a fixed pattern using Baritone's elytra pathfinder, this module flies
 * itself. That difference is what removes the firework requirement: Baritone's elytra pathfinder plans
 * around rocket propulsion and lands when rockets run out, while {@link ElytraController} drives Meteor's
 * ElytraFly in Bounce mode, which recasts the elytra every tick and sustains flight indefinitely without
 * consuming rockets. A flight is therefore limited by elytra durability alone, and
 * {@link ElytraManager} swaps in spares so any durability of elytra will do.
 *
 * <p>It is faster and smarter because it does not commit to a blind sweep:
 *
 * <ul>
 *   <li>targets come from {@link NewChunks} detections, clustered and ranked, so it flies at fresh terrain
 *       rather than empty ocean;</li>
 *   <li>{@link FlightPlanner} orders those targets into a nearest-neighbour tour, so it stops crossing its
 *       own path;</li>
 *   <li>it adapts its cruise altitude to the terrain underneath instead of holding one fixed height;</li>
 *   <li>when nothing has been found for a while it pushes a frontier waypoint outward to keep exploring.</li>
 * </ul>
 *
 * <p>Baritone is not required for any of this. It is used opportunistically, when installed, purely to
 * pre-generate chunks along the next leg so the client is not flying into unloaded terrain.
 */
public class BaseHunterV2 extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print autopilot progress and events in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoStart = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-start")
        .description("Begin flying as soon as the module is enabled and you are in a world.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> stopV1 = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-base-hunter-v1")
        .description("Disable Base Hunter when this module starts, so two autopilots never fight.")
        .defaultValue(true)
        .build()
    );

    // Flight

    private final Setting<Double> cruiseY = sgFlight.add(new DoubleSetting.Builder()
        .name("cruise-y")
        .description("Preferred flight altitude. Terrain avoidance overrides this when the ground is higher.")
        .defaultValue(180)
        .min(-64)
        .max(2000)
        .sliderRange(-64, 400)
        .build()
    );

    private final Setting<Boolean> terrainFollow = sgFlight.add(new BoolSetting.Builder()
        .name("terrain-follow")
        .description("Raise altitude automatically when terrain below is higher than the cruise altitude.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> clearance = sgFlight.add(new IntSetting.Builder()
        .name("clearance")
        .description("Blocks of clearance kept above terrain when terrain-following.")
        .defaultValue(40)
        .min(8)
        .sliderRange(8, 160)
        .visible(terrainFollow::get)
        .build()
    );

    private final Setting<Integer> elytraReplaceDurability = sgFlight.add(new IntSetting.Builder()
        .name("elytra-replace-durability")
        .description("Swap in a spare elytra when the worn one drops to this much durability. 0 disables.")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> useBaritonePregen = sgFlight.add(new BoolSetting.Builder()
        .name("baritone-pregen")
        .description("Use Baritone to pre-generate chunks along the next leg, if Baritone is installed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> pregenLegLength = sgFlight.add(new IntSetting.Builder()
        .name("pregen-leg-length")
        .description("How far ahead to pre-generate.")
        .defaultValue(512)
        .min(128)
        .sliderRange(128, 4096)
        .visible(() -> useBaritonePregen.get())
        .build()
    );

    // Targets

    private final Setting<Integer> clusterRadius = sgTargets.add(new IntSetting.Builder()
        .name("cluster-radius")
        .description("Chunks within this many chunks of each other count as one target.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargets.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many clustered targets to keep in the queue at once.")
        .defaultValue(32)
        .min(1)
        .sliderRange(1, 256)
        .build()
    );

    private final Setting<Boolean> nearestFirst = sgTargets.add(new BoolSetting.Builder()
        .name("nearest-first")
        .description("Reorder targets into a nearest-neighbour tour so the route does not criss-cross.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> priorityRadius = sgTargets.add(new IntSetting.Builder()
        .name("interest-radius")
        .description("Clusters within this many blocks of a known stash or logout spot are prioritised.")
        .defaultValue(256)
        .min(0)
        .sliderRange(0, 2048)
        .build()
    );

    private final Setting<Integer> frontierStep = sgTargets.add(new IntSetting.Builder()
        .name("frontier-step")
        .description("How far past the furthest known chunk to push when nothing new is being found.")
        .defaultValue(2000)
        .min(256)
        .sliderRange(256, 20000)
        .build()
    );

    private final Setting<Integer> frontierAfterSeconds = sgTargets.add(new IntSetting.Builder()
        .name("frontier-after-seconds")
        .description("Seconds without a new detection before expanding the frontier. 0 disables.")
        .defaultValue(90)
        .min(0)
        .sliderRange(0, 900)
        .build()
    );

    // Safety

    private final Setting<Boolean> pauseNearPlayers = sgSafety.add(new BoolSetting.Builder()
        .name("pause-near-players")
        .description("Pause the route when another player comes close.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> playerRadius = sgSafety.add(new IntSetting.Builder()
        .name("player-radius")
        .description("Distance at which another player triggers a pause.")
        .defaultValue(256)
        .min(16)
        .sliderRange(16, 1024)
        .visible(pauseNearPlayers::get)
        .build()
    );

    private final Setting<Boolean> resumeAfterPlayersLeave = sgSafety.add(new BoolSetting.Builder()
        .name("resume-after-players-leave")
        .description("Resume the route once no players are within range.")
        .defaultValue(true)
        .visible(pauseNearPlayers::get)
        .build()
    );

    private final Setting<Boolean> landOnArrival = sgSafety.add(new BoolSetting.Builder()
        .name("land-on-arrival")
        .description("Stop and land when the target queue is exhausted.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logOutWhenDone = sgSafety.add(new BoolSetting.Builder()
        .name("log-out-when-done")
        .description("Disconnect instead of just stopping when the hunt finishes.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> abortWithoutElytra = sgSafety.add(new BoolSetting.Builder()
        .name("abort-without-elytra")
        .description("Stop when no elytra remain at all. Any durability of elytra is accepted.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the current target and queued targets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the target boxes are drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Colour of the active target.")
        .defaultValue(new SettingColor(0, 200, 255))
        .build()
    );

    private final Setting<SettingColor> queueColor = sgRender.add(new ColorSetting.Builder()
        .name("queue-color")
        .description("Colour of queued targets.")
        .defaultValue(new SettingColor(120, 120, 255, 140))
        .build()
    );

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Hide target boxes further away than this.")
        .defaultValue(4096)
        .min(128)
        .sliderRange(128, 20000)
        .visible(render::get)
        .build()
    );

    // Webhook

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Setting<Boolean> notifyStateChanges = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-state-changes")
        .description("Send start, stop and completion messages.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> progressIntervalMinutes = sgWebhook.add(new IntSetting.Builder()
        .name("progress-interval-minutes")
        .description("Minutes between progress reports. 0 disables.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 60)
        .build()
    );

    // State

    private final FlightPlanner planner = new FlightPlanner();
    private final Deque<FlightPlanner.Target> queue = new ArrayDeque<>();

    private FlightPlanner.Target current;
    private boolean started;
    private boolean waitingForWorld;
    private boolean paused;
    private double travelledBlocks;
    private double lastX, lastZ;
    private long lastProgressWebhook;
    private long startedAt;
    private int targetsVisited;
    private int elytraSwaps;
    private boolean warnedNotFlying;

    public BaseHunterV2() {
        super(BaseHuntingAddon.CATEGORY, "base-hunter-v2",
            "Firework-free elytra base hunter. Flies itself and reacts to new chunk detections.");
    }

    @Override
    public void onActivate() {
        if (stopV1.get()) {
            BaseHunter v1 = Modules.get().get(BaseHunter.class);
            if (v1 != null && v1.isActive()) v1.disable();
        }

        if (!ElytraController.available()) {
            error("Meteor's Elytra Fly module is unavailable, cannot fly.");
            toggle();
            return;
        }

        planner.clear();
        queue.clear();
        current = null;
        started = false;
        paused = false;
        travelledBlocks = 0;
        targetsVisited = 0;
        elytraSwaps = 0;
        lastProgressWebhook = System.currentTimeMillis();

        if (autoStart.get()) {
            if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) {
                waitingForWorld = true;
                if (chatFeedback.get()) info("Waiting for a world to load before starting.");
            } else {
                start();
            }
        }
    }

    @Override
    public void onDeactivate() {
        ElytraController.disable();
        current = null;
        started = false;

        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter V2 stopped")
                .color(0xFF5555)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Flown", "%,d blocks".formatted((long) travelledBlocks))
                .field("Targets visited", String.valueOf(targetsVisited))
                .footer("Base Hunting Client"));
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!waitingForWorld) return;

        waitingForWorld = false;
        start();
    }

    /** Begins the hunt. Called by auto-start and the {@code /bh v2 start} command. */
    public void start() {
        if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) {
            waitingForWorld = true;
            return;
        }

        if (!ElytraManager.hasElytraEquipped() && !ElytraManager.equipBest()) {
            error("No elytra found. Any durability of elytra works, but you need at least one.");
            if (abortWithoutElytra.get()) toggle();
            return;
        }

        started = true;
        startedAt = System.currentTimeMillis();
        travelledBlocks = 0;
        lastX = ServerUtils.mc().player.getX();
        lastZ = ServerUtils.mc().player.getZ();

        boolean ok = ElytraController.enable(effectiveCruiseY(), true);

        if (!ok) {
            error("Could not start Meteor's Elytra Fly.");
            toggle();
            return;
        }

        if (chatFeedback.get()) {
            info("Base Hunter V2 started. Elytra: (highlight)%s(default).", ElytraManager.status());
            info("Fireworks are not required in this mode.");
        }

        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter V2 started")
                .description("Firework-free elytra sweep.")
                .color(0x00C8FF)
                .field("Elytra", ElytraManager.status())
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Dimension", ServerUtils.dimension())
                .field("Baritone", dev.basehunt.bhclient.utils.BaritoneHelper.AVAILABLE ? "available" : "not installed")
                .footer("Base Hunting Client"));
        }

        refreshQueue();
        if (queue.isEmpty()) pushFrontier();
        advance();
    }

    /** Stops flying but leaves the module enabled. */
    public void stop() {
        started = false;
        current = null;
        queue.clear();
        ElytraController.disable();

        if (chatFeedback.get()) info("Base Hunter V2 stopped.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) return;
        if (!started) return;

        handleElytra();
        handleSafety();

        if (paused) return;

        trackTravel();
        sendProgressIfDue();

        if (current == null) {
            advance();
            return;
        }

        if (ElytraController.arrivedHorizontally(current.x(), current.z())) {
            onTargetReached();
            return;
        }

        ElytraController.steerToGround(current.x(), current.z(), effectiveCruiseY());

        if (!ElytraController.isFlying()) {
            // Bounce mode needs a moment on the ground to launch; only complain once it has had time.
            if (System.currentTimeMillis() - startedAt > 8000 && !warnedNotFlying) {
                warnedNotFlying = true;
                warning("Not gliding yet. Make sure you have an elytra on and space above you.");
            }
        }
    }

    private void handleElytra() {
        if (ElytraManager.hasElytraEquipped()) {
            if (ElytraManager.replaceIfBelow(elytraReplaceDurability.get())) {
                elytraSwaps++;
                if (chatFeedback.get()) info("Swapped in a fresh elytra. %s", ElytraManager.status());
            }
            return;
        }

        // The worn elytra broke mid-flight. Anything in the inventory is better than nothing.
        if (ElytraManager.equipBest()) {
            elytraSwaps++;
            if (chatFeedback.get()) warning("Elytra broke, equipped a spare. %s", ElytraManager.status());
            return;
        }

        if (abortWithoutElytra.get()) {
            stopAndMaybeDisconnect("No elytra left");
        }
    }

    private void handleSafety() {
        if (!pauseNearPlayers.get()) return;
        if (ServerUtils.mc().player.isCreative()) return;

        PlayerEntity nearest = nearestPlayer();

        if (nearest != null) {
            if (!paused) {
                paused = true;
                ElytraController.disable();

                String name = nearest.getName().getString();
                int distance = (int) ServerUtils.mc().player.distanceTo(nearest);

                if (chatFeedback.get()) warning("Pausing, %s is %d blocks away.", name, distance);
                planner.noteProgress();
            }

            return;
        }

        if (paused && resumeAfterPlayersLeave.get()) {
            paused = false;
            if (chatFeedback.get()) info("Players gone, resuming.");
            ElytraController.enable(effectiveCruiseY(), true);
        }
    }

    private PlayerEntity nearestPlayer() {
        double radiusSq = (double) playerRadius.get() * playerRadius.get();
        PlayerEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PlayerEntity player : ServerUtils.mc().world.getPlayers()) {
            if (player == ServerUtils.mc().player) continue;

            double distance = ServerUtils.mc().player.squaredDistanceTo(player);
            if (distance > radiusSq) continue;

            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    private void trackTravel() {
        double x = ServerUtils.mc().player.getX();
        double z = ServerUtils.mc().player.getZ();

        travelledBlocks += Math.sqrt(Math.pow(x - lastX, 2) + Math.pow(z - lastZ, 2));
        lastX = x;
        lastZ = z;
    }

    /** Rebuilds the target queue from current chunk detections. */
    private void refreshQueue() {
        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (newChunks == null) return;

        Set<Long> detections = newChunks.getNewChunks();
        if (detections.isEmpty()) return;

        int playerX = ServerUtils.mc().player.getBlockPos().getX();
        int playerZ = ServerUtils.mc().player.getBlockPos().getZ();

        List<FlightPlanner.Target> targets = planner.buildTargets(
            detections,
            clusterRadius.get(),
            playerX,
            playerZ,
            maxTargets.get(),
            priorityRadius.get()
        );

        if (targets.isEmpty()) return;

        if (nearestFirst.get()) targets = FlightPlanner.orderByNearest(targets, playerX, playerZ);

        // Keep the target being flown to; only replace what is still queued.
        queue.clear();
        queue.addAll(targets);
    }

    private void advance() {
        if (queue.isEmpty()) {
            if (shouldPushFrontier()) {
                pushFrontier();
            } else {
                refreshQueue();
            }
        }

        current = queue.pollFirst();

        if (current == null) {
            stopAndMaybeDisconnect("No targets left");
            return;
        }

        ElytraController.steerToGround(current.x(), current.z(), effectiveCruiseY());

        if (chatFeedback.get()) {
            info("Target (highlight)%d, %d(default) - %s (%d blocks).",
                current.x(), current.z(), current.reason(),
                (int) ElytraController.horizontalDistanceTo(current.x(), current.z()));
        }
    }

    private boolean shouldPushFrontier() {
        if (frontierAfterSeconds.get() <= 0) return false;
        return planner.millisSinceProgress() >= frontierAfterSeconds.get() * 1000L;
    }

    private void pushFrontier() {
        NewChunks newChunks = Modules.get().get(NewChunks.class);
        Set<Long> known = newChunks == null ? Set.of() : newChunks.getNewChunks();

        FlightPlanner.Target frontier = FlightPlanner.frontier(
            known,
            ServerUtils.mc().player.getBlockPos().getX(),
            ServerUtils.mc().player.getBlockPos().getZ(),
            frontierStep.get()
        );

        if (chatFeedback.get()) info("Nothing new nearby, pushing out to (highlight)%d, %d(default).", frontier.x(), frontier.z());
        queue.addLast(frontier);
    }

    private void onTargetReached() {
        if (current == null) return;

        planner.markVisited(current.x(), current.z());
        targetsVisited++;

        if (chatFeedback.get()) info("Reached (highlight)%d, %d(default).", current.x(), current.z());
        current = null;

        // Rebuild the queue from what has been detected since the last leg, then keep going.
        refreshQueue();
        advance();
    }

    private void stopAndMaybeDisconnect(String reason) {
        if (chatFeedback.get()) info("%s, stopping the hunt.", reason);

        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter V2 finished")
                .description(reason)
                .color(0x00C8FF)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Flown", "%,d blocks".formatted((long) travelledBlocks))
                .field("Targets visited", String.valueOf(targetsVisited))
                .field("Elytra", ElytraManager.status())
                .field("Duration", durationText())
                .footer("Base Hunting Client"));
        }

        started = false;
        current = null;
        queue.clear();

        if (landOnArrival.get()) ElytraController.disable();

        if (logOutWhenDone.get() && ServerUtils.mc().player != null) {
            ServerUtils.mc().player.networkHandler.getConnection()
                .disconnect(Text.literal("Base Hunter V2: " + reason));
        } else if (isActive()) {
            toggle();
        }
    }

    private void sendProgressIfDue() {
        if (progressIntervalMinutes.get() <= 0) return;
        if (!webhook.isEnabled() || WebhookSettings.globallyMuted()) return;

        long now = System.currentTimeMillis();
        if (now - lastProgressWebhook < progressIntervalMinutes.get() * 60_000L) return;

        lastProgressWebhook = now;

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        StashManager stashManager = StashManager.get();

        webhook.send(new WebhookManager.Embed()
            .title("Base Hunter V2 progress")
            .color(0x00C8FF)
            .field("Position", ServerUtils.coordinates())
            .field("Server", ServerUtils.serverAddress())
            .field("Target", current == null ? "none" : "%d, %d".formatted(current.x(), current.z()))
            .field("Queued targets", String.valueOf(queue.size()))
            .field("Flown", "%,d blocks".formatted((long) travelledBlocks))
            .field("Speed", "%.1f b/s".formatted(ElytraController.speedBlocksPerSecond()))
            .field("New chunks", String.valueOf(newChunks == null ? 0 : newChunks.getTrackedCount()))
            .field("Stashes", String.valueOf(stashManager == null ? 0 : stashManager.forCurrentServer().size()))
            .field("Elytra", ElytraManager.status())
            .field("Duration", durationText())
            .footer("Base Hunting Client"));
    }

    private String durationText() {
        long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        return "%dh %dm".formatted(seconds / 3600, (seconds % 3600) / 60);
    }

    /**
     * Cruise altitude adjusted for the terrain ahead. Flying a fixed altitude either clips hills or wastes
     * time climbing over terrain that is not there, so the altitude follows the ground when asked to.
     */
    private double effectiveCruiseY() {
        double base = cruiseY.get();
        if (!terrainFollow.get()) return base;

        if (ServerUtils.mc().player == null || ServerUtils.mc().world == null) return base;

        // Sample a short distance ahead so the climb starts before the hill arrives.
        double headingX = current == null ? 0 : current.x() - ServerUtils.mc().player.getX();
        double headingZ = current == null ? 0 : current.z() - ServerUtils.mc().player.getZ();
        double length = Math.sqrt(headingX * headingX + headingZ * headingZ);

        double lookX = ServerUtils.mc().player.getX();
        double lookZ = ServerUtils.mc().player.getZ();
        if (length > 1.0) {
            lookX += headingX / length * 96.0;
            lookZ += headingZ / length * 96.0;
        }

        int probeX = (int) Math.floor(lookX);
        int probeZ = (int) Math.floor(lookZ);

        int highest = ServerUtils.mc().world.getTopY(Heightmap.Type.MOTION_BLOCKING, probeX, probeZ);
        if (highest + clearance.get() > base) return highest + clearance.get();

        return base;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || ServerUtils.mc().player == null) return;

        if (current != null) drawTarget(event, current, targetColor.get(), 6);

        for (FlightPlanner.Target target : queue) {
            if (target == current) continue;
            drawTarget(event, target, queueColor.get(), 3);
        }
    }

    private void drawTarget(Render3DEvent event, FlightPlanner.Target target, SettingColor color, int radius) {
        double distance = ElytraController.horizontalDistanceTo(target.x(), target.z());
        if (distance > renderDistance.get()) return;

        event.renderer.box(
            target.x() - radius, cruiseY.get() - radius, target.z() - radius,
            target.x() + radius, cruiseY.get() + radius, target.z() + radius,
            color, color, shapeMode.get(), 0
        );
    }

    @Override
    public String getInfoString() {
        if (!started) return "idle";
        return "%d queued, %s".formatted(queue.size(), ElytraManager.hasElytraEquipped() ? "flying" : "no elytra");
    }

    // Accessors for the command and HUD.

    public boolean isStarted() {
        return started;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getQueuedCount() {
        return queue.size();
    }

    public int getTargetsVisited() {
        return targetsVisited;
    }

    public int getElytraSwaps() {
        return elytraSwaps;
    }

    public double getTravelledBlocks() {
        return travelledBlocks;
    }

    public FlightPlanner.Target getCurrentTarget() {
        return current;
    }

    public List<FlightPlanner.Target> getQueuedTargets() {
        return new ArrayList<>(queue);
    }

    /** Forces a queue rebuild, used by {@code /bh v2 refresh}. */
    public void refreshTargets() {
        refreshQueue();
        if (queue.isEmpty()) pushFrontier();
        if (chatFeedback.get()) info("Queue rebuilt: %d targets.", queue.size());
    }
}
