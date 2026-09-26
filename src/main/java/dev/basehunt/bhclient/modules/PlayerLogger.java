package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookManager;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Records where players were last seen and, more usefully, where they logged out.
 *
 * <p>Logout spots are the single most valuable signal when base hunting: players log out at their base or
 * inside their stash, so the spot is a strong hint that something is there. Each recorded spot is also
 * rendered and reported, with a configurable "worth checking" radius so you can fly a small search pattern
 * around it instead of digging blindly.
 */
public class PlayerLogger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print recorded logout spots in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> recordRadius = sgGeneral.add(new IntSetting.Builder()
        .name("record-radius")
        .description("Only record players within this distance. 0 records everyone on the tab list.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 4096)
        .build()
    );

    private final Setting<Integer> expireHours = sgGeneral.add(new IntSetting.Builder()
        .name("expire-hours")
        .description("Stop showing logout spots older than this. 0 keeps them forever.")
        .defaultValue(48)
        .min(0)
        .sliderRange(0, 720)
        .build()
    );

    private final Setting<Boolean> logToChat = sgGeneral.add(new BoolSetting.Builder()
        .name("log-tab-list")
        .description("Log every player seen on the tab list to the console, one line each.")
        .defaultValue(false)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render-logout-spots")
        .description("Draw markers at recorded logout spots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How logout spot markers are drawn.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Marker fill colour.")
        .defaultValue(new SettingColor(255, 0, 128, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Marker outline colour.")
        .defaultValue(new SettingColor(255, 0, 128))
        .visible(render::get)
        .build()
    );

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Only draw markers within this distance.")
        .defaultValue(1024)
        .min(16)
        .sliderRange(16, 8192)
        .visible(render::get)
        .build()
    );

    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from you to each logout spot.")
        .defaultValue(false)
        .visible(render::get)
        .build()
    );

    // Webhook

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Setting<Boolean> notifyOnLogout = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-on-logout")
        .description("Send a webhook whenever a logout spot is recorded.")
        .defaultValue(true)
        .visible(webhook.enabled::get)
        .build()
    );

    private final Setting<Boolean> notifyNearbyLogout = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-near-logout")
        .description("Send a webhook when you come close to a previously recorded logout spot.")
        .defaultValue(true)
        .visible(webhook.enabled::get)
        .build()
    );

    private final Setting<Integer> nearbyThreshold = sgWebhook.add(new IntSetting.Builder()
        .name("near-threshold")
        .description("Distance at which a recorded logout spot triggers a notification.")
        .defaultValue(256)
        .min(16)
        .sliderRange(16, 4096)
        .visible(() -> webhook.enabled.get() && notifyNearbyLogout.get())
        .build()
    );

    private final List<PlayerTracker.Entry> spots = new ArrayList<>();

    private long lastRefresh;

    public PlayerLogger() {
        super(BaseHuntingAddon.CATEGORY, "player-logger", "Records where players were last seen and where they logged out.");
    }

    @Override
    public void onActivate() {
        spots.clear();
        lastRefresh = 0;
        refresh();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        long now = java.lang.System.currentTimeMillis();

        // Refreshing once a second keeps the marker list in step with the tracker without copying it every tick.
        if (now - lastRefresh > 1000L) {
            lastRefresh = now;
            refresh();
            checkNearbyLogoutSpots();
        }

        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) return;

        int radius = recordRadius.get();
        double radiusSq = radius <= 0 ? Double.MAX_VALUE : (double) radius * radius;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (mc.player.squaredDistanceTo(player) > radiusSq) continue;

            tracker.track(player.getUuid(), player.getName().getString(),
                (int) player.getX(), (int) player.getY(), (int) player.getZ());

            if (logToChat.get()) {
                info("%s at %d, %d, %d", player.getName().getString(),
                    (int) player.getX(), (int) player.getY(), (int) player.getZ());
            }
        }
    }

    private void refresh() {
        spots.clear();

        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) return;

        long cutoff = expireHours.get() <= 0
            ? 0
            : java.lang.System.currentTimeMillis() - expireHours.get() * 3_600_000L;

        String dimension = ServerUtils.dimension();

        for (PlayerTracker.Entry entry : tracker.forCurrentServer()) {
            if (!entry.hasLogoutSpot()) continue;
            if (cutoff > 0 && entry.logoutTime < cutoff) continue;
            if (!entry.logoutDimension.equals(dimension)) continue;

            spots.add(entry);
        }
    }

    private void checkNearbyLogoutSpots() {
        if (!notifyNearbyLogout.get() || !webhook.isEnabled() || WebhookSettings.globallyMuted()) return;
        if (mc.player == null) return;

        double thresholdSq = (double) nearbyThreshold.get() * nearbyThreshold.get();

        for (PlayerTracker.Entry entry : spots) {
            double dx = entry.logoutX - mc.player.getX();
            double dy = entry.logoutY - mc.player.getY();
            double dz = entry.logoutZ - mc.player.getZ();

            if (dx * dx + dy * dy + dz * dz > thresholdSq) continue;
            if (entry.notifiedNearby) continue;

            entry.notifiedNearby = true;

            webhook.send(new WebhookManager.Embed()
                .title("Near a logout spot")
                .color(0xFF0080)
                .field("Player", entry.name)
                .field("Logout spot", entry.logoutCoords())
                .field("Distance", entry.logoutDistanceTo((int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ()) + " blocks")
                .field("Logged out", formatAge(entry.logoutTime))
                .field("Our position", ServerUtils.coordinates())
                .footer("Base Hunting Client"));

            if (chatFeedback.get()) {
                warning("Near %s's logout spot (%s).", entry.name, entry.logoutCoords());
            }
        }
    }

    /** Called by the notifier when a player disappears from the tab list. */
    public void recordLogout(PlayerTracker.Entry entry, BlockPos logoutPos) {
        if (chatFeedback.get()) {
            info("%s logged out at (highlight)%s (default)(%s).",
                entry.name, entry.logoutCoords(), formatAge(entry.logoutTime));
        }

        if (!notifyOnLogout.get() || !webhook.isEnabled() || WebhookSettings.globallyMuted()) return;

        webhook.send(new WebhookManager.Embed()
            .title("Logout recorded")
            .color(0xFF0080)
            .field("Player", entry.name)
            .field("Logout spot", entry.logoutCoords())
            .field("Our position", ServerUtils.coordinates())
            .field("Server", ServerUtils.serverAddress())
            .field("Dimension", entry.logoutDimension)
            .field("Sessions seen", String.valueOf(entry.sessionsSeen))
            .footer("Base Hunting Client"));
    }

    private static String formatAge(long timestamp) {
        if (timestamp <= 0) return "unknown";

        long seconds = (java.lang.System.currentTimeMillis() - timestamp) / 1000L;
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86400) return (seconds / 3600) + "h ago";
        return (seconds / 86400) + "d ago";
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.player == null) return;

        double maxDistanceSq = (double) renderDistance.get() * renderDistance.get();

        for (PlayerTracker.Entry entry : spots) {
            double dx = entry.logoutX - mc.player.getX();
            double dy = entry.logoutY - mc.player.getY();
            double dz = entry.logoutZ - mc.player.getZ();

            if (dx * dx + dy * dy + dz * dz > maxDistanceSq) continue;

            event.renderer.box(
                entry.logoutX - 0.5, entry.logoutY, entry.logoutZ - 0.5,
                entry.logoutX + 0.5, entry.logoutY + 2, entry.logoutZ + 0.5,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0
            );

            if (showTracers.get()) {
                event.renderer.line(
                    mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                    entry.logoutX, entry.logoutY + 1, entry.logoutZ,
                    lineColor.get()
                );
            }
        }
    }

    public int getSpotCount() {
        return spots.size();
    }

    public void clearSpots() {
        PlayerTracker tracker = PlayerTracker.get();
        if (tracker != null) tracker.clearCurrentServer();

        spots.clear();
    }
}
