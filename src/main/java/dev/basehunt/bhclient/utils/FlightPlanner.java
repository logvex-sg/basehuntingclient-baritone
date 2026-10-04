package dev.basehunt.bhclient.utils;



import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides where to fly next, and in what order.
 *
 * <p>The simple sweeps in BaseHunter fly to a fixed pattern and hope. This planner instead reacts to what
 * the client is actually seeing, which is what makes a hunt finish sooner:
 *
 * <ul>
 *   <li><b>Clustering.</b> New chunks almost never appear alone. Chunks are grouped into clusters and a
 *       cluster is visited once, at its centre, instead of flying to each chunk in turn. Twenty adjacent
 *       new chunks collapse into a single target.</li>
 *   <li><b>Priority by yield.</b> Clusters are scored on size, distance and whether a stash or player was
 *       recorded there, so the most promising terrain is checked before it goes stale.</li>
 *   <li><b>Nearest-neighbour ordering.</b> Selected targets are reordered into a greedy tour so the route
 *       does not criss-cross the map between two points it already passed.</li>
 *   <li><b>Recovery.</b> When nothing has been detected for a while the planner emits a frontier waypoint
 *       beyond the furthest known chunk, pushing the sweep outward instead of re-treading old ground.</li>
 * </ul>
 *
 * <p>Everything here is pure calculation over positions so it can be reasoned about and tested without a
 * running game.
 */
public final class FlightPlanner {
    /** A group of adjacent new chunks, reduced to one flyable target. */
    public record Target(int x, int z, int chunkCount, double score, String reason) {
    }

    /**
     * Chunk key packing, matching {@code ChunkPos.toLong}: x in the low 32 bits, z in the high 32 bits.
     * Kept local so this class has no Minecraft dependency and can be tested headlessly.
     */
    public static long chunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >> 32);
    }

    private final Set<Long> visited = new HashSet<>();
    private long lastProgressAt;

    public FlightPlanner() {
        this.lastProgressAt = System.currentTimeMillis();
    }

    public void clear() {
        visited.clear();
        lastProgressAt = System.currentTimeMillis();
    }

    public void markVisited(int x, int z) {
        visited.add(key(x, z));
        lastProgressAt = System.currentTimeMillis();
    }

    public boolean wasVisited(int x, int z) {
        return visited.contains(key(x, z));
    }

    /** Called whenever something interesting is found, which suppresses frontier expansion. */
    public void noteProgress() {
        lastProgressAt = System.currentTimeMillis();
    }

    public long millisSinceProgress() {
        return System.currentTimeMillis() - lastProgressAt;
    }

    public int visitedCount() {
        return visited.size();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Groups chunk keys into clusters of adjacent chunks using a flood fill.
     *
     * @param chunkKeys packed chunk positions to cluster
     * @param mergeRadius chebyshev distance at which two chunks are treated as the same cluster
     * @return clusters as lists of chunk keys
     */
    public static List<List<Long>> cluster(Set<Long> chunkKeys, int mergeRadius) {
        Set<Long> remaining = new HashSet<>(chunkKeys);
        List<List<Long>> clusters = new ArrayList<>();
        int radius = Math.max(1, mergeRadius);

        while (!remaining.isEmpty()) {
            long seed = remaining.iterator().next();
            remaining.remove(seed);

            List<Long> cluster = new ArrayList<>();
            List<Long> queue = new ArrayList<>();
            queue.add(seed);

            while (!queue.isEmpty()) {
                long current = queue.remove(queue.size() - 1);
                cluster.add(current);

                int cx = keyX(current);
                int cz = keyZ(current);

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx == 0 && dz == 0) continue;

                        long neighbour = chunkKey(cx + dx, cz + dz);
                        if (remaining.remove(neighbour)) queue.add(neighbour);
                    }
                }
            }

            clusters.add(cluster);
        }

        return clusters;
    }

    /**
     * Turns new-chunk detections into a ranked list of flyable targets.
     *
     * @param chunkKeys packed chunk positions flagged as new
     * @param mergeRadius chebyshev distance for clustering
     * @param fromX player x, used for distance scoring
     * @param fromZ player z, used for distance scoring
     * @param maxTargets how many targets to return
     * @param priorityRadius radius around which a recorded stash or player raises a cluster's priority
     */
    public List<Target> buildTargets(Set<Long> chunkKeys, int mergeRadius,
                                     int fromX, int fromZ, int maxTargets, int priorityRadius) {
        List<Target> targets = new ArrayList<>();

        for (List<Long> cluster : cluster(chunkKeys, mergeRadius)) {
            int sumX = 0;
            int sumZ = 0;
            int size = cluster.size();

            for (long key : cluster) {
                sumX += keyX(key);
                sumZ += keyZ(key);
            }

            // Cluster centre in block coordinates. Averaging in chunk space keeps the centre inside the
            // cluster even when it is spread across a quadrant boundary.
            int centreX = (sumX / size) * 16 + 8;
            int centreZ = (sumZ / size) * 16 + 8;

            if (wasVisited(centreX, centreZ)) continue;

            double distance = Math.sqrt(Math.pow(centreX - fromX, 2) + Math.pow(centreZ - fromZ, 2));

            // Bigger clusters are worth more, distance costs, and anything near a known stash or player
            // gets a large bonus because that is where a base actually is.
            double score = size * 10.0;
            score -= distance / 128.0;

            String reason = size == 1 ? "single new chunk" : size + " adjacent new chunks";
            if (nearKnownInterest(centreX, centreZ, priorityRadius)) {
                score += 250.0;
                reason += " near known interest";
            }

            targets.add(new Target(centreX, centreZ, size, score, reason));
        }

        targets.sort(Comparator.comparingDouble(Target::score).reversed());

        if (targets.size() > maxTargets) {
            return new ArrayList<>(targets.subList(0, maxTargets));
        }

        return targets;
    }

    /** True when a recorded stash or player logout spot sits near the given position. */
    private static boolean nearKnownInterest(int x, int z, int radius) {
        if (radius <= 0) return false;

        var stashManager = dev.basehunt.bhclient.systems.StashManager.get();
        if (stashManager != null && stashManager.findNearby(ServerUtils.serverAddress(), new net.minecraft.util.math.BlockPos(x, 64, z), radius) != null) {
            return true;
        }

        var tracker = dev.basehunt.bhclient.systems.PlayerTracker.get();
        return tracker != null && tracker.findByLogoutLocation(x, 64, z, radius) != null;
    }

    /**
     * Reorders targets into a greedy nearest-neighbour tour starting from the player.
     *
     * <p>Visiting targets in score order alone would zig-zag: a high scoring cluster on the far side of the
     * map would be flown to before a cluster right next to the player. Walking the nearest unvisited target
     * each time keeps the path short, so more ground is covered per elytra.
     */
    public static List<Target> orderByNearest(List<Target> targets, int fromX, int fromZ) {
        List<Target> remaining = new ArrayList<>(targets);
        List<Target> ordered = new ArrayList<>(remaining.size());

        double currentX = fromX;
        double currentZ = fromZ;

        while (!remaining.isEmpty()) {
            Target nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            int nearestIndex = -1;

            for (int i = 0; i < remaining.size(); i++) {
                Target target = remaining.get(i);
                double distance = Math.pow(target.x() - currentX, 2) + Math.pow(target.z() - currentZ, 2);

                if (distance < nearestDistance) {
                    nearest = target;
                    nearestDistance = distance;
                    nearestIndex = i;
                }
            }

            if (nearest == null) break;

            ordered.add(nearest);
            remaining.remove(nearestIndex);
            currentX = nearest.x();
            currentZ = nearest.z();
        }

        return ordered;
    }

    /**
     * Picks a waypoint beyond the furthest known chunk, to push the sweep outward when nothing new has
     * been found. Heading is taken from the player's current position so the frontier continues the
     * direction of travel rather than jumping to a random edge of the explored area.
     */
    public static Target frontier(Set<Long> knownChunks, int fromX, int fromZ, int step) {
        int furthestX = fromX;
        int furthestZ = fromZ;
        double furthestDistance = -1;

        for (long key : knownChunks) {
            int cx = keyX(key) * 16 + 8;
            int cz = keyZ(key) * 16 + 8;

            double distance = Math.pow(cx - fromX, 2) + Math.pow(cz - fromZ, 2);
            if (distance > furthestDistance) {
                furthestDistance = distance;
                furthestX = cx;
                furthestZ = cz;
            }
        }

        double dx = furthestX - fromX;
        double dz = furthestZ - fromZ;
        double length = Math.sqrt(dx * dx + dz * dz);

        // No known chunks at all: push north so the sweep at least has a direction.
        if (length < 1.0) return new Target(fromX, fromZ - step, 0, 0, "frontier");

        int targetX = (int) Math.round(fromX + dx / length * step);
        int targetZ = (int) Math.round(fromZ + dz / length * step);

        return new Target(targetX, targetZ, 0, 0, "frontier past furthest chunk");
    }
}
