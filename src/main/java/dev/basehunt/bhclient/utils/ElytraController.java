package dev.basehunt.bhclient.utils;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.player.Rotation;
import net.minecraft.util.math.MathHelper;

/**
 * Drives Meteor's ElytraFly as a route follower.
 *
 * <p>ElytraFly has no destination concept: its autopilot just holds forward, which is useless for a sweep.
 * This class supplies the missing half by aiming the player at the next waypoint every tick, then letting
 * ElytraFly's Bounce mode do the flying.
 *
 * <p><b>Bounce mode is what removes the firework requirement.</b> It recasts the elytra on every tick
 * (the "elytra recast" trick), which sustains flight indefinitely without consuming rockets. That also
 * means a flight is limited only by elytra durability, not by how many fireworks were brought along.
 *
 * <p>Meteor's yaw lock is deliberately left alone. Setting it to Simple would pin the player to a fixed
 * bearing, which is the opposite of steering to a waypoint, so yaw is written directly each tick instead.
 */
public final class ElytraController {
    private static final double ARRIVAL_HORIZONTAL = 24.0;
    private static final double ARRIVAL_VERTICAL = 24.0;

    private ElytraController() {
    }

    public static ElytraFly elytraFly() {
        return Modules.get().get(ElytraFly.class);
    }

    public static boolean available() {
        return elytraFly() != null;
    }

    public static boolean isFlying() {
        return ServerUtils.mc().player != null && ServerUtils.mc().player.isGliding();
    }

    /**
     * Configures ElytraFly for firework-free long range travel and turns it on.
     *
     * @param cruiseY altitude to hold while crossing open terrain
     * @param emergencyLand whether to let ElytraFly set down when it can no longer continue
     */
    public static boolean enable(double cruiseY, boolean emergencyLand) {
        ElytraFly fly = elytraFly();
        if (fly == null) return false;

        fly.flightMode.set(ElytraFlightModes.Bounce);
        fly.autoJump.set(true);
        fly.sprint.set(false);

        // Bounce mode recasts the elytra, so fireworks are never needed. Explicitly disabling firework
        // use is what makes "no fireworks required" true rather than merely usually true.
        fly.useFireworks.set(false);

        fly.autoPilot.set(true);
        fly.autoPilotMinimumHeight.set(cruiseY);
        fly.autoHover.set(false);

        // Bounce mode writes pitch every tick when pitch lock is on, which would overwrite the altitude
        // steering in steerTo() with a fixed angle. Releasing the lock is what lets terrain following work.
        fly.lockPitch.set(false);

        // Yaw lock must stay in Smart so Bounce passes our yaw through instead of quantising it to 45
        // degrees (Simple) or pinning a fixed bearing (None).
        fly.yawLockMode.set(Rotation.LockMode.Smart);

        fly.noCrash.set(true);
        fly.dontGoIntoUnloadedChunks.set(true);

        // ElytraManager owns elytra replacement: it picks the highest-durability spare, which is what makes
        // any durability of elytra usable. Leaving ElytraFly's own replacement on would mean two owners
        // racing to swap the same chest slot.
        fly.replace.set(false);

        // Replacing the chest armour automatically would fight our own elytra handling on some servers.
        fly.chestSwap.set(ElytraFly.ChestSwapMode.Never);

        if (emergencyLand) {
            var settings = BaritoneHelper.settings();
            if (settings != null) settings.elytraAllowEmergencyLand.value = true;
        }

        if (!fly.isActive()) fly.enable();
        return true;
    }

    /** Turns ElytraFly off and releases the movement keys it may have left held. */
    public static void disable() {
        ElytraFly fly = elytraFly();
        if (fly == null) return;

        if (ServerUtils.mc().options != null) {
            ServerUtils.mc().options.forwardKey.setPressed(false);
            ServerUtils.mc().options.jumpKey.setPressed(false);
        }

        if (fly.isActive()) fly.disable();
    }

    /**
     * Aims at the destination and climbs or descends toward the cruise altitude.
     *
     * <p>Yaw is derived from the horizontal offset rather than from a compass bearing so the route stays
     * correct in every quadrant. Pitch is proportional to height error, clamped so the player never dives
     * steeply enough to lose altitude faster than the elytra can recover.
     */
    public static void steerTo(double targetX, double targetY, double targetZ) {
        var player = ServerUtils.mc().player;
        if (player == null) return;

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double dy = targetY - player.getY();

        if (dx * dx + dz * dz > 0.01) {
            float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
        }

        double pitch = -MathHelper.clamp(dy * 0.35, -35.0, 35.0);
        player.setPitch((float) pitch);
    }

    /** Steers toward a ground position, holding the given cruise altitude. */
    public static void steerToGround(double targetX, double targetZ, double cruiseY) {
        steerTo(targetX, cruiseY, targetZ);
    }

    public static boolean arrivedHorizontally(double targetX, double targetZ) {
        var player = ServerUtils.mc().player;
        if (player == null) return true;

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= ARRIVAL_HORIZONTAL;
    }

    public static boolean arrived(double targetX, double targetY, double targetZ) {
        var player = ServerUtils.mc().player;
        if (player == null) return true;

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double dy = targetY - player.getY();

        return Math.sqrt(dx * dx + dz * dz) <= ARRIVAL_HORIZONTAL && Math.abs(dy) <= ARRIVAL_VERTICAL;
    }

    public static double horizontalDistanceTo(double targetX, double targetZ) {
        var player = ServerUtils.mc().player;
        if (player == null) return 0;

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Current speed in blocks per second, derived from the per-tick velocity. */
    public static double speedBlocksPerSecond() {
        var player = ServerUtils.mc().player;
        if (player == null) return 0;

        return player.getVelocity().horizontalLength() * 20.0;
    }
}
