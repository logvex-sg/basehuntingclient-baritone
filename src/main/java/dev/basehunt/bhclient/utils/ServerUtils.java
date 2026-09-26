package dev.basehunt.bhclient.utils;

import dev.basehunt.bhclient.BaseHuntingAddon;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.world.World;

/** Small helpers shared by every module for describing "where am I right now". */
public final class ServerUtils {
    private ServerUtils() {
    }

    public static MinecraftClient mc() {
        return MeteorClient.mc;
    }

    /** Singleplayer worlds report no server entry, so fall back to a readable label. */
    public static String serverAddress() {
        MinecraftClient mc = mc();
        if (mc == null) return "unknown";

        ServerInfo info = mc.getCurrentServerEntry();
        if (info != null && info.address != null && !info.address.isBlank()) return info.address;

        if (mc.isInSingleplayer()) return "singleplayer";
        return "unknown";
    }

    public static String dimension() {
        MinecraftClient mc = mc();
        if (mc == null || mc.world == null) return "unknown";

        World world = mc.world;
        if (world.getRegistryKey() == World.OVERWORLD) return "overworld";
        if (world.getRegistryKey() == World.NETHER) return "nether";
        if (world.getRegistryKey() == World.END) return "end";
        return world.getRegistryKey().getValue().getPath();
    }

    public static String playerName() {
        MinecraftClient mc = mc();
        if (mc == null || mc.player == null) return "unknown";
        return mc.player.getGameProfile().name();
    }

    public static String coordinates() {
        MinecraftClient mc = mc();
        if (mc == null || mc.player == null) return "unknown";

        return "%d, %d, %d".formatted(
            (int) Math.floor(mc.player.getX()),
            (int) Math.floor(mc.player.getY()),
            (int) Math.floor(mc.player.getZ())
        );
    }

    public static String position() {
        MinecraftClient mc = mc();
        if (mc == null || mc.player == null) return "unknown";

        return "%s [%s] %s".formatted(playerName(), serverAddress(), coordinates());
    }

    public static int spawnDistance(int x, int z) {
        MinecraftClient mc = mc();
        if (mc == null || mc.world == null) return 0;

        // 1.21.11 replaced the spawn position getter with a spawn point record.
        double dx = x - mc.world.getSpawnPoint().getPos().getX();
        double dz = z - mc.world.getSpawnPoint().getPos().getZ();
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    public static void log(String message) {
        BaseHuntingAddon.LOG.info(message);
    }
}
