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
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;
import org.dfpl.chronograph.common.TemporalRelation;
import org.dfpl.chronograph.crud.memory.ChronoEdge;
import org.dfpl.chronograph.crud.memory.ChronoGraph;
import org.dfpl.chronograph.crud.memory.ChronoVertex;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Representation Ablation Benchmark for Section V of the T-TinkerPop paper.
 *
 * Compares:
 *   1. Legacy Dynamic String Parsing: timestamps stored as ISO-8601 strings and parsed
 *      on-the-fly for every traverser using DatetimeHelper.parse().
 *   2. ChronoGraph Event Objects: timestamps stored as TimePeriod objects in NavigableSets.
 *   3. T-TinkerPop Normalized epochMillis: timestamps normalized once at ingestion
 *      into Lifetime / epochMillis long arrays, evaluated via numeric operations.
 */
public class RepresentationAblationBenchmark implements BenchmarkTask {

    private static final int WARMUP_ITERS = 2;
    private static final int DEFAULT_BENCHMARK_ITERS = 10;
    private static final int LARGE_BENCHMARK_ITERS = 5;

    private static final int[] SIZES = {50000, 100000, 500000, 1000000};
    private static final int[] QUICK_SIZES = {5000, 20000};

    @Override
    public String getId() {
        return "representation-ablation";
    }

    @Override
    public String getName() {
        return "Table VIII: Representation Ablation (String vs CG vs TPop)";
    }

    @Override
    public String getDescription() {
        return "Compares dynamic ISO-8601 string parsing vs ChronoGraph objects vs TPop normalized epochMillis.";
    }

    @Override
    public BenchmarkGroup getGroup() {
        return BenchmarkGroup.PAPER;
    }

    private int currentWarmupIters = WARMUP_ITERS;
    private int currentDefaultIters = DEFAULT_BENCHMARK_ITERS;
    private int currentLargeIters = LARGE_BENCHMARK_ITERS;

    private static final long DAY_MS = 86_400_000L;
    private static final long BASE_TIME = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final long REF_START_MS = BASE_TIME + (15 * DAY_MS);
    private static final long REF_END_MS   = BASE_TIME + (25 * DAY_MS);
    private static final String REF_START_STR = ISO_FMT.format(Instant.ofEpochMilli(REF_START_MS));
    private static final String REF_END_STR   = ISO_FMT.format(Instant.ofEpochMilli(REF_END_MS));
    private static final TimePeriod REF_PERIOD = new TimePeriod(REF_START_MS, REF_END_MS);

    private static volatile Object sink;

    private Graph normGraph;
    private GraphTraversalSource gNorm;
    private Edge normRefEdge;

    private Graph strGraph;
    private GraphTraversalSource gStr;
    private Edge strRefEdge;

    private ChronoGraph chronoGraph;

    public static void main(String[] args) {
        new RepresentationAblationBenchmark().run();
    }

    @Override
    public void run(BenchmarkContext ctx) {
        if (ctx == null) {
            ctx = new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null);
        }

        this.currentWarmupIters = ctx.getWarmupIterations(WARMUP_ITERS);
        this.currentDefaultIters = ctx.getIterations(DEFAULT_BENCHMARK_ITERS);
        this.currentLargeIters = ctx.getIterations(LARGE_BENCHMARK_ITERS);

        int[] sizes = ctx.getSizes(getId(), SIZES, QUICK_SIZES);

        ctx.println("=================================================================================================================");
        ctx.println("  T-TINKERPOP VS CHRONOGRAPH REPRESENTATION ABLATION BENCHMARK (MULTI-INTERVAL CONSOLIDATED EDGES)");
        ctx.println("  Dynamic String Parsing vs ChronoGraph Objects vs T-TinkerPop Normalized epochMillis (Traversal & Direct)");
        ctx.println("  Empirically validating Section IV-B(b) pipeline stall elimination");
        ctx.println("=================================================================================================================");

        ctx.printf("%-10s %-16s %15s %15s %15s %15s %12s%n",
                "Events", "Operator", "Dynamic String", "ChronoGraph", "TPop Traversal", "TPop Direct", "Speedup vs Str");
        ctx.println("-----------------------------------------------------------------------------------------------------------------");

        List<AblationResult> results = new ArrayList<>();

        for (int size : sizes) {
            try {
                initializeGraphs(size);
                verifyParity(size);
                warmup();

                int iters = size >= 500000 ? currentLargeIters : currentDefaultIters;

                // 1. temporalBefore
                double strBefore = measureMs(() -> runDynamicStringFilter("before"), iters);
                double cgBefore  = measureMs(() -> runChronoGraphFilter(TemporalRelation.isBefore), iters);
                double normBefore = measureMs(() ->
                        gNorm.E().hasLabel("event").temporalBefore(normRefEdge).with(Temporal.ANY).count().next(), iters);
                double directBefore = measureMs(() -> runDirectNormalizedFilter(Temporal.precedes), iters);
                double spBefore = strBefore / Math.max(normBefore, 0.0001);
                results.add(new AblationResult(size, "temporalBefore", strBefore, cgBefore, normBefore, directBefore, spBefore));
                System.out.printf("%-10d %-16s %13.3f ms %13.3f ms %13.3f ms %13.3f ms %10.1fx%n",
                        size, "temporalBefore", strBefore, cgBefore, normBefore, directBefore, spBefore);

                // 2. temporalOverlaps
                double strOverlaps = measureMs(() -> runDynamicStringFilter("overlaps"), iters);
                double cgOverlaps  = measureMs(() -> runChronoGraphFilter(TemporalRelation.overlapsWith), iters);
                double normOverlaps = measureMs(() ->
                        gNorm.E().hasLabel("event").temporalOverlaps(normRefEdge).with(Temporal.ANY).count().next(), iters);
                double directOverlaps = measureMs(() -> runDirectNormalizedFilter(Temporal.overlaps), iters);
                double spOverlaps = strOverlaps / Math.max(normOverlaps, 0.0001);
                results.add(new AblationResult(size, "temporalOverlaps", strOverlaps, cgOverlaps, normOverlaps, directOverlaps, spOverlaps));
                System.out.printf("%-10d %-16s %13.3f ms %13.3f ms %13.3f ms %13.3f ms %10.1fx%n",
                        size, "temporalOverlaps", strOverlaps, cgOverlaps, normOverlaps, directOverlaps, spOverlaps);

                // 3. during
                double strDuring = measureMs(() -> runDynamicStringFilter("during"), iters);
                double cgDuring  = measureMs(() -> runChronoGraphFilter(TemporalRelation.during), iters);
                double normDuring = measureMs(() ->
                        gNorm.E().hasLabel("event").temporalDuring(normRefEdge).with(Temporal.ANY).count().next(), iters);
                double directDuring = measureMs(() -> runDirectNormalizedFilter(Temporal.during), iters);
                double spDuring = strDuring / Math.max(normDuring, 0.0001);
                results.add(new AblationResult(size, "during", strDuring, cgDuring, normDuring, directDuring, spDuring));
                System.out.printf("%-10d %-16s %13.3f ms %13.3f ms %13.3f ms %13.3f ms %10.1fx%n",
                        size, "during", strDuring, cgDuring, normDuring, directDuring, spDuring);

                System.out.println("-----------------------------------------------------------------------------------------------------------------");
            } finally {
                cleanup();
            }
        }

        printLatexTable(results);
    }

    public void run() {
        run(new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null));
    }

    private void initializeGraphs(int edgeCount) {
        normGraph = TinkerGraph.open();
        gNorm = normGraph.traversal();

        strGraph = TinkerGraph.open();
        gStr = strGraph.traversal();

        chronoGraph = new ChronoGraph();

        int k = 8;
        int numEdges = Math.max(16, edgeCount / k);
        int numV = numEdges + 1;
        Vertex[] normV = new Vertex[numV];
        Vertex[] strV  = new Vertex[numV];
        ChronoVertex[] cgV = new ChronoVertex[numV];

        for (int i = 0; i < numV; i++) {
            normV[i] = normGraph.addVertex(T.label, "item", "id", i);
            strV[i]  = strGraph.addVertex(T.label, "item", "id", i);
            cgV[i]   = (ChronoVertex) chronoGraph.addVertex("v" + i);
        }

        // Add reference edge with distinct label so target "event" queries do not include it
        normRefEdge = normV[0].addEdge("refEvent", normV[1]);
        Lifetime.from(REF_START_MS, REF_END_MS).attachTo(normRefEdge);

        strRefEdge = strV[0].addEdge("refEvent", strV[1]);
        strRefEdge.property("intervalsStr", REF_START_STR + "/" + REF_END_STR);

        // Add consolidated multi-interval target edges
        for (int i = 1; i <= numEdges; i++) {
            int u = i - 1;
            int v = i;

            List<Lifetime.Interval> intervals = new ArrayList<>(k);

            for (int j = 0; j < k; j++) {
                long idx = (long) (i - 1) * k + j;
                long startMs = BASE_TIME + ((idx * 17L * DAY_MS) % (365 * DAY_MS));
                long endMs   = startMs + (1L + (idx % 20)) * DAY_MS;

                intervals.add(new Lifetime.Interval(new Date(startMs), new Date(endMs)));
            }

            // 1. Normalized multi-interval edge in T-TinkerPop
            Edge normE = normV[u].addEdge("event", normV[v]);
            Lifetime lt = Lifetime.from(intervals);
            lt.attachTo(normE);

            // 2. String-backed edge & 3. ChronoGraph using the exact same canonical intervals
            StringBuilder strSb = new StringBuilder();
            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cgV[u], cgV[v], "event");
            for (int cIdx = 0; cIdx < lt.getIntervalCount(); cIdx++) {
                long sMs = lt.getStartMillis(cIdx);
                long eMs = lt.getEndMillis(cIdx);
                strSb.append(ISO_FMT.format(Instant.ofEpochMilli(sMs)))
                        .append('/')
                        .append(ISO_FMT.format(Instant.ofEpochMilli(eMs)))
                        .append(',');
                ce.addEvent(new TimePeriod(sMs, eMs));
            }
            Edge strE = strV[u].addEdge("event", strV[v]);
            strE.property("intervalsStr", strSb.toString());
        }
    }

    private void verifyParity(int size) {
        String[][] ops = {
                {"before", "temporalBefore"},
                {"overlaps", "temporalOverlaps"},
                {"during", "during"}
        };
        TemporalRelation[] cgRels = {
                TemporalRelation.isBefore,
                TemporalRelation.overlapsWith,
                TemporalRelation.during
        };
        Temporal[] tpopRels = {
                Temporal.precedes,
                Temporal.overlaps,
                Temporal.during
        };

        for (int idx = 0; idx < ops.length; idx++) {
            String strOp = ops[idx][0];
            String label = ops[idx][1];
            long cStr = runDynamicStringFilter(strOp);
            long cCg = runChronoGraphFilter(cgRels[idx]);
            long cNormDirect = runDirectNormalizedFilter(tpopRels[idx]);
            long cNormTrav;
            if ("before".equals(strOp)) {
                cNormTrav = gNorm.E().hasLabel("event").temporalBefore(normRefEdge).with(Temporal.ANY).count().next();
            } else if ("overlaps".equals(strOp)) {
                cNormTrav = gNorm.E().hasLabel("event").temporalOverlaps(normRefEdge).with(Temporal.ANY).count().next();
            } else {
                cNormTrav = gNorm.E().hasLabel("event").temporalDuring(normRefEdge).with(Temporal.ANY).count().next();
            }
            if (cStr != cCg || cCg != cNormDirect || cNormDirect != cNormTrav) {
                throw new IllegalStateException(String.format(
                        "Parity mismatch at size=%d op=%s: String=%d, ChronoGraph=%d, TPopTrav=%d, TPopDirect=%d",
                        size, label, cStr, cCg, cNormTrav, cNormDirect));
            }
        }
    }

    private long runDynamicStringFilter(String op) {
        long matchCount = 0;
        Iterator<Edge> it = strGraph.edges();

        Date targetStart = DatetimeHelper.parse(REF_START_STR);
        Date targetEnd   = DatetimeHelper.parse(REF_END_STR);

        while (it.hasNext()) {
            Edge e = it.next();
            if (e.equals(strRefEdge)) continue;
            if (!e.property("intervalsStr").isPresent()) continue;

            String intervalsStr = (String) e.property("intervalsStr").value();
            String[] pairs = intervalsStr.split(",");

            boolean matched = false;
            for (String pair : pairs) {
                if (pair.isEmpty()) continue;
                int slash = pair.indexOf('/');
                if (slash <= 0) continue;

                Date sDate = DatetimeHelper.parse(pair.substring(0, slash));
                Date eDate = DatetimeHelper.parse(pair.substring(slash + 1));

                switch (op) {
                    case "before":
                        if (eDate.before(targetStart)) matched = true;
                        break;
                    case "overlaps":
                        if (sDate.before(targetStart) && eDate.after(targetStart) && eDate.before(targetEnd)) matched = true;
                        break;
                    case "during":
                        if (sDate.after(targetStart) && eDate.before(targetEnd)) matched = true;
                        break;
                }
                if (matched) break;
            }
            if (matched) matchCount++;
        }
        return matchCount;
    }

    private long runChronoGraphFilter(TemporalRelation rel) {
        long count = 0;
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            java.util.NavigableSet<?> matches = ce.getEvents(REF_PERIOD, rel);
            if (matches != null && !matches.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private long runDirectNormalizedFilter(Temporal rel) {
        long count = 0;
        Iterator<Edge> it = normGraph.edges();
        Lifetime refLt = Lifetime.fromProperties(normRefEdge);
        while (it.hasNext()) {
            Edge e = it.next();
            if (e.equals(normRefEdge)) continue;
            Lifetime eLt = Lifetime.getLifetimeFromProperties(e);
            if (eLt != null && !eLt.isEmpty() && rel.test(eLt, refLt, Temporal.Quantifier.ANY)) {
                count++;
            }
        }
        return count;
    }

    private void warmup() {
        for (int i = 0; i < currentWarmupIters; i++) {
            sink = runDynamicStringFilter("before");
            sink = runChronoGraphFilter(TemporalRelation.isBefore);
            sink = gNorm.E().hasLabel("event").temporalBefore(normRefEdge).with(Temporal.ANY).count().next();
            sink = runDirectNormalizedFilter(Temporal.precedes);
        }
    }

    private double measureMs(LongSupplier op, int iters) {
        long totalNanos = 0L;
        for (int i = 0; i < iters; i++) {
            long start = System.nanoTime();
            sink = op.getAsLong();
            totalNanos += (System.nanoTime() - start);
        }
        return totalNanos / (iters * 1_000_000.0);
    }

    private void cleanup() {
        if (gNorm != null) { try { gNorm.close(); } catch (Exception ignored) {} }
        if (normGraph != null) { try { normGraph.close(); } catch (Exception ignored) {} }
        if (gStr != null) { try { gStr.close(); } catch (Exception ignored) {} }
        if (strGraph != null) { try { strGraph.close(); } catch (Exception ignored) {} }
        normGraph = null;
        gNorm = null;
        normRefEdge = null;
        strGraph = null;
        gStr = null;
        strRefEdge = null;
        chronoGraph = null;
    }

    private void printLatexTable(List<AblationResult> results) {
        System.out.println("\n% --- LaTeX Table for Section V Representation Ablation with ChronoGraph ---");
        System.out.println("\\begin{table}[htbp]");
        System.out.println("\\caption{Representation Ablation on Multi-Interval Consolidated Edges: Dynamic String Parsing vs ChronoGraph Objects vs T-TinkerPop Normalized \\texttt{epochMillis} (Runtime in ms).}");
        System.out.println("\\label{tab:representation-ablation-chronograph}");
        System.out.println("\\centering\\small");
        System.out.println("\\begin{tabular}{llrrrrr}");
        System.out.println("\\toprule");
        System.out.println("Events & Operator & Dynamic String (ms) & ChronoGraph (ms) & TPop Traversal (ms) & TPop Direct (ms) & Speedup vs String \\\\");
        System.out.println("\\midrule");
        for (AblationResult r : results) {
            System.out.printf("%d & \\texttt{%s} & %.2f & %.2f & %.2f & %.2f & \\textbf{%.1f$\\times$} \\\\%n",
                    r.size, r.operator, r.strMs, r.cgMs, r.normMs, r.directMs, r.speedup);
        }
        System.out.println("\\bottomrule");
        System.out.println("\\end{tabular}");
        System.out.println("\\end{table}\n");
    }

    private static class AblationResult {
        final int size;
        final String operator;
        final double strMs;
        final double cgMs;
        final double normMs;
        final double directMs;
        final double speedup;

        AblationResult(int size, String operator, double strMs, double cgMs, double normMs, double directMs, double speedup) {
            this.size = size;
            this.operator = operator;
            this.strMs = strMs;
            this.cgMs = cgMs;
            this.normMs = normMs;
            this.directMs = directMs;
            this.speedup = speedup;
        }
    }
}
