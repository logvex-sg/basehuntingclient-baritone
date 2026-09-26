package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookManager;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Disconnects automatically when something dangerous happens, and reports it to the webhook first.
 *
 * <p>The webhook is sent <i>before</i> the disconnect because the connection is gone afterwards, and
 * knowing where you were when you logged is the whole point on an anarchy server.
 */
public class AutoLog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("Triggers");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print the reason in chat before disconnecting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cancelBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-baritone")
        .description("Cancel Baritone pathing before disconnecting so it does not keep moving.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Wait this many ticks after a trigger before disconnecting, giving the webhook time to send.")
        .defaultValue(20)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    // Triggers

    private final Setting<Boolean> onHealth = sgTriggers.add(new BoolSetting.Builder()
        .name("low-health")
        .description("Disconnect when health drops below the threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> healthThreshold = sgTriggers.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("Health level that triggers a disconnect.")
        .defaultValue(8)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .visible(onHealth::get)
        .build()
    );

    private final Setting<Boolean> onPlayerNear = sgTriggers.add(new BoolSetting.Builder()
        .name("player-nearby")
        .description("Disconnect when another player comes within the radius.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerRadius = sgTriggers.add(new IntSetting.Builder()
        .name("player-radius")
        .description("Distance at which another player triggers a disconnect.")
        .defaultValue(128)
        .min(8)
        .sliderRange(8, 2048)
        .visible(onPlayerNear::get)
        .build()
    );

    private final Setting<List<String>> playerNames = sgTriggers.add(new StringListSetting.Builder()
        .name("player-names")
        .description("Disconnect when any of these names appears in the tab list. Leave empty to ignore.")
        .build()
    );

    private final Setting<Boolean> onTotemPop = sgTriggers.add(new BoolSetting.Builder()
        .name("totem-pop")
        .description("Disconnect when you pop a totem.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onElytraBreak = sgTriggers.add(new BoolSetting.Builder()
        .name("elytra-break")
        .description("Disconnect when your elytra runs out of durability mid flight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onOutOfFireworks = sgTriggers.add(new BoolSetting.Builder()
        .name("out-of-fireworks")
        .description("Disconnect when you run out of fireworks while flying.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyWhileFlying = sgTriggers.add(new BoolSetting.Builder()
        .name("only-while-flying")
        .description("Only apply the fireworks and elytra triggers while actually gliding.")
        .defaultValue(true)
        .build()
    );

    // Webhook

    private final WebhookSettings webhook = new WebhookSettings(sgWebhook);

    private final Setting<Boolean> notifyOnLog = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-on-disconnect")
        .description("Send a webhook describing why the client logged out.")
        .defaultValue(true)
        .visible(webhook.enabled::get)
        .build()
    );

    private final Setting<Boolean> notifyDanger = sgWebhook.add(new BoolSetting.Builder()
        .name("notify-on-danger")
        .description("Send a webhook as soon as a dangerous condition is noticed, even if the disconnect is delayed.")
        .defaultValue(true)
        .visible(webhook.enabled::get)
        .build()
    );

    private boolean triggered;
    private int countdown;
    private String triggerReason;

    public AutoLog() {
        super(BaseHuntingAddon.CATEGORY, "auto-log", "Automatically disconnects when you are in danger and reports it.");
    }

    @Override
    public void onActivate() {
        triggered = false;
        countdown = 0;
        triggerReason = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (triggered) {
            if (countdown-- <= 0) disconnect();
            return;
        }

        String reason = evaluateTriggers();
        if (reason != null) trigger(reason);
    }

    private String evaluateTriggers() {
        if (onHealth.get() && mc.player.getHealth() <= healthThreshold.get()) {
            return "Health dropped to %s".formatted(mc.player.getHealth());
        }

        if (onPlayerNear.get()) {
            PlayerEntity nearby = nearestPlayer(playerRadius.get());
            if (nearby != null) {
                return "%s is %d blocks away".formatted(
                    nearby.getName().getString(),
                    (int) mc.player.distanceTo(nearby));
            }
        }

        List<String> names = playerNames.get();
        if (!names.isEmpty()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player) continue;

                String name = player.getName().getString();
                for (String watched : names) {
                    if (!watched.isBlank() && name.equalsIgnoreCase(watched.trim())) {
                        return "%s is online".formatted(name);
                    }
                }
            }
        }

        boolean flying = mc.player.isGliding();

        if (onlyWhileFlying.get() && !flying) return null;

        if (onElytraBreak.get() && flying) {
            var chest = mc.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
            if (chest.getItem() != net.minecraft.item.Items.ELYTRA || chest.getMaxDamage() - chest.getDamage() <= 1) {
                return "Elytra is about to break";
            }
        }

        if (onOutOfFireworks.get() && flying && countFireworks() == 0) {
            return "Out of fireworks";
        }

        return null;
    }

    private void trigger(String reason) {
        triggered = true;
        triggerReason = reason;
        countdown = delayTicks.get();

        if (chatFeedback.get()) warning("Auto log: %s.", reason);

        if (cancelBaritone.get()) BaritoneHelper.stop();

        if (notifyDanger.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Danger detected")
                .description(reason)
                .color(0xFF5555)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .field("Dimension", ServerUtils.dimension())
                .field("Health", String.valueOf(mc.player.getHealth()))
                .field("Logging out in", delayTicks.get() / 20.0 + "s")
                .footer("Base Hunting Client"));
        }
    }

    private void disconnect() {
        triggered = false;

        if (notifyOnLog.get() && webhook.isEnabled() && !WebhookSettings.globallyMuted()) {
            webhook.send(new WebhookManager.Embed()
                .title("Auto logged out")
                .description(triggerReason == null ? "Unknown reason" : triggerReason)
                .color(0xFF5555)
                .field("Position", ServerUtils.coordinates())
                .field("Server", ServerUtils.serverAddress())
                .footer("Base Hunting Client"));
        }

        if (chatFeedback.get()) info("Disconnecting.");
        mc.player.networkHandler.getConnection().disconnect(Text.literal("Auto Log: " + (triggerReason == null ? "danger" : triggerReason)));
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!onTotemPop.get() || triggered) return;
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (mc.player == null) return;

        // Status 35 is the totem-of-undying animation; the packet names the entity that popped.
        if (packet.getStatus() != 35) return;
        if (packet.getEntity(mc.world) != mc.player) return;

        trigger("Popped a totem");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        triggered = false;
        triggerReason = null;
    }

    private PlayerEntity nearestPlayer(int radius) {
        double radiusSq = (double) radius * radius;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (mc.player.squaredDistanceTo(player) <= radiusSq) return player;
        }

        return null;
    }

    private int countFireworks() {
        int total = 0;

        for (int slot = 0; slot < mc.player.getInventory().size(); slot++) {
            if (mc.player.getInventory().getStack(slot).getItem() == net.minecraft.item.Items.FIREWORK_ROCKET) {
                total += mc.player.getInventory().getStack(slot).getCount();
            }
        }

        return total;
    }

    public boolean isTriggered() {
        return triggered;
    }
}
