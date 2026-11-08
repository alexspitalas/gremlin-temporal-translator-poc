package com.temporal.gremlin.bench;

import com.temporal.gremlin.benchmark.BenchmarkContext;
import com.temporal.gremlin.benchmark.BenchmarkGroup;
import com.temporal.gremlin.benchmark.BenchmarkTask;
import com.temporal.gremlin.benchmark.ScaleMode;
import com.tinkerpop.blueprints.TimePeriod;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.dfpl.chronograph.common.TemporalRelation;
import org.dfpl.chronograph.crud.memory.ChronoEdge;
import org.dfpl.chronograph.crud.memory.ChronoGraph;
import org.dfpl.chronograph.crud.memory.ChronoVertex;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.function.LongSupplier;

/**
 * Index & Traversal Selectivity Trade-off Benchmark for the T-TinkerPop journal paper
 * (Table VII / tab:index-selectivity-tradeoff).
 *
 * Honestly evaluates both the strengths and architectural weaknesses of T-TinkerPop:
 *   1. Ingestion Overhead (Write Penalty):
 *      Compares edge ingestion latency across T-TinkerPop Unindexed (primitive long[]),
 *      T-TinkerPop Indexed (TemporalTinkerIndex IntervalTree), and ChronoGraph (TreeSet)
 *      on multi-interval edges (k = 3 disjoint intervals per edge).
 *   2. Query Selectivity Crossover (0.01% to 75% selectivity on 500,000 edges, 1.5M intervals, temporalOverlaps):
 *      Demonstrates:
 *      - Strength: At low selectivity (0.01% - 5%), automated TemporalTinkerIndex routing
 *        achieves O(log |E| + K) sub-millisecond lookups, outperforming ChronoGraph and
 *        unindexed scans by 10x - 600x+.
 *      - Weakness 1: At broad selectivity (>= 25% - 75%), interval-tree candidate collection
 *        and deduplication become slower than sequential primitive long[] scans (~15% crossover).
 *      - Weakness 2: Unindexed Gremlin traversal (TPop Unindexed) incurs ~1.4x - 1.5x
 *        traverser/iterator overhead relative to raw storage iteration (TPop Direct).
 */
public class IndexSelectivityTradeoffBenchmark implements BenchmarkTask {

    private static final int WARMUP_ITERS = 3;
    private static final int BENCHMARK_ITERS = 10;
    private static final int QUERY_GRAPH_SIZE = 500_000;
    private static final int INTERVALS_PER_EDGE = 3;
    private static final int[] INGESTION_SIZES = {50_000, 100_000, 500_000};
    private static final int[] QUICK_INGESTION_SIZES = {5000, 20000};

    @Override
    public String getId() {
        return "index-selectivity";
    }

    @Override
    public String getName() {
        return "Table VII (tab:index-selectivity-tradeoff): Index Selectivity Crossover & Ingestion Overhead";
    }

    @Override
    public String getDescription() {
        return "Compares ingestion write penalty and query selectivity crossover across 0.01% to 75% on 500k edges (k=3).";
    }

    @Override
    public BenchmarkGroup getGroup() {
        return BenchmarkGroup.PAPER;
    }

    private int currentWarmupIters = WARMUP_ITERS;
    private int currentBenchmarkIters = BENCHMARK_ITERS;
    private int currentQueryGraphSize = QUERY_GRAPH_SIZE;
    private int[] currentIngestionSizes = INGESTION_SIZES;

    private static final long TIMELINE_START = 1_546_300_800_000L;
    private static final long STEP_SPAN = 100_000L;

    private static final double[] SELECTIVITY_FRACTIONS = {0.0001, 0.001, 0.01, 0.05, 0.10, 0.25, 0.50, 0.75};
    private static final String[] SELECTIVITY_LABELS = {"0.01%", "0.10%", "1.00%", "5.00%", "10.00%", "25.00%", "50.00%", "75.00%"};

    private static volatile Object sink;

    public static void main(String[] args) {
        new IndexSelectivityTradeoffBenchmark().run();
    }

    @Override
    public void run(BenchmarkContext ctx) {
        if (ctx == null) {
            ctx = new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null);
        }

        this.currentWarmupIters = ctx.getWarmupIterations(WARMUP_ITERS);
        this.currentBenchmarkIters = ctx.getIterations(BENCHMARK_ITERS);

        if (ctx.getScale() == ScaleMode.QUICK) {
            this.currentIngestionSizes = QUICK_INGESTION_SIZES;
            this.currentQueryGraphSize = 20_000;
        } else if (ctx.getScale() == ScaleMode.CUSTOM && ctx.getCustomSizes() != null && ctx.getCustomSizes().length > 0) {
            this.currentIngestionSizes = ctx.getCustomSizes();
            this.currentQueryGraphSize = ctx.getCustomSizes()[ctx.getCustomSizes().length - 1];
        } else {
            this.currentIngestionSizes = INGESTION_SIZES;
            this.currentQueryGraphSize = QUERY_GRAPH_SIZE;
        }

        ctx.println("========================================================================================================================");
        ctx.println("  T-TINKERPOP STRENGTHS & WEAKNESSES: INDEX INGESTION OVERHEAD & SELECTIVITY CROSSOVER BENCHMARK");
        ctx.println("========================================================================================================================");

        runIngestionBenchmark();
        runSelectivityCrossoverBenchmark();
    }

    public void run() {
        run(new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null));
    }

    private void runIngestionBenchmark() {
        System.out.println("\n--- Part 1: Write/Ingestion Latency Trade-off (Unindexed vs TemporalTinkerIndex vs ChronoGraph) ---");
        System.out.printf("%-12s %18s %18s %18s %18s%n",
                "Edges", "TPop Unindexed", "TPop Indexed", "ChronoGraph", "Index Write Cost");
        System.out.println("-".repeat(90));

        for (int n : currentIngestionSizes) {
            // Warmup once at small scale
            if (n == currentIngestionSizes[0]) {
                buildUnindexedGraph(Math.min(10_000, n)).close();
                buildIndexedGraph(Math.min(10_000, n)).close();
                buildChronoGraph(Math.min(10_000, n));
            }

            long t0 = System.nanoTime();
            TinkerGraph uGraph = buildUnindexedGraph(n);
            double unidxMs = (System.nanoTime() - t0) / 1_000_000.0;
            uGraph.close();

            long t1 = System.nanoTime();
            TinkerGraph iGraph = buildIndexedGraph(n);
            double idxMs = (System.nanoTime() - t1) / 1_000_000.0;
            iGraph.close();

            long t2 = System.nanoTime();
            ChronoGraph cGraph = buildChronoGraph(n);
            double cgMs = (System.nanoTime() - t2) / 1_000_000.0;
            sink = cGraph;

            System.out.printf("%-12d %15.2f ms %15.2f ms %15.2f ms %17.2fx%n",
                    n, unidxMs, idxMs, cgMs, idxMs / Math.max(unidxMs, 0.001));
        }
    }

    private void runSelectivityCrossoverBenchmark() {
        System.out.println("\n--- Part 2: Query Selectivity Crossover on " + currentQueryGraphSize
                + " Multi-Interval Edges (k=" + INTERVALS_PER_EDGE + ", Operator: temporalOverlaps) ---");
        System.out.printf("%-12s %12s %15s %15s %15s %15s %15s %15s %15s%n",
                "Selectivity", "Matches", "TPop Indexed", "TPop Unindexed", "TPop Direct",
                "CG NatRel", "CG Loop", "Idx vs Unidx", "Idx vs CG");
        System.out.println("-".repeat(138));

        TinkerGraph unindexedGraph = buildUnindexedGraph(currentQueryGraphSize);
        TinkerGraph indexedGraph = buildIndexedGraph(currentQueryGraphSize);
        ChronoGraph chronoGraph = buildChronoGraph(currentQueryGraphSize);

        GraphTraversalSource gUnindexed = unindexedGraph.traversal();
        GraphTraversalSource gIndexed = indexedGraph.traversal();

        try {
            for (int sIdx = 0; sIdx < SELECTIVITY_FRACTIONS.length; sIdx++) {
                double frac = SELECTIVITY_FRACTIONS[sIdx];
                String label = SELECTIVITY_LABELS[sIdx];

                int targetMatches = Math.max(1, (int) Math.round(currentQueryGraphSize * frac));
                long winStart = TIMELINE_START + currentQueryGraphSize
                        + (long) (currentQueryGraphSize - targetMatches) * STEP_SPAN + (STEP_SPAN / 2);
                long winEnd = TIMELINE_START + currentQueryGraphSize
                        + (long) (currentQueryGraphSize + 1) * STEP_SPAN;

                Lifetime refLt = Lifetime.from(winStart, winEnd);
                TimePeriod refPeriod = new TimePeriod(winStart, winEnd);

                // Verify exact match-count parity across all 5 methods
                long cIdx = gIndexed.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next();
                long cUnidx = gUnindexed.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next();
                long cDirect = countDirect(unindexedGraph, refLt, Temporal.overlaps);
                long cCgNat = countChronoGraphNatRel(chronoGraph, refPeriod, TemporalRelation.overlapsWith);
                long cCgLoop = countChronoGraphLoop(chronoGraph, refPeriod, TemporalRelation.overlapsWith);

                if (cIdx <= 0 || cIdx != cUnidx || cUnidx != cDirect || cDirect != cCgNat || cCgNat != cCgLoop) {
                    throw new IllegalStateException(String.format(
                            "Selectivity parity mismatch at %s: Indexed=%d, Unindexed=%d, Direct=%d, CG NatRel=%d, CG Loop=%d",
                            label, cIdx, cUnidx, cDirect, cCgNat, cCgLoop));
                }

                // Warmup
                for (int w = 0; w < currentWarmupIters; w++) {
                    sink = gIndexed.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next();
                    sink = gUnindexed.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next();
                    sink = countDirect(unindexedGraph, refLt, Temporal.overlaps);
                    sink = countChronoGraphNatRel(chronoGraph, refPeriod, TemporalRelation.overlapsWith);
                    sink = countChronoGraphLoop(chronoGraph, refPeriod, TemporalRelation.overlapsWith);
                }

                double idxMs = measureMs(() -> gIndexed.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next(), currentBenchmarkIters);
                double unidxMs = measureMs(() -> gUnindexed.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next(), currentBenchmarkIters);
                double directMs = measureMs(() -> countDirect(unindexedGraph, refLt, Temporal.overlaps), currentBenchmarkIters);
                double cgNatMs = measureMs(() -> countChronoGraphNatRel(chronoGraph, refPeriod, TemporalRelation.overlapsWith), currentBenchmarkIters);
                double cgLoopMs = measureMs(() -> countChronoGraphLoop(chronoGraph, refPeriod, TemporalRelation.overlapsWith), currentBenchmarkIters);

                double spUnidx = unidxMs / Math.max(idxMs, 0.0001);
                double spCg = cgNatMs / Math.max(idxMs, 0.0001);
                System.out.printf("%-12s %12d %12.3f ms %12.3f ms %12.3f ms %12.3f ms %12.3f ms %14.2fx %14.2fx%n",
                        label, cIdx, idxMs, unidxMs, directMs, cgNatMs, cgLoopMs, spUnidx, spCg);
            }
        } finally {
            try {
                gUnindexed.close();
                unindexedGraph.close();
                gIndexed.close();
                indexedGraph.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Lifetime buildEdgeLifetime(int edgeIndex, int numEdges) {
        List<Lifetime.Interval> ivs = new ArrayList<>(INTERVALS_PER_EDGE);
        // First two disjoint intervals occur strictly prior to TIMELINE_START
        ivs.add(new Lifetime.Interval(TIMELINE_START - 50_000L, TIMELINE_START - 40_000L));
        ivs.add(new Lifetime.Interval(TIMELINE_START - 30_000L, TIMELINE_START - 20_000L));
        // Third interval has start s3 < all query winStarts and end e3 decreasing with edgeIndex,
        // so only the first K edges intersect [winStart, winEnd] in the IntervalTree and satisfy overlaps.
        long s3 = TIMELINE_START + edgeIndex;
        long e3 = TIMELINE_START + numEdges + (long) (numEdges - edgeIndex) * STEP_SPAN;
        ivs.add(new Lifetime.Interval(s3, e3));
        return Lifetime.from(ivs);
    }

    private TinkerGraph buildUnindexedGraph(int numEdges) {
        TinkerGraph graph = TinkerGraph.open();
        Vertex u = graph.addVertex(T.label, "entity");
        Vertex v = graph.addVertex(T.label, "entity");
        for (int i = 0; i < numEdges; i++) {
            Edge edge = u.addEdge("event", v);
            buildEdgeLifetime(i, numEdges).attachTo(edge);
        }
        return graph;
    }

    private TinkerGraph buildIndexedGraph(int numEdges) {
        TinkerGraph graph = TinkerGraph.open();
        graph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);
        Vertex u = graph.addVertex(T.label, "entity");
        Vertex v = graph.addVertex(T.label, "entity");
        for (int i = 0; i < numEdges; i++) {
            Edge edge = u.addEdge("event", v);
            buildEdgeLifetime(i, numEdges).attachTo(edge);
        }
        return graph;
    }

    private ChronoGraph buildChronoGraph(int numEdges) {
        ChronoGraph cg = new ChronoGraph();
        ChronoVertex u = (ChronoVertex) cg.addVertex("u");
        for (int i = 0; i < numEdges; i++) {
            ChronoVertex v = (ChronoVertex) cg.addVertex("v" + i);
            ChronoEdge ce = (ChronoEdge) cg.addEdge(u, v, "event");
            Lifetime lt = buildEdgeLifetime(i, numEdges);
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }
        }
        return cg;
    }

    private long countDirect(TinkerGraph graph, Lifetime refLt, Temporal op) {
        long count = 0;
        Iterator<Edge> it = graph.edges();
        while (it.hasNext()) {
            Edge e = it.next();
            if (!"event".equals(e.label())) continue;
            Lifetime eLt = Lifetime.getLifetimeFromProperties(e);
            if (eLt != null && !eLt.isEmpty() && op.test(eLt, refLt, Temporal.Quantifier.ANY)) {
                count++;
            }
        }
        return count;
    }

    private long countChronoGraphNatRel(ChronoGraph cg, TimePeriod refPeriod, TemporalRelation rel) {
        long count = 0;
        for (com.tinkerpop.blueprints.Edge e : cg.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            NavigableSet<?> matches = ce.getEvents(refPeriod, rel);
            if (matches != null && !matches.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private long countChronoGraphLoop(ChronoGraph cg, TimePeriod refPeriod, TemporalRelation rel) {
        long count = 0;
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        for (com.tinkerpop.blueprints.Edge e : cg.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            boolean matched = false;
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                if (tp.checkTemporalRelation(refPeriod, rel)) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                count++;
            }
        }
        return count;
    }

    private double measureMs(LongSupplier op, int iters) {
        long totalNanos = 0L;
        for (int i = 0; i < iters; i++) {
            long s = System.nanoTime();
            sink = op.getAsLong();
            totalNanos += (System.nanoTime() - s);
        }
        return totalNanos / (iters * 1_000_000.0);
    }
}
