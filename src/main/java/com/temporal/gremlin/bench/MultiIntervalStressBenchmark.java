package com.temporal.gremlin.bench;

import com.temporal.gremlin.benchmark.BenchmarkContext;
import com.temporal.gremlin.benchmark.BenchmarkGroup;
import com.temporal.gremlin.benchmark.BenchmarkTask;
import com.temporal.gremlin.benchmark.ScaleMode;
import com.tinkerpop.blueprints.TimePeriod;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.dfpl.chronograph.common.TemporalRelation;
import org.dfpl.chronograph.crud.memory.ChronoEdge;
import org.dfpl.chronograph.crud.memory.ChronoGraph;
import org.dfpl.chronograph.crud.memory.ChronoVertex;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Multi-Interval Stress Test for Section V of the T-TinkerPop paper.
 *
 * Compares T-TinkerPop against ChronoGraph and Naive Cartesian baselines
 * on graphs where edges have k disjoint intervals (k in {1, 2, 5, 10}).
 *
 * Benchmarks:
 *   1. T-TinkerPop ANY regime: g.E().temporalOverlaps(ref).with(Temporal.ANY)
 *   2. T-TinkerPop ALL regime: g.E().temporalOverlaps(ref).with(Temporal.ALL)
 *   3. T-TinkerPop Direct Sweep: O(k log k) binary search sweep
 *   4. ChronoGraph NatRel API: ce.getEvents(refPeriod, TemporalRelation.overlapsWith)
 *   5. ChronoGraph Direct Loop: manual event iteration
 *   6. Naive Cartesian Product: O(k^2) brute force
 */
public class MultiIntervalStressBenchmark implements BenchmarkTask {

    private static final int WARMUP_ITERS = 3;
    private static final int DEFAULT_BENCHMARK_ITERS = 10;
    private static final int LARGE_BENCHMARK_ITERS = 5;

    private static final int[] EDGE_COUNTS = {50000, 100000, 500000, 1000000};
    private static final int[] QUICK_EDGE_COUNTS = {5000, 20000};
    private static final int[] K_VALUES = {1, 2, 5, 10};
    private static final int[] QUICK_K_VALUES = {1, 2, 5};

    @Override
    public String getId() {
        return "multi-interval";
    }

    @Override
    public String getName() {
        return "Table IX (tab:multi-interval-chronograph): Multi-Interval Traversal Scalability (k=1..10)";
    }

    @Override
    public String getDescription() {
        return "Evaluates scaling with k disjoint intervals across ANY, ALL, plane-sweep, and ChronoGraph.";
    }

    @Override
    public BenchmarkGroup getGroup() {
        return BenchmarkGroup.PAPER;
    }

    private int currentWarmupIters = WARMUP_ITERS;
    private int currentDefaultIters = DEFAULT_BENCHMARK_ITERS;
    private int currentLargeIters = LARGE_BENCHMARK_ITERS;

    private static final long DAY_MS = 86_400_000L;
    private static final long REF_START = 1_000_000L;
    private static final long REF_SPAN  = 50_000L;

    private static volatile Object sink;

    private Graph graph;
    private GraphTraversalSource g;
    private Edge referenceEdge;
    private List<Lifetime.Interval> refIntervals;
    private Lifetime refLifetime;

    private ChronoGraph chronoGraph;
    private List<TimePeriod> refPeriods;

    public static void main(String[] args) {
        new MultiIntervalStressBenchmark().run();
    }

    @Override
    public void run(BenchmarkContext ctx) {
        if (ctx == null) {
            ctx = new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null);
        }

        this.currentWarmupIters = ctx.getWarmupIterations(WARMUP_ITERS);
        this.currentDefaultIters = ctx.getIterations(DEFAULT_BENCHMARK_ITERS);
        this.currentLargeIters = ctx.getIterations(LARGE_BENCHMARK_ITERS);

        int[] edgeCounts = ctx.getSizes(getId(), EDGE_COUNTS, QUICK_EDGE_COUNTS);
        int[] kValues = (ctx.getScale() == ScaleMode.QUICK) ? QUICK_K_VALUES : K_VALUES;

        ctx.println("==========================================================================================================");
        ctx.println("  T-TINKERPOP VS CHRONOGRAPH MULTI-INTERVAL STRESS TEST (k in {1, 2, 5, 10})");
        ctx.println("  Evaluating multi-interval scaling: T-TinkerPop (ANY/ALL) vs ChronoGraph (NatRel/Loop) vs Naive O(k^2)");
        ctx.println("==========================================================================================================");

        for (int edgeCount : edgeCounts) {
            System.out.printf("%n=== Graph Size: %d edges ===%n", edgeCount);
            System.out.println("┌──────┬──────────────────┬──────────────────┬──────────────────┬──────────────────┬──────────────────┬─────────────────┐");
            System.out.println("│  k   │  TPop ANY (ms)   │  TPop ALL (ms)   │ TPop Sweep (ms)  │   CG NatRel (ms) │   CG Loop (ms)   │  Naive O(k^2) ms│");
            System.out.println("├──────┼──────────────────┼──────────────────┼──────────────────┼──────────────────┼──────────────────┼─────────────────┤");

            List<BenchmarkRow> rows = new ArrayList<>();

            for (int k : kValues) {
                try {
                    initializeGraph(edgeCount, k);
                    verifyParity(edgeCount, k);
                    warmup();

                    int iters = edgeCount >= 500000 ? currentLargeIters : currentDefaultIters;

                    double anyMs = measureAverageMs(() ->
                            g.E().hasLabel("event").temporalOverlaps(referenceEdge).with(Temporal.ANY).count().next(), iters);

                    double allMs = measureAverageMs(() ->
                            g.E().hasLabel("event").temporalOverlaps(referenceEdge).with(Temporal.ALL).count().next(), iters);

                    double sweepMs = measureAverageMs(this::runDirectSweepScan, iters);

                    double cgNatMs = measureAverageMs(this::runChronoGraphNatRel, iters);

                    double cgLoopMs = measureAverageMs(this::runChronoGraphLoop, iters);

                    double naiveMs = measureAverageMs(this::runNaiveCartesianScan, iters);

                    rows.add(new BenchmarkRow(k, anyMs, allMs, sweepMs, cgNatMs, cgLoopMs, naiveMs));

                    System.out.printf("│ %4d │ %16.3f │ %16.3f │ %16.3f │ %16.3f │ %16.3f │ %15.3f │%n",
                            k, anyMs, allMs, sweepMs, cgNatMs, cgLoopMs, naiveMs);
                } finally {
                    cleanup();
                }
            }
            System.out.println("└──────┴──────────────────┴──────────────────┴──────────────────┴──────────────────┴──────────────────┴─────────────────┘");

            printLatexSnippet(edgeCount, rows);
        }
    }

    public void run() {
        run(new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null));
    }

    private void initializeGraph(int edgeCount, int k) {
        graph = TinkerGraph.open();
        g = graph.traversal();
        chronoGraph = new ChronoGraph();

        int numVertices = edgeCount + 1;
        Vertex[] vertices = new Vertex[numVertices];
        ChronoVertex[] cVertices = new ChronoVertex[numVertices];

        for (int i = 0; i < numVertices; i++) {
            vertices[i] = graph.addVertex(T.label, "entity", "name", "v" + i);
            cVertices[i] = (ChronoVertex) chronoGraph.addVertex("v" + i);
        }

        // Reference edge with k disjoint intervals (distinct label so target "event" scans skip it)
        Vertex refOut = vertices[0];
        Vertex refIn = vertices[1];
        refIntervals = generateDisjointIntervals(REF_START, REF_SPAN, k);
        refLifetime = Lifetime.from(refIntervals);

        referenceEdge = g.addE("refEvent").from(refOut).to(refIn)
                .property("ref", true)
                .next();
        refLifetime.attachTo(referenceEdge);

        refPeriods = new ArrayList<>(refLifetime.getIntervalCount());
        for (int idx = 0; idx < refLifetime.getIntervalCount(); idx++) {
            refPeriods.add(new TimePeriod(refLifetime.getStartMillis(idx), refLifetime.getEndMillis(idx)));
        }

        // Build target edges each with k disjoint intervals
        for (int i = 1; i < edgeCount; i++) {
            int uIdx = i;
            int vIdx = i + 1;

            Vertex out = vertices[uIdx];
            Vertex in = vertices[vIdx];

            long baseOffset = (i * 12345L) % (DAY_MS * 10);
            List<Lifetime.Interval> intervals = generateDisjointIntervals(baseOffset, REF_SPAN, k);

            // 1. T-TinkerPop
            Lifetime lt = Lifetime.from(intervals);
            Edge e = g.addE("event").from(out).to(in).next();
            lt.attachTo(e);

            // 2. ChronoGraph
            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cVertices[uIdx], cVertices[vIdx], "event");
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }
        }
    }

    private void verifyParity(int edgeCount, int k) {
        long anyCount = g.E().hasLabel("event").temporalOverlaps(referenceEdge).with(Temporal.ANY).count().next();
        long sweepCount = runDirectSweepScan();
        long cgNatCount = runChronoGraphNatRel();
        long cgLoopCount = runChronoGraphLoop();
        long naiveCount = runNaiveCartesianScan();

        if (anyCount != sweepCount || sweepCount != cgNatCount || cgNatCount != cgLoopCount || cgLoopCount != naiveCount) {
            throw new IllegalStateException(String.format(
                    "MultiInterval parity mismatch at edgeCount=%d k=%d: TPopANY=%d, Sweep=%d, CGNatRel=%d, CGLoop=%d, Naive=%d",
                    edgeCount, k, anyCount, sweepCount, cgNatCount, cgLoopCount, naiveCount));
        }
    }

    private List<Lifetime.Interval> generateDisjointIntervals(long baseOffset, long span, int k) {
        List<Lifetime.Interval> intervals = new ArrayList<>(k);
        long gap = span / 2;
        long currentStart = baseOffset;
        for (int i = 0; i < k; i++) {
            long currentEnd = currentStart + span;
            intervals.add(new Lifetime.Interval(new Date(currentStart), new Date(currentEnd)));
            currentStart = currentEnd + gap;
        }
        return intervals;
    }

    private long runDirectSweepScan() {
        long count = 0;
        Iterator<Edge> it = graph.edges();
        Lifetime targetLt = refLifetime;
        while (it.hasNext()) {
            Edge e = it.next();
            if (e.equals(referenceEdge)) continue;
            Lifetime eLt = Lifetime.getLifetimeFromProperties(e);
            if (eLt == null || eLt.isEmpty()) continue;
            if (Temporal.overlaps.test(eLt, targetLt, Temporal.Quantifier.ANY)) {
                count++;
            }
        }
        return count;
    }

    private long runChronoGraphNatRel() {
        long count = 0;
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            boolean matched = false;
            for (int i = 0, n = refPeriods.size(); i < n; i++) {
                java.util.NavigableSet<?> matches = ce.getEvents(refPeriods.get(i), TemporalRelation.overlapsWith);
                if (matches != null && !matches.isEmpty()) {
                    matched = true;
                    break;
                }
            }
            if (matched) count++;
        }
        return count;
    }

    private long runChronoGraphLoop() {
        long count = 0;
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            boolean matched = false;
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                for (int i = 0, n = refPeriods.size(); i < n; i++) {
                    if (tp.checkTemporalRelation(refPeriods.get(i), TemporalRelation.overlapsWith)) {
                        matched = true;
                        break;
                    }
                }
                if (matched) break;
            }
            if (matched) count++;
        }
        return count;
    }

    private long runNaiveCartesianScan() {
        long count = 0;
        Iterator<Edge> it = graph.edges();
        while (it.hasNext()) {
            Edge e = it.next();
            if (e.equals(referenceEdge)) continue;
            Lifetime eLt = Lifetime.getLifetimeFromProperties(e);
            if (eLt == null || eLt.isEmpty()) continue;

            List<Lifetime.Interval> xs = eLt.getIntervals();
            List<Lifetime.Interval> ys = refIntervals;

            boolean overlapsAny = false;
            for (Lifetime.Interval x : xs) {
                for (Lifetime.Interval y : ys) {
                    if (x.getStart().before(y.getStart())
                            && x.getEnd().after(y.getStart())
                            && x.getEnd().before(y.getEnd())) {
                        overlapsAny = true;
                        break;
                    }
                }
                if (overlapsAny) break;
            }
            if (overlapsAny) count++;
        }
        return count;
    }

    private void warmup() {
        for (int i = 0; i < currentWarmupIters; i++) {
            sink = g.E().hasLabel("event").temporalOverlaps(referenceEdge).with(Temporal.ANY).count().next();
            sink = g.E().hasLabel("event").temporalOverlaps(referenceEdge).with(Temporal.ALL).count().next();
            sink = runDirectSweepScan();
            sink = runChronoGraphNatRel();
            sink = runChronoGraphLoop();
            sink = runNaiveCartesianScan();
        }
    }

    private double measureAverageMs(LongSupplier op, int iters) {
        long totalNanos = 0L;
        for (int i = 0; i < iters; i++) {
            long start = System.nanoTime();
            sink = op.getAsLong();
            totalNanos += (System.nanoTime() - start);
        }
        return totalNanos / (iters * 1_000_000.0);
    }

    private void cleanup() {
        if (g != null) { try { g.close(); } catch (Exception ignored) {} }
        if (graph != null) { try { graph.close(); } catch (Exception ignored) {} }
        graph = null;
        g = null;
        referenceEdge = null;
        refIntervals = null;
        refLifetime = null;
        chronoGraph = null;
        refPeriods = null;
    }

    private void printLatexSnippet(int edgeCount, List<BenchmarkRow> rows) {
        System.out.printf("%n%% --- LaTeX Table Snippet for %d Edges ---%n", edgeCount);
        System.out.println("\\begin{table}[htbp]");
        System.out.printf("\\caption{Multi-interval evaluation comparison across sub-interval counts $k$ on %d edges (Runtime in ms).}%n", edgeCount);
        System.out.println("\\label{tab:multi-interval-chronograph}");
        System.out.println("\\centering\\small");
        System.out.println("\\begin{tabular}{rrrrrrr}");
        System.out.println("\\toprule");
        System.out.println("$k$ & TPop ANY & TPop ALL & TPop Sweep & CG NatRel & CG Loop & Naive $O(k^2)$ \\\\");
        System.out.println("\\midrule");
        for (BenchmarkRow r : rows) {
            System.out.printf("%d & %.2f & %.2f & %.2f & %.2f & %.2f & %.2f \\\\%n",
                    r.k, r.anyMs, r.allMs, r.sweepMs, r.cgNatMs, r.cgLoopMs, r.naiveMs);
        }
        System.out.println("\\bottomrule");
        System.out.println("\\end{tabular}");
        System.out.println("\\end{table}\n");
    }

    private static class BenchmarkRow {
        final int k;
        final double anyMs;
        final double allMs;
        final double sweepMs;
        final double cgNatMs;
        final double cgLoopMs;
        final double naiveMs;

        BenchmarkRow(int k, double anyMs, double allMs, double sweepMs, double cgNatMs, double cgLoopMs, double naiveMs) {
            this.k = k;
            this.anyMs = anyMs;
            this.allMs = allMs;
            this.sweepMs = sweepMs;
            this.cgNatMs = cgNatMs;
            this.cgLoopMs = cgLoopMs;
            this.naiveMs = naiveMs;
        }
    }
}
