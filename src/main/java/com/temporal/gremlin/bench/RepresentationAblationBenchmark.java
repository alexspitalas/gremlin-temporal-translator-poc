package com.temporal.gremlin.bench;

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
public class RepresentationAblationBenchmark {

    private static final int WARMUP_ITERS = 2;
    private static final int DEFAULT_BENCHMARK_ITERS = 10;
    private static final int LARGE_BENCHMARK_ITERS = 5;

    private static final int[] SIZES = {50000, 100000, 500000, 1000000};

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

    public void run() {
        System.out.println("======================================================================================================");
        System.out.println("  T-TINKERPOP VS CHRONOGRAPH REPRESENTATION ABLATION BENCHMARK");
        System.out.println("  Dynamic String Parsing vs ChronoGraph Objects vs T-TinkerPop Normalized epochMillis");
        System.out.println("  Empirically validating Section IV-B(b) pipeline stall elimination");
        System.out.println("======================================================================================================");

        System.out.printf("%-10s %-18s %15s %15s %15s %12s%n",
                "Scale |E|", "Operator", "Dynamic String", "ChronoGraph", "T-TinkerPop", "Speedup vs Str");
        System.out.println("------------------------------------------------------------------------------------------------------");

        List<AblationResult> results = new ArrayList<>();

        for (int size : SIZES) {
            try {
                initializeGraphs(size);
                warmup();

                int iters = size >= 500000 ? LARGE_BENCHMARK_ITERS : DEFAULT_BENCHMARK_ITERS;

                // 1. temporalBefore
                double strBefore = measureMs(() -> runDynamicStringFilter("before"), iters);
                double cgBefore  = measureMs(() -> runChronoGraphFilter(TemporalRelation.isBefore), iters);
                double normBefore = measureMs(() ->
                        gNorm.E().hasLabel("event").temporalBefore(normRefEdge).count().next(), iters);
                double spBefore = strBefore / Math.max(normBefore, 0.0001);
                results.add(new AblationResult(size, "temporalBefore", strBefore, cgBefore, normBefore, spBefore));
                System.out.printf("%-10d %-18s %13.3f ms %13.3f ms %13.3f ms %10.1fx%n",
                        size, "temporalBefore", strBefore, cgBefore, normBefore, spBefore);

                // 2. temporalOverlaps
                double strOverlaps = measureMs(() -> runDynamicStringFilter("overlaps"), iters);
                double cgOverlaps  = measureMs(() -> runChronoGraphFilter(TemporalRelation.overlapsWith), iters);
                double normOverlaps = measureMs(() ->
                        gNorm.E().hasLabel("event").temporalOverlaps(normRefEdge).count().next(), iters);
                double spOverlaps = strOverlaps / Math.max(normOverlaps, 0.0001);
                results.add(new AblationResult(size, "temporalOverlaps", strOverlaps, cgOverlaps, normOverlaps, spOverlaps));
                System.out.printf("%-10d %-18s %13.3f ms %13.3f ms %13.3f ms %10.1fx%n",
                        size, "temporalOverlaps", strOverlaps, cgOverlaps, normOverlaps, spOverlaps);

                // 3. during
                double strDuring = measureMs(() -> runDynamicStringFilter("during"), iters);
                double cgDuring  = measureMs(() -> runChronoGraphFilter(TemporalRelation.during), iters);
                double normDuring = measureMs(() ->
                        gNorm.E().hasLabel("event").asAdmin().addStep(
                                new org.apache.tinkerpop.gremlin.process.traversal.step.filter.AllenFilterStep<>(
                                        gNorm.E().hasLabel("event").asAdmin(), Temporal.during, normRefEdge))
                                .count().next(), iters);
                double spDuring = strDuring / Math.max(normDuring, 0.0001);
                results.add(new AblationResult(size, "during", strDuring, cgDuring, normDuring, spDuring));
                System.out.printf("%-10d %-18s %13.3f ms %13.3f ms %13.3f ms %10.1fx%n",
                        size, "during", strDuring, cgDuring, normDuring, spDuring);

                System.out.println("------------------------------------------------------------------------------------------------------");
            } finally {
                cleanup();
            }
        }

        printLatexTable(results);
    }

    private void initializeGraphs(int edgeCount) {
        normGraph = TinkerGraph.open();
        gNorm = normGraph.traversal();

        strGraph = TinkerGraph.open();
        gStr = strGraph.traversal();

        chronoGraph = new ChronoGraph();

        int numV = Math.max(16, edgeCount / 8);
        Vertex[] normV = new Vertex[numV];
        Vertex[] strV  = new Vertex[numV];
        ChronoVertex[] cgV = new ChronoVertex[numV];

        for (int i = 0; i < numV; i++) {
            normV[i] = normGraph.addVertex(T.label, "item", "id", i);
            strV[i]  = strGraph.addVertex(T.label, "item", "id", i);
            cgV[i]   = (ChronoVertex) chronoGraph.addVertex("v" + i);
        }

        // Add reference edge
        normRefEdge = gNorm.addE("event").from(normV[0]).to(normV[1])
                .lifetime(REF_START_MS, REF_END_MS).next();

        strRefEdge = gStr.addE("event").from(strV[0]).to(strV[1])
                .property("startTimeStr", REF_START_STR)
                .property("endTimeStr", REF_END_STR)
                .next();

        for (int i = 1; i < edgeCount; i++) {
            int u = i % numV;
            int v = (i + 1) % numV;

            long startMs = BASE_TIME + ((i * 17L) % (365 * DAY_MS));
            long endMs   = startMs + (1L + (i % 20)) * DAY_MS;

            // 1. Normalized graph
            gNorm.addE("event").from(normV[u]).to(normV[v])
                    .lifetime(startMs, endMs)
                    .iterate();

            // 2. String-backed graph
            String startStr = ISO_FMT.format(Instant.ofEpochMilli(startMs));
            String endStr   = ISO_FMT.format(Instant.ofEpochMilli(endMs));
            gStr.addE("event").from(strV[u]).to(strV[v])
                    .property("startTimeStr", startStr)
                    .property("endTimeStr", endStr)
                    .iterate();

            // 3. ChronoGraph
            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cgV[u], cgV[v], "event");
            ce.addEvent(new TimePeriod(startMs, endMs));
        }
    }

    private long runDynamicStringFilter(String op) {
        long matchCount = 0;
        Iterator<Edge> it = strGraph.edges();

        Date targetStart = DatetimeHelper.parse(REF_START_STR);
        Date targetEnd   = DatetimeHelper.parse(REF_END_STR);

        while (it.hasNext()) {
            Edge e = it.next();
            if (!e.property("startTimeStr").isPresent()) continue;

            String sStr = (String) e.property("startTimeStr").value();
            String eStr = (String) e.property("endTimeStr").value();

            Date sDate = DatetimeHelper.parse(sStr);
            Date eDate = DatetimeHelper.parse(eStr);

            boolean match = false;
            switch (op) {
                case "before":
                    match = eDate.before(targetStart);
                    break;
                case "overlaps":
                    match = sDate.before(targetStart) && eDate.after(targetStart) && eDate.before(targetEnd);
                    break;
                case "during":
                    match = sDate.after(targetStart) && eDate.before(targetEnd);
                    break;
            }
            if (match) matchCount++;
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

    private void warmup() {
        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink = runDynamicStringFilter("before");
            sink = runChronoGraphFilter(TemporalRelation.isBefore);
            sink = gNorm.E().hasLabel("event").temporalBefore(normRefEdge).count().next();
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
        System.out.println("\\caption{Representation Ablation: Dynamic String Parsing vs ChronoGraph Objects vs T-TinkerPop Normalized \\texttt{epochMillis} (Runtime in ms).}");
        System.out.println("\\label{tab:representation-ablation-chronograph}");
        System.out.println("\\centering\\small");
        System.out.println("\\begin{tabular}{llrrrr}");
        System.out.println("\\toprule");
        System.out.println("Scale $|E|$ & Operator & Dynamic String (ms) & ChronoGraph (ms) & T-TinkerPop (ms) & Speedup vs String \\\\");
        System.out.println("\\midrule");
        for (AblationResult r : results) {
            System.out.printf("%d & \\texttt{%s} & %.2f & %.2f & %.2f & \\textbf{%.1f$\\times$} \\\\%n",
                    r.size, r.operator, r.strMs, r.cgMs, r.normMs, r.speedup);
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
        final double speedup;

        AblationResult(int size, String operator, double strMs, double cgMs, double normMs, double speedup) {
            this.size = size;
            this.operator = operator;
            this.strMs = strMs;
            this.cgMs = cgMs;
            this.normMs = normMs;
            this.speedup = speedup;
        }
    }
}
