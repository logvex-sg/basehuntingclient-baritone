package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Keeps a per-server log of players the client has seen, where they were first and last spotted and when.
 *
 * <p>Anarchy servers rotate alt accounts constantly, so entries are matched on UUID and the username is
 * only ever used as a display label.
 */
public class PlayerTracker extends System<PlayerTracker> {
    private static final int MAX_ENTRIES_PER_SERVER = 5000;

    private final List<Entry> entries = new ArrayList<>();

    public PlayerTracker() {
        super("base-hunting-players");
    }

    public static PlayerTracker get() {
        return Systems.get(PlayerTracker.class);
    }

    public List<Entry> getAll() {
        return entries;
    }

    public List<Entry> forCurrentServer() {
        String server = ServerUtils.serverAddress();
        List<Entry> result = new ArrayList<>();

        for (Entry entry : entries) {
            if (entry.server.equals(server)) result.add(entry);
        }

        result.sort(Comparator.comparingLong((Entry e) -> -e.lastSeen));
        return result;
    }

    public Entry get(UUID uuid) {
        String server = ServerUtils.serverAddress();

        for (Entry entry : entries) {
            if (entry.uuid.equals(uuid) && entry.server.equals(server)) return entry;
        }

        return null;
    }

    public Entry track(UUID uuid, String name, int x, int y, int z) {
        Entry entry = get(uuid);
        if (entry == null) {
            entry = new Entry(uuid, name, ServerUtils.serverAddress());
            entry.dimension = ServerUtils.dimension();
            entry.firstX = x;
            entry.firstY = y;
            entry.firstZ = z;
            entries.add(entry);
            trim(entry.server);
        }

        entry.name = name;
        entry.lastX = x;
        entry.lastY = y;
        entry.lastZ = z;
        entry.lastSeen = java.lang.System.currentTimeMillis();
        entry.dimension = ServerUtils.dimension();
        entry.sightings++;

        return entry;
    }

    /** Marks the player as having logged out; the next sighting starts a new session. */
    public void markLoggedOut(UUID uuid, int x, int y, int z) {
        Entry entry = get(uuid);
        if (entry == null) return;

        entry.logoutX = x;
        entry.logoutY = y;
        entry.logoutZ = z;
        entry.logoutTime = java.lang.System.currentTimeMillis();
        entry.logoutDimension = ServerUtils.dimension();
        entry.sessionsSeen++;

        save();
    }

    public void markLoggedIn(UUID uuid) {
        Entry entry = get(uuid);
        if (entry == null) return;

        entry.lastLogin = java.lang.System.currentTimeMillis();
        save();
    }

    public Entry findByLogoutLocation(int x, int y, int z, int radius) {
        String server = ServerUtils.serverAddress();
        long now = java.lang.System.currentTimeMillis();

        Entry best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entry entry : entries) {
            if (!entry.server.equals(server)) continue;
            if (entry.logoutTime <= 0) continue;
            if (now - entry.logoutTime > 1000L * 60 * 60 * 12) continue;

            double dx = entry.logoutX - x;
            double dy = entry.logoutY - y;
            double dz = entry.logoutZ - z;
            double distance = dx * dx + dy * dy + dz * dz;

            if (distance <= (double) radius * radius && distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }

        return best;
    }

    private void trim(String server) {
        List<Entry> serverEntries = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.server.equals(server)) serverEntries.add(entry);
        }

        if (serverEntries.size() <= MAX_ENTRIES_PER_SERVER) return;

        serverEntries.sort(Comparator.comparingLong(e -> e.lastSeen));
        int toRemove = serverEntries.size() - MAX_ENTRIES_PER_SERVER;
        entries.removeAll(serverEntries.subList(0, toRemove));
    }

    public int clearCurrentServer() {
        String server = ServerUtils.serverAddress();
        int before = entries.size();
        entries.removeIf(entry -> entry.server.equals(server));

        int removed = before - entries.size();
        if (removed > 0) save();
        return removed;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        NbtList list = new NbtList();

        for (Entry entry : entries) list.add(entry.toTag());
        tag.put("entries", list);

        return tag;
    }

    @Override
    public PlayerTracker fromTag(NbtCompound tag) {
        entries.clear();

        NbtList list = tag.getListOrEmpty("entries");
        for (NbtElement element : list) {
            if (element instanceof NbtCompound compound) {
                Entry entry = Entry.fromTag(compound);
                if (entry != null) entries.add(entry);
            }
        }

        return this;
    }

    public static class Entry {
        public UUID uuid;
        public String name;
        public String server;
        public String dimension = "unknown";

        public int firstX, firstY, firstZ;
        public int lastX, lastY, lastZ;

        public int logoutX, logoutY, logoutZ;
        public String logoutDimension = "unknown";
        public long logoutTime;
        public long lastLogin;

        public long firstSeen;
        public long lastSeen;
        public int sightings;


        public int sessionsSeen;
        public boolean notifiedNearby;

        public Entry(UUID uuid, String name, String server) {
            this.uuid = uuid;
            this.name = name;
            this.server = server;
            this.firstSeen = java.lang.System.currentTimeMillis();
            this.lastSeen = this.firstSeen;
        }

        public boolean hasLogoutSpot() {
            return logoutTime > 0;
        }

        public int logoutDistanceTo(int x, int y, int z) {
            double dx = logoutX - x;
            double dy = logoutY - y;
            double dz = logoutZ - z;
            return (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public String logoutCoords() {
            return "%d, %d, %d".formatted(logoutX, logoutY, logoutZ);
        }

        public String firstCoords() {
            return "%d, %d, %d".formatted(firstX, firstY, firstZ);
        }

        public String lastCoords() {
            return "%d, %d, %d".formatted(lastX, lastY, lastZ);
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();

            tag.putString("uuid", uuid.toString());
            tag.putString("name", name);
            tag.putString("server", server);
            tag.putString("dimension", dimension);

            tag.putIntArray("first", new int[] {firstX, firstY, firstZ});
            tag.putIntArray("last", new int[] {lastX, lastY, lastZ});
            tag.putIntArray("logout", new int[] {logoutX, logoutY, logoutZ});

            tag.putString("logoutDimension", logoutDimension);
            tag.putLong("logoutTime", logoutTime);
            tag.putLong("lastLogin", lastLogin);

            tag.putLong("firstSeen", firstSeen);
            tag.putLong("lastSeen", lastSeen);
            tag.putInt("sightings", sightings);
            tag.putInt("sessionsSeen", sessionsSeen);

            return tag;
        }

        public static Entry fromTag(NbtCompound tag) {
            String rawUuid = tag.getString("uuid", "");
            if (rawUuid.isBlank()) return null;

            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException e) {
                return null;
            }

            Entry entry = new Entry(uuid, tag.getString("name", "unknown"), tag.getString("server", "unknown"));

            entry.dimension = tag.getString("dimension", "unknown");
            entry.logoutDimension = tag.getString("logoutDimension", "unknown");

            int[] first = tag.getIntArray("first").orElse(new int[0]);
            int[] last = tag.getIntArray("last").orElse(new int[0]);
            int[] logout = tag.getIntArray("logout").orElse(new int[0]);

            if (first.length == 3) {
                entry.firstX = first[0];
                entry.firstY = first[1];
                entry.firstZ = first[2];
            }

            if (last.length == 3) {
                entry.lastX = last[0];
                entry.lastY = last[1];
                entry.lastZ = last[2];
            }

            if (logout.length == 3) {
                entry.logoutX = logout[0];
                entry.logoutY = logout[1];
                entry.logoutZ = logout[2];
            }

            entry.logoutTime = tag.getLong("logoutTime", 0);
            entry.lastLogin = tag.getLong("lastLogin", 0);
            entry.firstSeen = tag.getLong("firstSeen", entry.firstSeen);
            entry.lastSeen = tag.getLong("lastSeen", entry.lastSeen);
            entry.sightings = tag.getInt("sightings", 0);
            entry.sessionsSeen = tag.getInt("sessionsSeen", 0);

            return entry;
        }
    }
}
