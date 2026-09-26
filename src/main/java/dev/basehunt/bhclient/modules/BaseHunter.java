package dev.basehunt.bhclient.modules;

import baritone.api.Settings;
import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.BaritoneHelper;
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
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;


import java.util.ArrayList;
import java.util.List;

/**
 * Long range base hunting autopilot built on Baritone's elytra pathfinder.
 *
 * <p>The module only decides <i>where</i> to fly; Baritone handles the actual flight, firework usage and
 * terrain avoidance. That split keeps this code small and means fixes to Baritone's flight logic benefit
 * us automatically.
 *
 * <p>Sweep patterns exist because flying in a straight line forever misses almost everything. A grid or
 * spiral sweep guarantees a bounded search area is covered systematically instead of relying on luck.
 */
public class BaseHunter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRoute = settings.createGroup("Route");
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

    private final Setting<Boolean> acceptBaritoneTerms = sgGeneral.add(new BoolSetting.Builder()
        .name("accept-baritone-terms")
        .description("Automatically accept Baritone's elytra terms so the pathfinder can be used.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> configureBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("configure-baritone")
        .description("Apply sensible Baritone elytra settings on enable.")
        .defaultValue(true)
        .build()
    );

    // Route

    private final Setting<Mode> mode = sgRoute.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How the autopilot picks its next destination.")
        .defaultValue(Mode.Grid)
        .build()
    );

    private final Setting<Double> headingDegrees = sgRoute.add(new DoubleSetting.Builder()
        .name("heading-degrees")
        .description("Compass bearing to fly on. 0 is north, 90 is east.")
        .defaultValue(0)
        .min(-180)
        .max(180)
        .sliderRange(-180, 180)
        .decimalPlaces(0)
        .visible(() -> mode.get() == Mode.Heading)
        .build()
    );

    private final Setting<Integer> legLength = sgRoute.add(new IntSetting.Builder()
        .name("leg-length")
        .description("Length of each straight leg of the sweep, in blocks.")
        .defaultValue(2000)
        .min(128)
        .sliderRange(128, 30000)
        .visible(() -> mode.get() == Mode.Heading || mode.get() == Mode.Grid)
        .build()
    );

    private final Setting<Integer> gridSpacing = sgRoute.add(new IntSetting.Builder()
        .name("grid-spacing")
        .description("Distance between parallel sweep legs. Smaller covers more thoroughly, slower.")
        .defaultValue(512)
        .min(64)
        .sliderRange(64, 4096)
        .visible(() -> mode.get() == Mode.Grid)
        .build()
    );

    private final Setting<Integer> spiralRadius = sgRoute.add(new IntSetting.Builder()
        .name("spiral-radius")
        .description("Maximum radius for the spiral pattern before it restarts at the centre.")
        .defaultValue(5000)
        .min(256)
        .sliderRange(256, 100000)
        .visible(() -> mode.get() == Mode.Spiral)
        .build()
    );

    private final Setting<Integer> spiralStep = sgRoute.add(new IntSetting.Builder()
        .name("spiral-step")
        .description("How much the spiral radius grows per revolution.")
        .defaultValue(500)
        .min(64)
        .sliderRange(64, 5000)
        .visible(() -> mode.get() == Mode.Spiral)
        .build()
    );

    private final Setting<Integer> targetX = sgRoute.add(new IntSetting.Builder()
        .name("target-x")
        .description("X coordinate to fly to when using Coordinates mode.")
        .defaultValue(0)
        .noSlider()
        .visible(() -> mode.get() == Mode.Coordinates)
        .build()
    );

    private final Setting<Integer> targetZ = sgRoute.add(new IntSetting.Builder()
        .name("target-z")
        .description("Z coordinate to fly to when using Coordinates mode.")
        .defaultValue(0)
        .noSlider()
        .visible(() -> mode.get() == Mode.Coordinates)
        .build()
    );

    private final Setting<Integer> waypointArrivalRadius = sgRoute.add(new IntSetting.Builder()
        .name("waypoint-arrival-radius")
        .description("Distance at which a waypoint counts as reached.")
        .defaultValue(96)
        .min(8)
        .sliderRange(8, 512)
        .visible(() -> mode.get() == Mode.Waypoints)
        .build()
    );

    private final Setting<Boolean> reverseOnArrival = sgRoute.add(new BoolSetting.Builder()
        .name("reverse-on-arrival")
        .description("Fly the sweep back the way it came instead of teleporting the pattern forward.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.Heading || mode.get() == Mode.Grid)
        .build()
    );

    private final Setting<Double> fireworksKeep = sgRoute.add(new DoubleSetting.Builder()
        .name("fireworks-to-keep")
        .description("Land and stop when fewer than this many fireworks are left. 0 disables the check.")
        .defaultValue(16)
        .min(0)
        .sliderRange(0, 256)
        .decimalPlaces(0)
        .build()
    );

    // Safety

    private final Setting<Boolean> pauseNearPlayers = sgSafety.add(new BoolSetting.Builder()
        .name("pause-near-players")
        .description("Stop flying while another player is nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerRadius = sgSafety.add(new IntSetting.Builder()
        .name("player-radius")
        .description("Distance at which another player counts as nearby.")
        .defaultValue(256)
        .min(16)
        .sliderRange(16, 2048)
        .visible(pauseNearPlayers::get)
        .build()
    );

    private final Setting<Boolean> resumeAfterPlayersLeave = sgSafety.add(new BoolSetting.Builder()
        .name("resume-after-players-leave")
        .description("Automatically resume the route once nearby players are gone.")
        .defaultValue(true)
        .visible(pauseNearPlayers::get)
        .build()
    );

    private final Setting<Boolean> stopOnStash = sgSafety.add(new BoolSetting.Builder()
        .name("stop-on-stash")
        .description("Halt the autopilot when the stash finder reports a stash.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logOutOnArrival = sgSafety.add(new BoolSetting.Builder()
        .name("log-out-on-arrival")
        .description("Disconnect instead of idling when the route finishes. Useful for unattended runs.")
        .defaultValue(false)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw the current leg and destination.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the destination marker is drawn.")
        .defaultValue(ShapeMode.Lines)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> destinationColor = sgRender.add(new ColorSetting.Builder()
        .name("destination-color")
        .description("Colour of the destination marker.")
        .defaultValue(new SettingColor(0, 255, 128, 60))
        .visible(render::get)
        .build()
    );

    // Webhook

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Setting<Integer> progressInterval = sgWebhook.add(new IntSetting.Builder()
        .name("progress-interval-minutes")
        .description("Send a progress report this often. 0 disables progress reports.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 120)
        .visible(webhook.enabled::get)
        .build()
    );

    private final Setting<Boolean> notifyStateChanges = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-state-changes")
        .description("Send a webhook when the autopilot starts, stops or finishes.")
        .defaultValue(true)
        .visible(webhook.enabled::get)
        .build()
    );

    private final Setting<Boolean> notifyOnPlayer = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-on-player")
        .description("Send a webhook when another player comes into range.")
        .defaultValue(true)
        .visible(webhook.enabled::get)
        .build()
    );

    private final List<BlockPos> waypoints = new ArrayList<>();

    private BlockPos currentDestination;
    private BlockPos legStart;

    private int gridLegIndex;
    private boolean gridReversed;
    private int spiralLegIndex;
    private double travelledBlocks;

    private boolean paused;
    private long lastProgressTime;
    private long lastResumeAttempt;
    private boolean waitingForBaritone;
    private boolean started;



    public BaseHunter() {
        super(BaseHuntingAddon.CATEGORY, "base-hunter", "Baritone powered elytra autopilot for sweeping anarchy servers for bases.");
    }

    @Override
    public void onActivate() {
        waypoints.clear();
        currentDestination = null;
        legStart = null;
        gridLegIndex = 0;
        gridReversed = false;
        spiralLegIndex = 0;
        travelledBlocks = 0;
        paused = false;
        waitingForBaritone = false;


        long now = java.lang.System.currentTimeMillis();
        lastProgressTime = now;


        if (!BaritoneHelper.AVAILABLE) {
            error("Baritone is not installed. Base Hunter needs Baritone to fly.");
            toggle();
            return;
        }

        if (configureBaritone.get()) applyBaritoneSettings();
        if (acceptBaritoneTerms.get()) acceptTerms();

        if (autoStart.get()) {
            if (mc.player == null || mc.world == null) {
                waitingForBaritone = true;
                if (chatFeedback.get()) info("Waiting for a world to load before starting.");
            } else {
                startRoute();
            }
        }
    }

    @Override
    public void onDeactivate() {
        BaritoneHelper.stop();
        currentDestination = null;
        legStart = null;

        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter stopped")
                .color(0xFF5555)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Distance flown", "%,d blocks".formatted((long) travelledBlocks))
                .footer("Base Hunting Client"));
        }
    }

    private void applyBaritoneSettings() {
        Settings settings = BaritoneHelper.settings();
        if (settings == null) return;

        settings.elytraAutoSwap.value = true;
        settings.elytraAllowEmergencyLand.value = true;
        settings.elytraConserveFireworks.value = true;
        settings.elytraAutoJump.value = true;
        settings.elytraFreeLook.value = true;
        settings.elytraChatSpam.value = false;
    }

    private void acceptTerms() {
        Settings settings = BaritoneHelper.settings();
        if (settings == null || settings.elytraTermsAccepted.value) return;

        settings.elytraTermsAccepted.value = true;
        if (chatFeedback.get()) info("Accepted Baritone's elytra terms on your behalf.");
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!waitingForBaritone) return;

        waitingForBaritone = false;
        startRoute();
    }

    /** Starts (or restarts) the sweep using the current settings. Used by the command and auto-start. */
    public void start() {
        if (mc.player == null || mc.world == null) {
            waitingForBaritone = true;
            return;
        }

        startRoute();
    }

    private void startRoute() {
        if (mc.player == null || mc.world == null) return;

        started = true;
        travelledBlocks = 0;
        legStart = mc.player.getBlockPos();

        if (mode.get() == Mode.Waypoints && waypoints.isEmpty()) {
            // Fall back to stored stashes so "fly to my bases" works without extra setup.
            StashManager manager = StashManager.get();
            if (manager != null) {
                for (StashManager.Stash stash : manager.forCurrentServer()) waypoints.add(stash.pos);
            }
        }

        if (mode.get() == Mode.Waypoints && waypoints.isEmpty()) {
            error("No waypoints available. Use %s or find a stash first.", commandHint("waypoint add"));
            toggle();
            return;
        }

        if (chatFeedback.get()) info("Starting base hunt in (highlight)%s (default)mode.", mode.get());
        if (notifyStateChanges.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter started")
                .color(0x00C8FF)
                .field("Mode", mode.get().name())
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Dimension", ServerUtils.dimension())
                .footer("Base Hunting Client"));
        }

        advanceToNextDestination(true);
    }

    private String commandHint(String suffix) {
        String prefix = Config.get().prefix.get();
        return prefix + "basehunt " + suffix;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (!BaritoneHelper.AVAILABLE) return;

        // Nothing should fly until the route is actually started, otherwise enabling the module with
        // auto-start off would immediately begin sweeping.
        if (!started) return;

        handlePlayerSafety();

        if (paused) {
            if (resumeAfterPlayersLeave.get() && !anyPlayerNearby()) {
                paused = false;
                if (chatFeedback.get()) info("Players gone, resuming route.");
                advanceToNextDestination(true);
            } else {
                BaritoneHelper.stop();
                return;
            }
        }

        if (currentDestination == null) {
            advanceToNextDestination(false);
            return;
        }

        if (hasArrived()) {
            onDestinationReached();
            return;
        }

        if (!fireworksAvailable()) {
            if (chatFeedback.get()) warning("Out of fireworks, stopping the hunt.");
            stopAndMaybeDisconnect("Out of fireworks");
            return;
        }

        if (!BaritoneHelper.isElytraLoaded()) {
            BaritoneHelper.elytraPathTo(currentDestination);
            if (chatFeedback.get()) info("Flying to (highlight)%d, %d (default)(%d blocks away).",
                currentDestination.getX(), currentDestination.getZ(), distanceTo(currentDestination));
            return;
        }

        trackTravel();
        sendProgressIfDue();
    }

    private void handlePlayerSafety() {
        if (!pauseNearbyPlayers()) return;
        if (paused) return;

        PlayerEntity nearest = nearestPlayer();
        if (nearest == null) return;

        paused = true;
        BaritoneHelper.stop();

        String name = nearest.getName().getString();
        int distance = (int) mc.player.distanceTo(nearest);

        if (chatFeedback.get()) warning("Pausing, %s is %d blocks away.", name, distance);

        if (notifyOnPlayer.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Player nearby")
                .description("Base Hunter paused to avoid detection.")
                .color(0xFFAA00)
                .field("Player", name)
                .field("Distance", distance + " blocks")
                .field("Their position", "%d, %d, %d".formatted(
                    (int) nearest.getX(), (int) nearest.getY(), (int) nearest.getZ()))
                .field("Our position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .footer("Base Hunting Client"));
        }
    }

    private boolean pauseNearbyPlayers() {
        return pauseNearPlayers.get() && !mc.player.isCreative();
    }

    private boolean anyPlayerNearby() {
        return nearestPlayer() != null;
    }

    private PlayerEntity nearestPlayer() {
        double radiusSq = (double) playerRadius.get() * playerRadius.get();
        PlayerEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;

            double distance = mc.player.squaredDistanceTo(player);
            if (distance > radiusSq) continue;

            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    private void trackTravel() {
        BlockPos position = mc.player.getBlockPos();
        if (legStart == null) {
            legStart = position;
            return;
        }

        travelledBlocks += Math.sqrt(position.getSquaredDistance(legStart));
        legStart = position;
    }

    private void sendProgressIfDue() {
        if (progressInterval.get() <= 0 || !webhook.isEnabled() || WebhookSettings.globallyMuted()) return;

        long now = java.lang.System.currentTimeMillis();
        if (now - lastProgressTime < progressInterval.get() * 60_000L) return;

        lastProgressTime = now;

        webhook.send(new WebhookManager.Embed()
            .title("Base Hunter progress")
            .color(0x00C8FF)
            .field("Position", ServerUtils.coordinates())
            .field("Server", ServerUtils.serverAddress())
            .field("Destination", "%d, %d".formatted(currentDestination.getX(), currentDestination.getZ()))
            .field("Remaining", distanceTo(currentDestination) + " blocks")
            .field("Flown this run", "%,d blocks".formatted((long) travelledBlocks))
            .field("Fireworks", String.valueOf(countFireworks()))
            .field("New chunks found", String.valueOf(countNewChunks()))
            .footer("Base Hunting Client"));
    }

    private int countFireworks() {
        int total = 0;

        for (int slot = 0; slot < mc.player.getInventory().size(); slot++) {
            if (mc.player.getInventory().getStack(slot).getItem() == Items.FIREWORK_ROCKET) {
                total += mc.player.getInventory().getStack(slot).getCount();
            }
        }

        return total;
    }

    private int countNewChunks() {
        NewChunks newChunks = Modules.get().get(NewChunks.class);
        return newChunks == null ? 0 : newChunks.getTrackedCount();
    }

    private boolean fireworksAvailable() {
        if (fireworksKeep.get() <= 0) return true;
        return countFireworks() >= fireworksKeep.get();
    }

    private boolean hasArrived() {
        if (currentDestination == null) return true;

        if (mode.get() == Mode.Waypoints) {
            return distanceTo(currentDestination) <= waypointArrivalRadius.get();
        }

        return distanceTo(currentDestination) <= 32;
    }

    private int distanceTo(BlockPos pos) {
        double dx = pos.getX() - mc.player.getX();
        double dz = pos.getZ() - mc.player.getZ();
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    private void onDestinationReached() {
        if (chatFeedback.get()) info("Reached (highlight)%d, %d(default).", currentDestination.getX(), currentDestination.getZ());

        if (mode.get() == Mode.Waypoints && !waypoints.isEmpty()) {
            waypoints.remove(0);
            if (waypoints.isEmpty()) {
                stopAndMaybeDisconnect("All waypoints visited");
                return;
            }
        }

        advanceToNextDestination(false);
    }

    private void stopAndMaybeDisconnect(String reason) {
        if (chatFeedback.get()) info("%s, stopping the hunt.", reason);

        if (webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Base Hunter finished")
                .description(reason)
                .color(0x00C8FF)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Flown this run", "%,d blocks".formatted((long) travelledBlocks))
                .footer("Base Hunting Client"));
        }

        currentDestination = null;
        BaritoneHelper.stop();

        if (logOutOnArrival.get()) {
            if (chatFeedback.get()) info("Disconnecting.");
            mc.player.networkHandler.getConnection().disconnect(Text.literal("Base Hunter: " + reason));
        } else {
            toggle();
        }
    }

    /** Picks the next destination for the active sweep pattern. */
    private void advanceToNextDestination(boolean restart) {
        if (mc.player == null) return;

        int playerX = mc.player.getBlockPos().getX();
        int playerZ = mc.player.getBlockPos().getZ();

        if (restart) {
            legStart = mc.player.getBlockPos();

        }

        currentDestination = switch (mode.get()) {
            case Heading -> headingDestination(playerX, playerZ);
            case Coordinates -> new BlockPos(targetX.get(), 64, targetZ.get());
            case Grid -> gridDestination(playerX, playerZ);
            case Spiral -> spiralDestination(playerX, playerZ);
            case Waypoints -> waypoints.isEmpty() ? null : waypoints.get(0);
        };

        if (currentDestination == null) {
            stopAndMaybeDisconnect("No destination available");
            return;
        }

        BaritoneHelper.elytraPathTo(currentDestination);
    }

    private BlockPos headingDestination(int playerX, int playerZ) {
        double radians = Math.toRadians(headingDegrees.get());
        int dx = (int) Math.round(Math.sin(radians) * legLength.get());
        int dz = (int) Math.round(-Math.cos(radians) * legLength.get());

        return new BlockPos(playerX + dx, 64, playerZ + dz);
    }

    /**
     * Boustrophedon sweep: fly a leg, shift sideways by the grid spacing, fly the opposite direction.
     * Reversing instead of jumping means no chunk along the edge is skipped.
     */
    private BlockPos gridDestination(int playerX, int playerZ) {
        int spacing = gridSpacing.get();
        int length = legLength.get();

        if (gridReversed) {
            gridLegIndex++;
            gridReversed = false;
        } else {
            gridReversed = true;
        }

        int direction = gridReversed ? 1 : -1;
        int offset = gridLegIndex * spacing * direction;

        double radians = Math.toRadians(headingDegrees.get());
        int alongX = (int) Math.round(Math.sin(radians) * length);
        int alongZ = (int) Math.round(-Math.cos(radians) * length);
        int acrossX = (int) Math.round(Math.cos(radians) * offset);
        int acrossZ = (int) Math.round(Math.sin(radians) * offset);

        return new BlockPos(playerX + alongX + acrossX, 64, playerZ + alongZ + acrossZ);
    }

    /** Archimedean spiral: each leg is one step longer, rotating the bearing by a fixed amount. */
    private BlockPos spiralDestination(int playerX, int playerZ) {
        spiralLegIndex++;

        double radius = Math.min(spiralLegIndex * (double) spiralStep.get(), spiralRadius.get());
        if (radius >= spiralRadius.get()) spiralLegIndex = 0;

        double angle = Math.toRadians(spiralLegIndex * 60.0);

        int x = playerX + (int) Math.round(Math.cos(angle) * radius);
        int z = playerZ + (int) Math.round(Math.sin(angle) * radius);

        return new BlockPos(x, 64, z);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null || currentDestination == null) return;

        event.renderer.box(
            currentDestination.getX() - 4, currentDestination.getY() - 4, currentDestination.getZ() - 4,
            currentDestination.getX() + 4, currentDestination.getY() + 4, currentDestination.getZ() + 4,
            destinationColor.get(), destinationColor.get(), shapeMode.get(), 0
        );
    }

    public void setWaypoints(List<BlockPos> positions) {
        waypoints.clear();
        waypoints.addAll(positions);
    }

    public void addWaypoint(BlockPos pos) {
        waypoints.add(pos);
    }

    public void clearWaypoints() {
        waypoints.clear();
    }

    public int getWaypointCount() {
        return waypoints.size();
    }

    public BlockPos getCurrentDestination() {
        return currentDestination;
    }

    public double getTravelledBlocks() {
        return travelledBlocks;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getModeName() {
        return mode.get().name();
    }

    public enum Mode {
        Heading,
        Coordinates,
        Grid,
        Spiral,
        Waypoints
    }
}
