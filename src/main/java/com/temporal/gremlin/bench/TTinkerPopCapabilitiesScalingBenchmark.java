package com.temporal.gremlin.bench;

import com.temporal.gremlin.benchmark.BenchmarkContext;
import com.temporal.gremlin.benchmark.BenchmarkGroup;
import com.temporal.gremlin.benchmark.BenchmarkTask;
import com.temporal.gremlin.benchmark.ScaleMode;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.TemporalP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Benchmarks scaling of T-TinkerPop-unique temporal capabilities that ChronoGraph does not support:
 * 1) Ligozat Generalized (p,q)-Calculus: Convex lattice intervals [alpha, beta] (TemporalP.between),
 *    generalized multi-interval (4,4)-zone vectors (TemporalP.generalized), and multi-branch .or(...) disjunctions.
 * 2) Historical Subgraph Views & Temporal Properties: Snapshot views atTime(t), windowed views window(t1, t2),
 *    multi-versioned vertex properties lifetimeProperty(...), and manual step-by-step filter chains.
 * 3) Temporal Path-Finding Optimality Criteria: tbfs(EARLIEST_ARRIVAL, EARLIEST_DEPARTURE, LATEST_DEPARTURE, LATEST_ARRIVAL)
 *    and bounded-waiting-time multi-hop journeys sequentialPath(minDelay, maxDelay).
 */
public class TTinkerPopCapabilitiesScalingBenchmark implements BenchmarkTask {

    private static final int[] GRAPH_SIZES = {10_000, 50_000, 100_000, 500_000, 1_000_000};
    private static final int[] QUICK_GRAPH_SIZES = {5000, 20000};
    private static final int WARMUP_ITERS  = 4;
    private static final int MEASURE_ITERS = 8;

    @Override
    public String getId() {
        return "capabilities";
    }

    @Override
    public String getName() {
        return "Table XI: Scaling of T-TinkerPop Unique Capabilities";
    }

    @Override
    public String getDescription() {
        return "Evaluates Ligozat (p,q)-calculus, historical subgraph views, and temporal path optimality criteria.";
    }

    @Override
    public BenchmarkGroup getGroup() {
        return BenchmarkGroup.PAPER;
    }

    private static int currentWarmupIters = WARMUP_ITERS;
    private static int currentMeasureIters = MEASURE_ITERS;

    private static final long BASE_TIME = 1_700_000_000_000L;
    private static final long TIME_SPAN = 86_400_000L * 30; // 30 days in ms

    private static class LigozatRow {
        final int numEdges;
        final double atomicOverlapsMs;
        final double convexBetweenMs;
        final double generalizedZoneMs;
        final double multiBranchOrMs;
        final double speedupVsOr;

        LigozatRow(int numEdges, double atomicOverlapsMs, double convexBetweenMs,
                   double generalizedZoneMs, double multiBranchOrMs) {
            this.numEdges = numEdges;
            this.atomicOverlapsMs = atomicOverlapsMs;
            this.convexBetweenMs = convexBetweenMs;
            this.generalizedZoneMs = generalizedZoneMs;
            this.multiBranchOrMs = multiBranchOrMs;
            this.speedupVsOr = multiBranchOrMs / Math.max(convexBetweenMs, 1e-6);
        }
    }

    private static class ViewRow {
        final int numEdges;
        final double atTimeHopMs;
        final double windowHopMs;
        final double manualFilterHopMs;
        final double temporalPropWindowMs;

        ViewRow(int numEdges, double atTimeHopMs, double windowHopMs,
                double manualFilterHopMs, double temporalPropWindowMs) {
            this.numEdges = numEdges;
            this.atTimeHopMs = atTimeHopMs;
            this.windowHopMs = windowHopMs;
            this.manualFilterHopMs = manualFilterHopMs;
            this.temporalPropWindowMs = temporalPropWindowMs;
        }
    }

    private static class PathRow {
        final int numEdges;
        final double earliestArrivalMs;
        final double earliestDepartureMs;
        final double latestDepartureMs;
        final double latestArrivalMs;
        final double sequentialPath2HopMs;

        PathRow(int numEdges, double earliestArrivalMs, double earliestDepartureMs,
                double latestDepartureMs, double latestArrivalMs, double sequentialPath2HopMs) {
            this.numEdges = numEdges;
            this.earliestArrivalMs = earliestArrivalMs;
            this.earliestDepartureMs = earliestDepartureMs;
            this.latestDepartureMs = latestDepartureMs;
            this.latestArrivalMs = latestArrivalMs;
            this.sequentialPath2HopMs = sequentialPath2HopMs;
        }
    }

    public static void main(String[] args) {
        new TTinkerPopCapabilitiesScalingBenchmark().run();
    }

    @Override
    public void run(BenchmarkContext ctx) {
        if (ctx == null) {
            ctx = new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null);
        }

        currentWarmupIters = ctx.getWarmupIterations(WARMUP_ITERS);
        currentMeasureIters = ctx.getIterations(MEASURE_ITERS);

        int[] sizes = ctx.getSizes(getId(), GRAPH_SIZES, QUICK_GRAPH_SIZES);

        ctx.println("================================================================================================================");
        ctx.println("  T-TINKERPOP CAPABILITIES SCALING BENCHMARK (LIGOAT CALCULUS, HISTORICAL VIEWS, TEMPORAL PATH TARGETS)");
        ctx.println("================================================================================================================");

        List<LigozatRow> ligozatRows = new ArrayList<>();
        List<ViewRow> viewRows       = new ArrayList<>();
        List<PathRow> pathRows       = new ArrayList<>();

        for (int numEdges : sizes) {
            runForSize(numEdges, ligozatRows, viewRows, pathRows);
        }

        printLaTeXTables(ligozatRows, viewRows, pathRows);
    }

    public void run() {
        run(new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null));
    }

    private static void runForSize(int numEdges,
                                   List<LigozatRow> ligozatRows,
                                   List<ViewRow> viewRows,
                                   List<PathRow> pathRows) {
        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        Random rng = new Random(42L + numEdges);

        int numVertices = Math.max(500, numEdges / 20);
        Vertex[] vertices = new Vertex[numVertices];
        for (int i = 0; i < numVertices; i++) {
            Vertex v = graph.addVertex(T.label, "Station", "code", "S" + i);
            long vStart = BASE_TIME;
            long vEnd   = BASE_TIME + TIME_SPAN;
            Lifetime.from(new Date(vStart), new Date(vEnd)).attachTo(v);

            // Attach 2 temporal property versions per vertex ("status" in H1, "alert" in H2)
            long mid = BASE_TIME + (TIME_SPAN / 2);
            g.V(v).lifetimeProperty("status", "NORMAL", new Date(BASE_TIME), new Date(mid)).iterate();
            g.V(v).lifetimeProperty("alert", "CONGESTED", new Date(mid + 1), new Date(BASE_TIME + TIME_SPAN)).iterate();
            vertices[i] = v;
        }

        // Populate edges with 2 coalesced intervals (4 endpoints = 4-interval in Ligozat Pi(4,4))
        for (int i = 0; i < numEdges; i++) {
            Vertex u = vertices[rng.nextInt(numVertices)];
            Vertex v = vertices[rng.nextInt(numVertices)];
            if (u == v) v = vertices[(i + 1) % numVertices];

            long s1 = BASE_TIME + (Math.abs(rng.nextLong()) % (TIME_SPAN / 2));
            long d1 = 600_000L + (Math.abs(rng.nextLong()) % 3_600_000L);
            long e1 = s1 + d1;
            long s2 = e1 + 3_600_000L + (Math.abs(rng.nextLong()) % 7_200_000L);
            long d2 = 600_000L + (Math.abs(rng.nextLong()) % 3_600_000L);
            long e2 = s2 + d2;

            Edge edge = u.addEdge("event", v);
            List<Lifetime.Interval> ivs = Arrays.asList(
                    new Lifetime.Interval(new Date(s1), new Date(e1)),
                    new Lifetime.Interval(new Date(s2), new Date(e2))
            );
            Lifetime.from(ivs).attachTo(edge);
        }

        // Reference single-interval lifetime for Allen & Convex [precedes, overlaps]
        long refStart = BASE_TIME + (TIME_SPAN / 4);
        long refEnd   = refStart + (TIME_SPAN / 8);
        Lifetime refSingle = Lifetime.from(new Date(refStart), new Date(refEnd));

        // Reference 2-interval lifetime (4 endpoints -> 9 zones 0..8) for Ligozat (4,4) relation
        Lifetime refMulti2 = Lifetime.from(Arrays.asList(
                new Lifetime.Interval(new Date(refStart), new Date(refEnd)),
                new Lifetime.Interval(new Date(refEnd + 7_200_000L), new Date(refEnd + 14_400_000L))
        ));
        // A (4,4)-relation in Pi(4,4): first interval before refMulti2 (0,0), second interval after first sub-interval (4,4)
        GeneralizedRelation rel44 = new GeneralizedRelation(4, 4, 0, 0, 4, 4);

        // Verify parity between Ligozat Convex [precedes, overlaps] and Multi-Branch .or(before, meets, overlaps)
        // Using single-interval reference & ANY quantifier
        long convexCount = g.E().hasLabel("event")
                .has(Lifetime.TEMPORAL_INTERVALS, TemporalP.between(Temporal.precedes, Temporal.overlaps, refSingle).any())
                .count().next();
        long orCount = g.E().hasLabel("event")
                .or(
                        __.temporalBefore(refSingle).with(Temporal.ANY),
                        __.temporalMeets(refSingle).with(Temporal.ANY),
                        __.temporalOverlaps(refSingle).with(Temporal.ANY)
                ).count().next();
        if (convexCount != orCount) {
            throw new IllegalStateException("Ligozat convex parity mismatch: convex=" + convexCount + ", or=" + orCount);
        }

        // Warmup Suite A
        for (int i = 0; i < currentWarmupIters; i++) {
            g.E().hasLabel("event").temporalOverlaps(refSingle).with(Temporal.ANY).count().next();
            g.E().hasLabel("event").has(Lifetime.TEMPORAL_INTERVALS, TemporalP.between(Temporal.precedes, Temporal.overlaps, refSingle).any()).count().next();
            g.E().hasLabel("event").has(Lifetime.TEMPORAL_INTERVALS, TemporalP.generalized(refMulti2, rel44)).count().next();
            g.E().hasLabel("event").or(__.temporalBefore(refSingle).with(Temporal.ANY), __.temporalMeets(refSingle).with(Temporal.ANY), __.temporalOverlaps(refSingle).with(Temporal.ANY)).count().next();
        }

        double atomicMs = measureMs(() -> g.E().hasLabel("event").temporalOverlaps(refSingle).with(Temporal.ANY).count().next());
        double convexMs = measureMs(() -> g.E().hasLabel("event").has(Lifetime.TEMPORAL_INTERVALS, TemporalP.between(Temporal.precedes, Temporal.overlaps, refSingle).any()).count().next());
        double gen44Ms  = measureMs(() -> g.E().hasLabel("event").has(Lifetime.TEMPORAL_INTERVALS, TemporalP.generalized(refMulti2, rel44)).count().next());
        double orMs     = measureMs(() -> g.E().hasLabel("event").or(
                __.temporalBefore(refSingle).with(Temporal.ANY),
                __.temporalMeets(refSingle).with(Temporal.ANY),
                __.temporalOverlaps(refSingle).with(Temporal.ANY)
        ).count().next());

        ligozatRows.add(new LigozatRow(numEdges, atomicMs, convexMs, gen44Ms, orMs));

        // Suite B: Historical Subgraph Views & Temporal Vertex Properties
        Date snapInstant = new Date(refStart + 1_800_000L);
        Date winStart    = new Date(refStart);
        Date winEnd      = new Date(refEnd);
        Lifetime winLt   = Lifetime.from(winStart, winEnd);

        // Verify parity between window(wStart, wEnd) view hop and manual step filter hop
        long winHopCount = g.V().window(winStart, winEnd).out("event").count().next();
        long manualHopCount = g.V().temporalIntersects(winLt)
                .outE("event").temporalIntersects(winLt)
                .inV().temporalIntersects(winLt)
                .count().next();
        if (winHopCount != manualHopCount) {
            throw new IllegalStateException("Window view parity mismatch: window=" + winHopCount + ", manual=" + manualHopCount);
        }

        for (int i = 0; i < currentWarmupIters; i++) {
            g.V().atTime(snapInstant).out("event").count().next();
            g.V().window(winStart, winEnd).out("event").count().next();
            g.V().temporalIntersects(winLt).outE("event").temporalIntersects(winLt).inV().temporalIntersects(winLt).count().next();
            g.V().window(winStart, winEnd).values("status").count().next();
        }

        double atTimeHopMs  = measureMs(() -> g.V().atTime(snapInstant).out("event").count().next());
        double windowHopMs  = measureMs(() -> g.V().window(winStart, winEnd).out("event").count().next());
        double manualHopMs  = measureMs(() -> g.V().temporalIntersects(winLt).outE("event").temporalIntersects(winLt).inV().temporalIntersects(winLt).count().next());
        double tempPropMs   = measureMs(() -> g.V().window(winStart, winEnd).values("status").count().next());

        viewRows.add(new ViewRow(numEdges, atTimeHopMs, windowHopMs, manualHopMs, tempPropMs));

        // Suite C: Temporal Path-Finding Variants (tbfs 4 targets & bounded-delay sequentialPath)
        Vertex src = vertices[0];
        Object[] seqSeeds = new Object[Math.min(20, numVertices)];
        for (int i = 0; i < seqSeeds.length; i++) seqSeeds[i] = vertices[i].id();

        long deadline = BASE_TIME + TIME_SPAN;
        for (int i = 0; i < currentWarmupIters; i++) {
            g.V(src).tbfs(TemporalPathTarget.EARLIEST_ARRIVAL, BASE_TIME).next();
            g.V(src).tbfs(TemporalPathTarget.EARLIEST_DEPARTURE, BASE_TIME).next();
            g.V(src).tbfs(TemporalPathTarget.LATEST_DEPARTURE, deadline).next();
            g.V(src).tbfs(TemporalPathTarget.LATEST_ARRIVAL, deadline).next();
            g.V(seqSeeds).sequentialPath(0L, 86_400_000L, "event").sequentialPath(0L, 86_400_000L, "event").count().next();
        }

        double eaMs = measureMs(() -> g.V(src).tbfs(TemporalPathTarget.EARLIEST_ARRIVAL, BASE_TIME).next().size());
        double edMs = measureMs(() -> g.V(src).tbfs(TemporalPathTarget.EARLIEST_DEPARTURE, BASE_TIME).next().size());
        double ldMs = measureMs(() -> g.V(src).tbfs(TemporalPathTarget.LATEST_DEPARTURE, deadline).next().size());
        double laMs = measureMs(() -> g.V(src).tbfs(TemporalPathTarget.LATEST_ARRIVAL, deadline).next().size());
        double spMs = measureMs(() -> g.V(seqSeeds).sequentialPath(0L, 86_400_000L, "event").sequentialPath(0L, 86_400_000L, "event").count().next());

        pathRows.add(new PathRow(numEdges, eaMs, edMs, ldMs, laMs, spMs));

        System.out.printf(Locale.US,
                "Edges=%-8d | Ligozat: Atomic=%6.2fms, Convex=%6.2fms, Pi(4,4)=%6.2fms, Or=%6.2fms (%.2fx) | Views: atTime=%6.2fms, window=%6.2fms, manual=%6.2fms, tProp=%5.2fms | Paths: EA=%5.2fms, ED=%5.2fms, LD=%5.2fms, LA=%5.2fms, Seq2Hop=%5.2fms%n",
                numEdges, atomicMs, convexMs, gen44Ms, orMs, orMs / Math.max(convexMs, 1e-6),
                atTimeHopMs, windowHopMs, manualHopMs, tempPropMs,
                eaMs, edMs, ldMs, laMs, spMs);
    }

    private static double measureMs(LongSupplier op) {
        long totalNanos = 0L;
        for (int i = 0; i < currentMeasureIters; i++) {
            long t0 = System.nanoTime();
            long res = op.getAsLong();
            if (res < 0) throw new IllegalStateException();
            totalNanos += (System.nanoTime() - t0);
        }
        return (totalNanos / (double) currentMeasureIters) / 1_000_000.0;
    }

    private static void printLaTeXTables(List<LigozatRow> ligozatRows, List<ViewRow> viewRows, List<PathRow> pathRows) {
        System.out.println("\n% --- LaTeX Table: T-TinkerPop Unique Capabilities Scaling ---");
        System.out.println("\\begin{table*}[t]");
        System.out.println("\\caption{Scaling of T-TinkerPop-Unique Temporal Capabilities ($10\\text{K}$--$1\\text{M}$ Multi-Interval Edges, Not Supported in ChronoGraph): (a) Ligozat Generalized $(p,q)$-Calculus vs.\\ Multi-Branch Disjunctions, (b) Historical Subgraph Views (\\texttt{atTime}, \\texttt{window}, \\texttt{lifetimeProperty}), and (c) Temporal Path Optimality Criteria (\\texttt{tbfs} Targets \\& Bounded-Delay \\texttt{sequentialPath}).}");
        System.out.println("\\label{tab:ttinkerpop-unique-scaling}");
        System.out.println("\\centering\\small");
        System.out.println("\\begin{tabular}{r|rrrrr|rrrr|rrrrr}");
        System.out.println("\\toprule");
        System.out.println("& \\multicolumn{5}{c|}{\\textbf{Ligozat $(p,q)$-Calculus (ms)}} & \\multicolumn{4}{c|}{\\textbf{Historical Views (ms)}} & \\multicolumn{5}{c}{\\textbf{Temporal Path Targets (ms)}} \\\\");
        System.out.println("Edges $|E|$ & Atomic & Convex $[\\alpha,\\beta]$ & $\\Pi(4,4)$ & \\texttt{.or()} & Speedup & \\texttt{atTime} & \\texttt{window} & Manual & \\texttt{tProp} & EA & ED & LD & LA & \\texttt{seqPath} \\\\");
        System.out.println("\\midrule");
        for (int i = 0; i < ligozatRows.size(); i++) {
            LigozatRow l = ligozatRows.get(i);
            ViewRow v    = viewRows.get(i);
            PathRow p    = pathRows.get(i);
            System.out.printf(Locale.US,
                    "%d & %.2f & \\textbf{%.2f} & %.2f & %.2f & \\textbf{%.2f$\\times$} & %.2f & %.2f & %.2f & %.2f & %.2f & %.2f & %.2f & %.2f & %.2f \\\\\n",
                    l.numEdges,
                    l.atomicOverlapsMs, l.convexBetweenMs, l.generalizedZoneMs, l.multiBranchOrMs, l.speedupVsOr,
                    v.atTimeHopMs, v.windowHopMs, v.manualFilterHopMs, v.temporalPropWindowMs,
                    p.earliestArrivalMs, p.earliestDepartureMs, p.latestDepartureMs, p.latestArrivalMs, p.sequentialPath2HopMs);
        }
        System.out.println("\\bottomrule");
        System.out.println("\\end{tabular}");
        System.out.println("\\end{table*}");
    }
}
