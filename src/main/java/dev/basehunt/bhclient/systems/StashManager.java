package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.systems.System;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Persistent record of every stash, base and new-chunk cluster the client has seen.
 *
 * <p>Entries are keyed by server so travelling between anarchy servers does not mix their coordinates up.
 * The store is deliberately append-mostly: a stash that is seen again is merged and its counters refreshed
 * rather than duplicated.
 */
public class StashManager extends System<StashManager> {
    private final List<Stash> stashes = new ArrayList<>();

    public StashManager() {
        super("base-hunting-stashes");
    }

    public static StashManager get() {
        return meteordevelopment.meteorclient.systems.Systems.get(StashManager.class);
    }

    public List<Stash> getAll() {
        return stashes;
    }

    public List<Stash> forCurrentServer() {
        String server = ServerUtils.serverAddress();
        List<Stash> result = new ArrayList<>();

        for (Stash stash : stashes) {
            if (stash.server.equals(server)) result.add(stash);
        }

        result.sort(Comparator.comparingInt((Stash s) -> -s.score()));
        return result;
    }

    /** Adds the stash or merges it into the closest existing entry. Returns the stored instance. */
    public Stash addOrMerge(Stash stash, int mergeRadius) {
        Stash existing = findNearby(stash.server, stash.pos, mergeRadius);

        if (existing != null) {
            existing.merge(stash);
            save();
            return existing;
        }

        stashes.add(stash);
        save();
        return stash;
    }

    public Stash findNearby(String server, BlockPos pos, int radius) {
        Stash best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Stash stash : stashes) {
            if (!stash.server.equals(server)) continue;

            double distance = stash.pos.getSquaredDistance(pos);
            if (distance <= (double) radius * radius && distance < bestDistance) {
                best = stash;
                bestDistance = distance;
            }
        }

        return best;
    }

    public int removeWhere(java.util.function.Predicate<Stash> predicate) {
        int before = stashes.size();
        stashes.removeIf(predicate);
        int removed = before - stashes.size();

        if (removed > 0) save();
        return removed;
    }

    public void clearCurrentServer() {
        String server = ServerUtils.serverAddress();
        removeWhere(stash -> stash.server.equals(server));
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        NbtList list = new NbtList();

        for (Stash stash : stashes) list.add(stash.toTag());
        tag.put("stashes", list);

        return tag;
    }

    @Override
    public StashManager fromTag(NbtCompound tag) {
        stashes.clear();

        NbtList list = tag.getListOrEmpty("stashes");
        for (NbtElement element : list) {
            if (element instanceof NbtCompound compound) {
                Stash stash = Stash.fromTag(compound);
                if (stash != null) stashes.add(stash);
            }
        }

        return this;
    }

    public enum Kind {
        Stash,
        Base,
        NewChunks,
        Manual
    }

    public static class Stash {
        public String server;
        public BlockPos pos;
        public Kind kind;
        public String dimension;

        public int chests;
        public int barrels;
        public int shulkers;
        public int enderChests;
        public int furnaces;
        public int hoppers;
        public int dispensers;
        public int spawners;

        public long firstSeen;
        public long lastSeen;

        public Stash(String server, BlockPos pos, Kind kind) {
            this.server = server;
            this.pos = pos;
            this.kind = kind;
            this.dimension = ServerUtils.dimension();
            this.firstSeen = java.lang.System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
        }

        public int totalStorage() {
            return chests + barrels + shulkers + enderChests + furnaces + hoppers + dispensers;
        }

        public int score() {
            return chests * 2 + barrels * 2 + shulkers * 8 + enderChests + furnaces + hoppers + dispensers + spawners * 4;
        }

        public void merge(Stash other) {
            // Counters are peaks, not sums: the same chunk is re-sent on every re-visit.
            chests = Math.max(chests, other.chests);
            barrels = Math.max(barrels, other.barrels);
            shulkers = Math.max(shulkers, other.shulkers);
            enderChests = Math.max(enderChests, other.enderChests);
            furnaces = Math.max(furnaces, other.furnaces);
            hoppers = Math.max(hoppers, other.hoppers);
            dispensers = Math.max(dispensers, other.dispensers);
            spawners = Math.max(spawners, other.spawners);

            lastSeen = java.lang.System.currentTimeMillis();
            dimension = other.dimension;
        }

        public String describeCounts() {
            StringBuilder builder = new StringBuilder();

            appendCount(builder, "chest", chests);
            appendCount(builder, "barrel", barrels);
            appendCount(builder, "shulker", shulkers);
            appendCount(builder, "ender chest", enderChests);
            appendCount(builder, "furnace", furnaces);
            appendCount(builder, "hopper", hoppers);
            appendCount(builder, "dispenser", dispensers);
            appendCount(builder, "spawner", spawners);

            return builder.isEmpty() ? "none" : builder.toString();
        }

        private static void appendCount(StringBuilder builder, String label, int count) {
            if (count <= 0) return;
            if (!builder.isEmpty()) builder.append(", ");
            builder.append(count).append(' ').append(label).append(count == 1 ? "" : "s");
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();

            tag.putString("server", server);
            tag.putIntArray("pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            tag.putString("kind", kind.name());
            tag.putString("dimension", dimension);

            tag.putInt("chests", chests);
            tag.putInt("barrels", barrels);
            tag.putInt("shulkers", shulkers);
            tag.putInt("enderChests", enderChests);
            tag.putInt("furnaces", furnaces);
            tag.putInt("hoppers", hoppers);
            tag.putInt("dispensers", dispensers);
            tag.putInt("spawners", spawners);

            tag.putLong("firstSeen", firstSeen);
            tag.putLong("lastSeen", lastSeen);

            return tag;
        }

        public static Stash fromTag(NbtCompound tag) {
            int[] pos = tag.getIntArray("pos").orElse(null);
            if (pos == null || pos.length != 3) return null;

            Kind kind;
            try {
                kind = Kind.valueOf(tag.getString("kind", "Stash"));
            } catch (IllegalArgumentException e) {
                kind = Kind.Stash;
            }

            Stash stash = new Stash(tag.getString("server", "unknown"), new BlockPos(pos[0], pos[1], pos[2]), kind);

            stash.dimension = tag.getString("dimension", "unknown");
            stash.chests = tag.getInt("chests", 0);
            stash.barrels = tag.getInt("barrels", 0);
            stash.shulkers = tag.getInt("shulkers", 0);
            stash.enderChests = tag.getInt("enderChests", 0);
            stash.furnaces = tag.getInt("furnaces", 0);
            stash.hoppers = tag.getInt("hoppers", 0);
            stash.dispensers = tag.getInt("dispensers", 0);
            stash.spawners = tag.getInt("spawners", 0);
            stash.firstSeen = tag.getLong("firstSeen", stash.firstSeen);
            stash.lastSeen = tag.getLong("lastSeen", stash.lastSeen);

            return stash;
        }
    }

    /** Convenience for callers that want to mutate a list of stashes without touching the store. */
    public void forEachCurrentServer(Consumer<Stash> consumer) {
        for (Stash stash : forCurrentServer()) consumer.accept(stash);
    }
}
