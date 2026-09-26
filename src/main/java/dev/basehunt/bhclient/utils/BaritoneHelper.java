package dev.basehunt.bhclient.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import net.minecraft.util.math.BlockPos;

/**
 * Thin wrapper around Baritone so every module can stay quiet about whether Baritone is actually present.
 *
 * <p>Baritone is a compile-only dependency. Touching {@link BaritoneAPI} from a class that is loaded when
 * Baritone is missing would throw {@link NoClassDefFoundError}, so every reference lives behind the
 * {@link #AVAILABLE} check and the class is only initialised lazily from {@link #get()}.
 */
public final class BaritoneHelper {
    public static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("baritone.api.BaritoneAPI");
            available = true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            available = false;
        }
        AVAILABLE = available;
    }

    private BaritoneHelper() {
    }

    public static IBaritone get() {
        if (!AVAILABLE) return null;
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public static Settings settings() {
        if (!AVAILABLE) return null;
        return BaritoneAPI.getSettings();
    }

    public static boolean isPathing() {
        IBaritone baritone = get();
        return baritone != null && baritone.getPathingBehavior().isPathing();
    }

    public static boolean isElytraLoaded() {
        IBaritone baritone = get();
        return baritone != null && baritone.getElytraProcess().isLoaded();
    }

    public static void stop() {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getPathingBehavior().cancelEverything();
        baritone.getElytraProcess().onLostControl();
    }

    /** Flies to a destination using Baritone's nether-pathfinder powered elytra process. */
    public static void elytraPathTo(BlockPos destination) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getElytraProcess().pathTo(destination);
    }

    public static void elytraPathTo(Goal goal) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getElytraProcess().pathTo(goal);
    }

    public static void walkTo(BlockPos destination) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalNear(destination, 2));
    }

    public static BlockPos currentDestination() {
        IBaritone baritone = get();
        if (baritone == null) return null;

        return baritone.getElytraProcess().currentDestination();
    }

    public static void resetElytraState() {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getElytraProcess().resetState();
    }

    public static void runCommand(String command) {
        IBaritone baritone = get();
        if (baritone == null) return;

        baritone.getCommandManager().execute(command);
    }

    public static String getPrefix() {
        Settings settings = settings();
        return settings == null ? "" : settings.prefix.value;
    }
}
