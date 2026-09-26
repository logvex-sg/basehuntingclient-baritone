package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookManager;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reports session lifecycle events to a Discord webhook: logins, logouts, deaths and player sightings.
 *
 * <p>Anarchy bases are found by noticing who is where and when. Because logouts are broadcast to everyone
 * on the server, tracking them lets you find where other players keep their gear: their logout spot is
 * very often a base or a stash.
 */
public class WebhookNotifier extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEvents = settings.createGroup("Events");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Also print notifications in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeCoordinates = sgGeneral.add(new BoolSetting.Builder()
        .name("include-coordinates")
        .description("Include your position in every notification.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeServer = sgGeneral.add(new BoolSetting.Builder()
        .name("include-server")
        .description("Include the server address in every notification.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> muted = sgGeneral.add(new BoolSetting.Builder()
        .name("muted")
        .description("Keep the module running but suppress every outgoing webhook.")
        .defaultValue(false)
        .build()
    );

    // Events

    private final Setting<Boolean> notifyJoin = sgEvents.add(new BoolSetting.Builder()
        .name("notify-join")
        .description("Notify when you join a server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyLeave = sgEvents.add(new BoolSetting.Builder()
        .name("notify-leave")
        .description("Notify when you disconnect.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyPlayerJoin = sgEvents.add(new BoolSetting.Builder()
        .name("notify-player-join")
        .description("Notify when another player joins the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyPlayerLeave = sgEvents.add(new BoolSetting.Builder()
        .name("notify-player-leave")
        .description("Notify when another player leaves, including where they were.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyPlayerNearby = sgEvents.add(new BoolSetting.Builder()
        .name("notify-player-nearby")
        .description("Notify when a player is within the radius below.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> nearbyRadius = sgEvents.add(new IntSetting.Builder()
        .name("nearby-radius")
        .description("Radius for nearby player notifications.")
        .defaultValue(512)
        .min(16)
        .sliderRange(16, 4096)
        .visible(notifyPlayerNearby::get)
        .build()
    );

    private final Setting<Integer> messageCooldown = sgEvents.add(new IntSetting.Builder()
        .name("player-cooldown-seconds")
        .description("Minimum gap between two notifications about the same player.")
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 600)
        .build()
    );

    // Webhook

    private final Setting<String> title = sgWebhook.add(new StringSetting.Builder()
        .name("embed-title-prefix")
        .description("Prefix added to the title of every embed.")
        .defaultValue("")
        .build()
    );

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Map<UUID, Long> lastNotified = new HashMap<>();

    private long sessionStartedAt;
    private int sessionDeaths;

    public WebhookNotifier() {
        super(BaseHuntingAddon.CATEGORY, "webhook-notifier", "Sends session and player events to a Discord webhook.");
    }

    public boolean isMuted() {
        return muted.get();
    }

    /** Fires a plain test embed so a webhook URL can be verified from chat. */
    public void sendTest() {
        if (WebhookSettings.globallyMuted()) {
            error("Webhook output is muted, unmute to send a test.");
            return;
        }

        boolean sent = webhook.send(baseEmbed("Test message", 0x00FF88)
            .description("If you can read this, the webhook is configured correctly.")
            .field("Player", ServerUtils.playerName())
            .field("Position", ServerUtils.coordinates()));

        if (sent) info("Test webhook queued.");
        else error("Webhook is not enabled or the URL is blank.");
    }

    public boolean isSendingAllowed() {
        return !muted.get();
    }

    @Override
    public void onActivate() {
        lastNotified.clear();
        sessionStartedAt = java.lang.System.currentTimeMillis();
        sessionDeaths = 0;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        sessionStartedAt = java.lang.System.currentTimeMillis();

        if (!notifyJoin.get()) return;

        WebhookManager.Embed embed = baseEmbed("Joined server", 0x00FF88)
            .field("Player", ServerUtils.playerName());

        if (includeCoordinates.get()) embed.field("Position", ServerUtils.coordinates());
        embed.field("Dimension", ServerUtils.dimension());

        send(embed);

        if (chatFeedback.get()) info("Webhook session started.");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!notifyLeave.get()) return;

        long seconds = (java.lang.System.currentTimeMillis() - sessionStartedAt) / 1000L;

        WebhookManager.Embed embed = baseEmbed("Disconnected", 0xFF5555)
            .field("Player", ServerUtils.playerName())
            .field("Session length", formatDuration(seconds));

        if (includeCoordinates.get()) embed.field("Last position", ServerUtils.coordinates());

        send(embed);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet) {
            if (!notifyPlayerJoin.get()) return;
            if (!packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;

            for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
                if (entry.profile() == null) continue;

                String name = entry.profile().name();
                if (mc.player != null && name.equals(mc.player.getGameProfile().name())) continue;

                UUID id = entry.profileId();
                if (onCooldown(id)) continue;

                PlayerTracker tracker = PlayerTracker.get();
                if (tracker != null && mc.player != null) {
                    tracker.track(id, name, (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ());
                    tracker.markLoggedIn(id);
                }

                WebhookManager.Embed embed = baseEmbed("Player joined", 0x00C8FF)
                    .field("Player", name)
                    .field("Latency", entry.latency() + " ms");

                if (includeCoordinates.get()) embed.field("Our position", ServerUtils.coordinates());

                send(embed);
            }

            return;
        }

        if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            if (!notifyPlayerLeave.get()) return;

            for (UUID id : packet.profileIds()) {
                if (onCooldown(id)) continue;

                PlayerTracker tracker = PlayerTracker.get();
                PlayerTracker.Entry entry = tracker == null ? null : tracker.get(id);

                if (tracker != null && mc.player != null) {
                    tracker.markLoggedOut(id, (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ());
                }

                // The logger module owns the logout messaging, so let it build the notification.
                PlayerLogger logger = Modules.get().get(PlayerLogger.class);
                if (logger != null && logger.isActive() && entry != null) {
                    logger.recordLogout(entry, new BlockPos(
                        entry.logoutX, entry.logoutY, entry.logoutZ));
                }

                String name = entry != null ? entry.name : id.toString();

                WebhookManager.Embed embed = baseEmbed("Player left", 0xFFAA00)
                    .field("Player", name);

                if (entry != null) {
                    embed.field("Last seen", entry.lastCoords());
                    embed.field("First seen", entry.firstCoords());
                }

                if (includeCoordinates.get()) embed.field("Our position", ServerUtils.coordinates());

                send(embed);
            }
        }
    }

    private boolean onCooldown(UUID id) {
        if (messageCooldown.get() <= 0) return false;

        long now = java.lang.System.currentTimeMillis();
        Long previous = lastNotified.get(id);
        if (previous != null && now - previous < messageCooldown.get() * 1000L) return true;

        lastNotified.put(id, now);
        return false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!notifyPlayerNearby.get() || mc.player == null || mc.world == null) return;

        double radiusSq = (double) nearbyRadius.get() * nearbyRadius.get();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (mc.player.squaredDistanceTo(player) > radiusSq) continue;

            UUID id = player.getUuid();
            if (onCooldown(id)) continue;

            int distance = (int) mc.player.distanceTo(player);

            send(baseEmbed("Player nearby", 0xFFAA00)
                .field("Player", player.getName().getString())
                .field("Distance", distance + " blocks")
                .field("Their position", "%d, %d, %d".formatted(
                    (int) player.getX(), (int) player.getY(), (int) player.getZ()))
                .field("Our position", ServerUtils.coordinates()));

            if (chatFeedback.get()) warning("%s is %d blocks away.", player.getName().getString(), distance);
        }
    }

    /** Called by the other modules when they want to reuse this module's embed formatting. */
    public WebhookManager.Embed baseEmbed(String heading, int color) {
        WebhookManager.Embed embed = new WebhookManager.Embed()
            .title(title.get().isBlank() ? heading : title.get() + " " + heading)
            .color(color)
            .footer("Base Hunting Client");

        if (includeServer.get()) embed.field("Server", ServerUtils.serverAddress());

        return embed;
    }

    private void send(WebhookManager.Embed embed) {
        if (WebhookSettings.globallyMuted()) return;
        webhook.send(embed);
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;

        if (hours > 0) return "%dh %dm".formatted(hours, minutes);
        if (minutes > 0) return "%dm %ds".formatted(minutes, seconds % 60);
        return seconds + "s";
    }

    public int getTrackedPlayerCount() {
        PlayerTracker tracker = PlayerTracker.get();
        return tracker == null ? 0 : tracker.forCurrentServer().size();
    }

    public int getStashCount() {
        StashManager manager = StashManager.get();
        return manager == null ? 0 : manager.forCurrentServer().size();
    }
}
