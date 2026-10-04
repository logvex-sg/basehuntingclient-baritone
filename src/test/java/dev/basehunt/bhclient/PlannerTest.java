package dev.basehunt.bhclient;

import dev.basehunt.bhclient.utils.FlightPlanner;


import java.util.*;

public class PlannerTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    public static void main(String[] args) {

        System.out.println("== clustering ==");

        Set<Long> blob = new HashSet<>();
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++) blob.add(FlightPlanner.chunkKey(x, z));
        List<List<Long>> c1 = FlightPlanner.cluster(blob, 2);
        check("3x3 adjacent chunks form one cluster", c1.size() == 1, "got " + c1.size());
        check("cluster holds all 9 chunks", c1.get(0).size() == 9, "got " + c1.get(0).size());

        Set<Long> two = new HashSet<>(Set.of(FlightPlanner.chunkKey(0, 0), FlightPlanner.chunkKey(50, 50)));
        List<List<Long>> c2 = FlightPlanner.cluster(two, 2);
        check("distant chunks stay separate", c2.size() == 2, "got " + c2.size());

        check("empty input yields no clusters", FlightPlanner.cluster(new HashSet<>(), 2).isEmpty(), "not empty");

        Set<Long> chain = new HashSet<>();
        for (int i = 0; i < 5; i++) chain.add(FlightPlanner.chunkKey(i, 0));
        check("chain merges with radius 1", FlightPlanner.cluster(chain, 1).size() == 1,
            "got " + FlightPlanner.cluster(chain, 1).size());

        System.out.println("== nearest-neighbour ordering ==");

        List<FlightPlanner.Target> targets = new ArrayList<>(List.of(
            new FlightPlanner.Target(1000, 0, 1, 100, "far"),
            new FlightPlanner.Target(100, 0, 1, 100, "near"),
            new FlightPlanner.Target(500, 0, 1, 100, "mid")
        ));
        List<FlightPlanner.Target> ordered = FlightPlanner.orderByNearest(targets, 0, 0);
        check("nearest target visited first", ordered.get(0).x() == 100, "got " + ordered.get(0).x());
        check("then mid", ordered.get(1).x() == 500, "got " + ordered.get(1).x());
        check("then far", ordered.get(2).x() == 1000, "got " + ordered.get(2).x());

        check("ordering preserves count", ordered.size() == 3, "got " + ordered.size());
        Set<Integer> xs = new HashSet<>();
        for (FlightPlanner.Target t : ordered) xs.add(t.x());
        check("ordering preserves identity", xs.size() == 3, "duplicates present");

        double greedy = tourLength(ordered, 0, 0);
        double worst = tourLength(new ArrayList<>(List.of(
            new FlightPlanner.Target(1000, 0, 1, 100, "far"),
            new FlightPlanner.Target(500, 0, 1, 100, "mid"),
            new FlightPlanner.Target(100, 0, 1, 100, "near"))), 0, 0);
        check("greedy tour is shorter than worst ordering", greedy < worst,
            "greedy=" + greedy + " worst=" + worst);

        System.out.println("== frontier expansion ==");

        Set<Long> known = new HashSet<>();
        for (int x = 0; x < 10; x++) known.add(FlightPlanner.chunkKey(x, 0));

        FlightPlanner.Target f = FlightPlanner.frontier(known, 0, 0, 2000);
        check("frontier pushes outward", Math.abs(f.z()) >= 1000 || Math.abs(f.x()) >= 1000,
            "got " + f.x() + "," + f.z());
        check("frontier is roughly step distance away",
            Math.abs(Math.hypot(f.x(), f.z()) - 2000) < 400,
            "dist=" + Math.hypot(f.x(), f.z()));

        FlightPlanner.Target f0 = FlightPlanner.frontier(new HashSet<>(), 0, 0, 2000);
        check("frontier works with no detections", f0.z() == -2000, "got " + f0.x() + "," + f0.z());

        System.out.println("== visited tracking ==");

        FlightPlanner planner = new FlightPlanner();
        check("nothing visited initially", !planner.wasVisited(10, 10), "was visited");
        planner.markVisited(10, 10);
        check("visited position is remembered", planner.wasVisited(10, 10), "not remembered");
        check("unvisited position is not", !planner.wasVisited(10, 11), "false positive");
        check("visited count increments", planner.visitedCount() == 1, "got " + planner.visitedCount());
        planner.clear();
        check("clear resets visited", !planner.wasVisited(10, 10) && planner.visitedCount() == 0, "not reset");

        System.out.println("== progress / frontier gating ==");
        check("progress starts fresh", planner.millisSinceProgress() < 1000, "stale");
        planner.noteProgress();
        check("noteProgress resets timer", planner.millisSinceProgress() < 1000, "not reset");

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static double tourLength(List<FlightPlanner.Target> ts, double x, double z) {
        double total = 0;
        for (FlightPlanner.Target t : ts) {
            total += Math.hypot(t.x() - x, t.z() - z);
            x = t.x(); z = t.z();
        }
        return total;
    }
}
