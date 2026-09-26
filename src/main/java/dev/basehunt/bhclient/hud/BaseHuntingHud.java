package dev.basehunt.bhclient.hud;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.modules.BaseHunter;
import dev.basehunt.bhclient.modules.NewChunks;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import java.util.ArrayList;
import java.util.List;

/** Compact status readout so the important numbers are visible while flying. */
public class BaseHuntingHud extends HudElement {
    public static final HudElementInfo<BaseHuntingHud> INFO = new HudElementInfo<>(
        BaseHuntingAddon.HUD_GROUP,
        "base-hunting",
        "Shows new chunk, stash and autopilot status.",
        BaseHuntingHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showServer = sgGeneral.add(new BoolSetting.Builder()
        .name("show-server")
        .description("Show the server address.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showNewChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-new-chunks")
        .description("Show how many new chunks have been found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showStashes = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stashes")
        .description("Show how many stashes have been found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-players")
        .description("Show how many players have been recorded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showAutopilot = sgGeneral.add(new BoolSetting.Builder()
        .name("show-autopilot")
        .description("Show autopilot destination and distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showFireworks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-fireworks")
        .description("Show the remaining firework count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Colour of the labels.")
        .defaultValue(new SettingColor(150, 150, 150))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Colour of the values.")
        .defaultValue(new SettingColor(0, 200, 255))
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Draw a shadow behind the text.")
        .defaultValue(true)
        .build()
    );

    public BaseHuntingHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        List<String> lines = buildLines();

        double width = 0;
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, shadow.get()));

        setSize(width, renderer.textHeight(shadow.get()) * lines.size());

        double y = this.y;

        for (String line : lines) {
            int separator = line.indexOf(':');

            if (separator < 0) {
                renderer.text(line, x, y, valueColor.get(), shadow.get());
            } else {
                String label = line.substring(0, separator + 1);
                String value = line.substring(separator + 1);

                double labelWidth = renderer.text(label, x, y, labelColor.get(), shadow.get());
                renderer.text(value, x + labelWidth, y, valueColor.get(), shadow.get());
            }

            y += renderer.textHeight(shadow.get());
        }
    }

    private List<String> buildLines() {
        List<String> lines = new ArrayList<>();

        if (showServer.get()) lines.add("Server: " + ServerUtils.serverAddress());
        lines.add("Position: " + ServerUtils.coordinates());

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (showNewChunks.get() && newChunks != null && newChunks.isActive()) {
            lines.add("New chunks: %d".formatted(newChunks.getTrackedCount()));
        }

        StashManager stashManager = StashManager.get();
        if (showStashes.get() && stashManager != null) {
            lines.add("Stashes: %d".formatted(stashManager.forCurrentServer().size()));
        }

        PlayerTracker tracker = PlayerTracker.get();
        if (showPlayers.get() && tracker != null) {
            lines.add("Players: %d".formatted(tracker.forCurrentServer().size()));
        }

        BaseHunter hunter = Modules.get().get(BaseHunter.class);
        if (showAutopilot.get() && hunter != null && hunter.isActive()) {
            var destination = hunter.getCurrentDestination();

            if (destination != null) {
                double dx = destination.getX() - mc.player.getX();
                double dz = destination.getZ() - mc.player.getZ();
                lines.add("Destination: %d, %d".formatted(destination.getX(), destination.getZ()));
                lines.add("Remaining: %d blocks".formatted((int) Math.sqrt(dx * dx + dz * dz)));
            }

            lines.add("Status: " + (hunter.isPaused() ? "paused" : "flying"));
            lines.add("Flown: %,d blocks".formatted((long) hunter.getTravelledBlocks()));
        }

        if (showFireworks.get() && mc.player != null) {
            lines.add("Fireworks: %d".formatted(countFireworks()));
        }

        return lines;
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
}
