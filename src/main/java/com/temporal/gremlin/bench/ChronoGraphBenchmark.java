package com.temporal.gremlin.bench;

import com.tinkerpop.blueprints.TimeInstant;
import com.tinkerpop.blueprints.TimePeriod;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.dfpl.chronograph.algorithm.temporal.search.bfs.TemporalBreadthFirstSearch;
import org.dfpl.chronograph.common.TemporalRelation;
import org.dfpl.chronograph.crud.memory.ChronoEdge;
import org.dfpl.chronograph.crud.memory.ChronoGraph;
import org.dfpl.chronograph.crud.memory.ChronoVertex;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Scalability & Micro/Macro Benchmark: ChronoGraph vs T-TinkerPop Native (Table VI of the paper).
 *
 * Evaluates across OpenSky Flight dataset scales (10k, 50k, 100k, 500k, 1.1M flights):
 *   1. Macro-benchmark: Temporal BFS (ChronoGraph API vs T-TinkerPop native g.V(s).tbfs(...))
 *   2. Micro-benchmark: Allen operators (temporalBefore, temporalOverlaps) across:
 *      - CG Loop (raw event scan)
 *      - CG NatRel (ChronoGraph NavigableSet index)
 *      - T-TinkerPop Direct (raw primitive long[] scan)
 *      - T-TinkerPop Unindexed (folded Gremlin traversal scan)
 *      - T-TinkerPop Indexed (automated TemporalTinkerIndex IntervalTree pushdown)
 */
public class ChronoGraphBenchmark {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int BFS_ITERATIONS = 10;
    private static final int OP_ITERATIONS = 50;
    private static final String CSV_PATH = "datasets/flightlist_20190101_20190131.csv";

    private static final int[] SIZES = {10000, 50000, 100000, 500000, 1114885};
    private static final String[] LABELS = {"10k", "50k", "100k", "500k", "1.1M"};

    private TinkerGraph unindexedGraph;
    private GraphTraversalSource gUnindexed;
    private TinkerGraph indexedGraph;
    private GraphTraversalSource gIndexed;
    private ChronoGraph chronoGraph;

    private Vertex tSource;
    private ChronoVertex cSource;
    private Edge refEdgeUnindexed;
    private Edge refEdgeIndexed;
    private Lifetime refLifetime;
    private TimePeriod refPeriod;

    public static void main(String[] args) {
        new ChronoGraphBenchmark().run();
    }

    public void run() {
        System.out.println("\n========================================================================================================================");
        System.out.println("  CHRONOGRAPH VS T-TINKERPOP SCALABILITY BENCHMARK (OPEN-SKY FLIGHT WORKLOADS)");
        System.out.println("  Comparing Temporal BFS and Allen Operators (CG Loop, CG NatRel, TPop Direct, TPop Unindexed, TPop Indexed)");
        System.out.println("========================================================================================================================");

        for (int i = 0; i < SIZES.length; i++) {
            runForSize(SIZES[i], LABELS[i]);
        }
    }

    private void runForSize(int size, String label) {
        System.out.println("\n" + "=".repeat(110));
        System.out.println("  DATASET SCALE: " + label + " Flights (target=" + size + ")");
        System.out.println("=".repeat(110));

        try {
            initializeGraphs(size);
            verifyOperatorParity(label);
            warmup();
            runBFSBenchmark(label);
            runOperatorBenchmarks(label);
        } catch (Exception e) {
            System.err.println("Benchmark error at scale " + label + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void initializeGraphs(int limit) throws Exception {
        unindexedGraph = TinkerGraph.open();
        gUnindexed = unindexedGraph.traversal();

        indexedGraph = TinkerGraph.open();
        indexedGraph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);
        gIndexed = indexedGraph.traversal();

        chronoGraph = new ChronoGraph();

        Map<String, Vertex> uAirports = new HashMap<>();
        Map<String, Vertex> iAirports = new HashMap<>();
        Map<String, ChronoVertex> cAirports = new HashMap<>();
        Map<String, List<Lifetime.Interval>> routeIntervals = new LinkedHashMap<>();
        Map<String, String> routeOrigins = new HashMap<>();
        Map<String, String> routeDests = new HashMap<>();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(CSV_PATH))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null && count < limit) {
                String[] parts = line.split(",");
                if (parts.length < 9) continue;

                String origin = parts[5].trim();
                String destination = parts[6].trim();
                if (origin.isEmpty() || destination.isEmpty() || origin.equals(destination)) continue;

                long start, end;
                try {
                    start = OffsetDateTime.parse(parts[7].trim(), formatter).toInstant().toEpochMilli();
                    end = OffsetDateTime.parse(parts[8].trim(), formatter).toInstant().toEpochMilli();
                } catch (Exception e) {
                    continue;
                }
                if (start >= end) continue;

                uAirports.computeIfAbsent(origin, c -> unindexedGraph.addVertex(T.label, "Airport", "code", c));
                uAirports.computeIfAbsent(destination, c -> unindexedGraph.addVertex(T.label, "Airport", "code", c));
                iAirports.computeIfAbsent(origin, c -> indexedGraph.addVertex(T.label, "Airport", "code", c));
                iAirports.computeIfAbsent(destination, c -> indexedGraph.addVertex(T.label, "Airport", "code", c));
                cAirports.computeIfAbsent(origin, c -> {
                    ChronoVertex cv = (ChronoVertex) chronoGraph.addVertex(c);
                    cv.setProperty("code", c);
                    return cv;
                });
                cAirports.computeIfAbsent(destination, c -> {
                    ChronoVertex cv = (ChronoVertex) chronoGraph.addVertex(c);
                    cv.setProperty("code", c);
                    return cv;
                });

                String routeKey = origin + "->" + destination;
                routeIntervals.computeIfAbsent(routeKey, k -> new ArrayList<>()).add(new Lifetime.Interval(start, end));
                routeOrigins.putIfAbsent(routeKey, origin);
                routeDests.putIfAbsent(routeKey, destination);
                count++;
            }
        }

        long sampleStart = 0L;
        long sampleEnd = 0L;
        int edgeCount = 0;
        int refTargetEdge = Math.max(1, routeIntervals.size() / 4);

        for (Map.Entry<String, List<Lifetime.Interval>> entry : routeIntervals.entrySet()) {
            String key = entry.getKey();
            String orig = routeOrigins.get(key);
            String dst = routeDests.get(key);
            Lifetime lt = Lifetime.from(entry.getValue());

            Edge eU = uAirports.get(orig).addEdge("flight", uAirports.get(dst));
            lt.attachTo(eU);

            Edge eI = iAirports.get(orig).addEdge("flight", iAirports.get(dst));
            lt.attachTo(eI);

            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cAirports.get(orig), cAirports.get(dst), "flight");
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }

            if ((edgeCount == refTargetEdge || sampleStart == 0L) && lt.getIntervalCount() > 0) {
                sampleStart = lt.getStartMillis(0);
                sampleEnd = lt.getEndMillis(0);
            }
            edgeCount++;
        }

        System.out.printf("  Loaded %,d flights consolidated into %,d multi-interval route edges across %,d airports.%n",
                count, edgeCount, uAirports.size());

        if (gUnindexed.V().hasLabel("Airport").hasNext()) {
            tSource = gUnindexed.V().hasLabel("Airport")
                    .order().by(__.outE("flight").count(), Order.desc)
                    .limit(1).next();
            cSource = (ChronoVertex) chronoGraph.getVertex(tSource.value("code").toString());
        }

        refLifetime = Lifetime.from(sampleStart, sampleEnd);
        refPeriod = new TimePeriod(sampleStart, sampleEnd);

        Vertex rU1 = unindexedGraph.addVertex(T.label, "refNode");
        Vertex rV1 = unindexedGraph.addVertex(T.label, "refNode");
        refEdgeUnindexed = gUnindexed.addE("refFlight").from(rU1).to(rV1).lifetime(sampleStart, sampleEnd).next();

        Vertex rU2 = indexedGraph.addVertex(T.label, "refNode");
        Vertex rV2 = indexedGraph.addVertex(T.label, "refNode");
        refEdgeIndexed = gIndexed.addE("refFlight").from(rU2).to(rV2).lifetime(sampleStart, sampleEnd).next();
    }

    private void verifyOperatorParity(String label) {
        String[] ops = {"temporalBefore", "temporalOverlaps"};
        for (String op : ops) {
            TemporalRelation cgRel = "temporalBefore".equals(op) ? TemporalRelation.isBefore : TemporalRelation.overlapsWith;
            Temporal tpopRel = "temporalBefore".equals(op) ? Temporal.precedes : Temporal.overlaps;

            long cLoop = countChronoGraphLoop(cgRel);
            long cNat = countChronoGraphNatRel(cgRel);
            long cDirect = countDirectTinkerPop(tpopRel);
            long cUnidx = "temporalBefore".equals(op)
                    ? gUnindexed.E().hasLabel("flight").temporalBefore(refEdgeUnindexed).with(Temporal.ANY).count().next()
                    : gUnindexed.E().hasLabel("flight").temporalOverlaps(refEdgeUnindexed).with(Temporal.ANY).count().next();
            long cIdx = "temporalBefore".equals(op)
                    ? gIndexed.E().hasLabel("flight").temporalBefore(refEdgeIndexed).with(Temporal.ANY).count().next()
                    : gIndexed.E().hasLabel("flight").temporalOverlaps(refEdgeIndexed).with(Temporal.ANY).count().next();

            if (cLoop != cNat || cNat != cDirect || cDirect != cUnidx || cUnidx != cIdx) {
                throw new IllegalStateException(String.format(
                        "Operator parity mismatch at scale %s for %s: CGLoop=%d, CGNat=%d, Direct=%d, Unindexed=%d, Indexed=%d",
                        label, op, cLoop, cNat, cDirect, cUnidx, cIdx));
            }
        }
    }

    private void warmup() {
        if (tSource == null) return;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            new TemporalBreadthFirstSearch().compute(cSource, new TimeInstant(0L), "flight");
            gUnindexed.V(tSource).tbfs(0L, TemporalPathTarget.EARLIEST_ARRIVAL, Direction.OUT, "flight").next();
            gUnindexed.V(tSource).tbfs(0L, TemporalPathTarget.EARLIEST_DEPARTURE, Direction.OUT, "flight").next();
            countChronoGraphNatRel(TemporalRelation.isBefore);
            countDirectTinkerPop(Temporal.precedes);
            gUnindexed.E().hasLabel("flight").temporalBefore(refEdgeUnindexed).with(Temporal.ANY).count().next();
            gIndexed.E().hasLabel("flight").temporalBefore(refEdgeIndexed).with(Temporal.ANY).count().next();
        }
    }

    private void runBFSBenchmark(String label) {
        if (tSource == null) return;

        long cgNanos = 0L;
        int cgReachable = 0;
        for (int i = 0; i < BFS_ITERATIONS; i++) {
            TemporalBreadthFirstSearch tbfs = new TemporalBreadthFirstSearch();
            long s = System.nanoTime();
            tbfs.compute(cSource, new TimeInstant(0L), "flight");
            cgNanos += (System.nanoTime() - s);
            if (i == 0) cgReachable = getChronoCount(tbfs) - 1; // exclude source vertex itself
        }
        double cgMs = cgNanos / (1_000_000.0 * BFS_ITERATIONS);

        long arrNanos = 0L;
        int arrReachable = 0;
        for (int i = 0; i < BFS_ITERATIONS; i++) {
            long s = System.nanoTime();
            Map<Vertex, Long> res = gUnindexed.V(tSource)
                    .tbfs(0L, TemporalPathTarget.EARLIEST_ARRIVAL, Direction.OUT, "flight").next();
            arrNanos += (System.nanoTime() - s);
            if (i == 0) arrReachable = res.size();
        }
        double arrMs = arrNanos / (1_000_000.0 * BFS_ITERATIONS);

        long depNanos = 0L;
        int depReachable = 0;
        for (int i = 0; i < BFS_ITERATIONS; i++) {
            long s = System.nanoTime();
            Map<Vertex, Long> res = gUnindexed.V(tSource)
                    .tbfs(0L, TemporalPathTarget.EARLIEST_DEPARTURE, Direction.OUT, "flight").next();
            depNanos += (System.nanoTime() - s);
            if (i == 0) depReachable = res.size();
        }
        double depMs = depNanos / (1_000_000.0 * BFS_ITERATIONS);

        System.out.println("\n--- Temporal BFS Performance ---");
        System.out.printf("%-26s %14s %14s %14s%n", "Implementation", "Time (ms)", "Reachable", "Speedup vs CG");
        System.out.println("-".repeat(72));
        System.out.printf("%-26s %11.3f ms %14d %14s%n", "ChronoGraph (API)", cgMs, cgReachable, "1.00x");
        System.out.printf("%-26s %11.3f ms %14d %13.2fx%n", "TPop tbfs (Arrival)", arrMs, arrReachable, cgMs / arrMs);
        System.out.printf("%-26s %11.3f ms %14d %13.2fx%n", "TPop tbfs (Departure)", depMs, depReachable, cgMs / depMs);
    }

    private void runOperatorBenchmarks(String label) {
        System.out.println("\n--- Temporal Operator Performance (" + OP_ITERATIONS + " iterations) ---");
        System.out.printf("%-18s %13s %13s %13s %15s %15s %12s%n",
                "Operator", "CG Loop", "CG NatRel", "TPop Direct", "TPop Unindexed", "TPop Indexed", "Matches");
        System.out.println("-".repeat(106));

        benchmarkSingleOperator("temporalBefore", TemporalRelation.isBefore, Temporal.precedes);
        benchmarkSingleOperator("temporalOverlaps", TemporalRelation.overlapsWith, Temporal.overlaps);
    }

    private void benchmarkSingleOperator(String opName, TemporalRelation cgRel, Temporal tpopRel) {
        long loopNanos = 0L;
        long natNanos = 0L;
        long directNanos = 0L;
        long unidxNanos = 0L;
        long idxNanos = 0L;
        long matches = 0L;

        for (int i = 0; i < OP_ITERATIONS; i++) {
            long s = System.nanoTime();
            matches = countChronoGraphLoop(cgRel);
            loopNanos += (System.nanoTime() - s);

            s = System.nanoTime();
            countChronoGraphNatRel(cgRel);
            natNanos += (System.nanoTime() - s);

            s = System.nanoTime();
            countDirectTinkerPop(tpopRel);
            directNanos += (System.nanoTime() - s);

            s = System.nanoTime();
            if ("temporalBefore".equals(opName)) {
                gUnindexed.E().hasLabel("flight").temporalBefore(refEdgeUnindexed).with(Temporal.ANY).count().next();
            } else {
                gUnindexed.E().hasLabel("flight").temporalOverlaps(refEdgeUnindexed).with(Temporal.ANY).count().next();
            }
            unidxNanos += (System.nanoTime() - s);

            s = System.nanoTime();
            if ("temporalBefore".equals(opName)) {
                gIndexed.E().hasLabel("flight").temporalBefore(refEdgeIndexed).with(Temporal.ANY).count().next();
            } else {
                gIndexed.E().hasLabel("flight").temporalOverlaps(refEdgeIndexed).with(Temporal.ANY).count().next();
            }
            idxNanos += (System.nanoTime() - s);
        }

        double div = 1_000_000.0 * OP_ITERATIONS;
        System.out.printf("%-18s %10.3f ms %10.3f ms %10.3f ms %12.3f ms %12.3f ms %12d%n",
                opName, loopNanos / div, natNanos / div, directNanos / div, unidxNanos / div, idxNanos / div, matches);
    }

    private long countChronoGraphNatRel(TemporalRelation rel) {
        long count = 0;
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            NavigableSet<?> matches = ce.getEvents(refPeriod, rel);
            if (matches != null && !matches.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private long countChronoGraphLoop(TemporalRelation rel) {
        long count = 0;
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            boolean matched = false;
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                if (tp.checkTemporalRelation(refPeriod, rel)) {
                    matched = true;
                    break;
                }
            }
            if (matched) count++;
        }
        return count;
    }

    private long countDirectTinkerPop(Temporal rel) {
        long count = 0;
        Iterator<Edge> it = unindexedGraph.edges();
        while (it.hasNext()) {
            Edge e = it.next();
            if (!"flight".equals(e.label())) continue;
            Lifetime eLt = Lifetime.getLifetimeFromProperties(e);
            if (eLt != null && !eLt.isEmpty() && rel.test(eLt, refLifetime, Temporal.Quantifier.ANY)) {
                count++;
            }
        }
        return count;
    }

    private int getChronoCount(TemporalBreadthFirstSearch tbfs) {
        try {
            Field f = TemporalBreadthFirstSearch.class.getDeclaredField("distances");
            f.setAccessible(true);
            Map<?, ?> distances = (Map<?, ?>) f.get(tbfs);
            return distances.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private void cleanup() {
        try {
            if (gUnindexed != null) gUnindexed.close();
            if (unindexedGraph != null) unindexedGraph.close();
            if (gIndexed != null) gIndexed.close();
            if (indexedGraph != null) indexedGraph.close();
        } catch (Exception ignored) {
        }
        unindexedGraph = null;
        gUnindexed = null;
        indexedGraph = null;
        gIndexed = null;
        chronoGraph = null;
        tSource = null;
        cSource = null;
    }
}
