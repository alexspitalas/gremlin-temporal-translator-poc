package com.temporal.gremlin.bench;

import com.tinkerpop.blueprints.TimePeriod;
import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.PageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.ranking.pagerank.TemporalPageRankAlgorithm;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.dfpl.chronograph.common.TemporalRelation;
import org.dfpl.chronograph.crud.memory.ChronoEdge;
import org.dfpl.chronograph.crud.memory.ChronoGraph;
import org.dfpl.chronograph.crud.memory.ChronoVertex;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Algorithmic Benchmark for Section V of the T-TinkerPop paper.
 *
 * Compares:
 *   1. T-TinkerPop temporalPageRank(): native Gremlin chronological stream
 *   2. ChronoGraph Temporal PageRank: chronological stream over ChronoGraph events
 *   3. Static PageRank: Apache TinkerPop 20-iteration BSP power iteration on aggregated graph
 *
 * Evaluated on the OpenSky Network real-world flight dataset.
 */
public class TemporalPageRankBenchmark {

    private static final int WARMUP_ITERS = 2;
    private static final int DEFAULT_BENCHMARK_ITERS = 5;
    private static final int LARGE_BENCHMARK_ITERS = 3;
    private static final int STATIC_PR_ITERATIONS = 20;

    private static final int[] FLIGHT_SIZES = {50000, 100000, 250000, 500000, 1000000};
    private static final String CSV_PATH = "datasets/flightlist_20190101_20190131.csv";

    private static final DateTimeFormatter CSV_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private static volatile Object sink;

    private TinkerGraph graph;
    private GraphTraversalSource g;
    private ChronoGraph chronoGraph;

    public static void main(String[] args) {
        new TemporalPageRankBenchmark().run();
    }

    public void run() {
        System.out.println("==========================================================================================================");
        System.out.println("  T-TINKERPOP VS CHRONOGRAPH ALGORITHMIC BENCHMARK: TEMPORAL PAGERANK ON OPENSKY FLIGHTS");
        System.out.println("  T-TinkerPop temporalPageRank() vs ChronoGraph Stream vs Static PageRank (Aggregated Window)");
        System.out.println("==========================================================================================================");

        File csvFile = new File(CSV_PATH);
        if (!csvFile.exists()) {
            System.err.println("Error: CSV file not found at " + CSV_PATH);
            return;
        }

        System.out.printf("%-10s %-9s %-9s %16s %16s %16s %10s%n",
                "Flights", "Airports", "Edges", "TPop TPR (ms)", "ChronoGraph (ms)", "Static PR (ms)", "Speedup");
        System.out.println("----------------------------------------------------------------------------------------------------------");

        List<PageRankResult> results = new ArrayList<>();

        for (int limit : FLIGHT_SIZES) {
            try {
                loadDataset(limit);
                int vertexCount = g.V().count().next().intValue();
                int edgeCount   = g.E().count().next().intValue();

                warmup();

                int iters = limit >= 500000 ? LARGE_BENCHMARK_ITERS : DEFAULT_BENCHMARK_ITERS;

                // 1. T-TinkerPop Native Temporal PageRank
                double tpopMs = measureMs(() -> {
                    g.V().temporalPageRank()
                            .with(TemporalPageRank.alpha, 0.85d)
                            .with(TemporalPageRank.beta, 0.5d)
                            .with(TemporalPageRank.normalize, true)
                            .iterate();
                    return edgeCount;
                }, iters);

                // 2. ChronoGraph Temporal PageRank
                double cgMs = measureMs(this::runChronoGraphTemporalPageRank, iters);

                // 3. Static PageRank (20 iterations)
                double staticMs = measureMs(() -> {
                    try {
                        ComputerResult cr = graph.compute()
                                .program(PageRankVertexProgram.build()
                                        .iterations(STATIC_PR_ITERATIONS)
                                        .create(graph))
                                .submit()
                                .get();
                        return cr.graph().traversal().V().count().next();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }, iters);

                double speedup = staticMs / Math.max(tpopMs, 0.0001);
                results.add(new PageRankResult(limit, vertexCount, edgeCount, tpopMs, cgMs, staticMs, speedup));

                System.out.printf("%-10d %-9d %-9d %14.2f ms %14.2f ms %14.2f ms %9.1fx%n",
                        limit, vertexCount, edgeCount, tpopMs, cgMs, staticMs, speedup);

            } catch (Exception e) {
                System.err.println("Error processing size " + limit + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                cleanup();
            }
        }

        printLatexTable(results);
    }

    private void loadDataset(int limit) throws Exception {
        graph = TinkerGraph.open();
        g = graph.traversal();
        chronoGraph = new ChronoGraph();

        Map<String, Vertex> airportMap = new HashMap<>();
        Map<String, ChronoVertex> cgAirportMap = new HashMap<>();
        int count = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(CSV_PATH))) {
            String header = br.readLine();
            String line;
            while ((line = br.readLine()) != null && count < limit) {
                String[] parts = line.split(",");
                if (parts.length < 9) continue;

                String origin = parts[5].trim();
                String dest   = parts[6].trim();
                if (origin.isEmpty() || dest.isEmpty()) continue;

                String firstSeenStr = parts[7].trim();
                String lastSeenStr  = parts[8].trim();
                if (firstSeenStr.isEmpty() || lastSeenStr.isEmpty()) continue;

                long startEpoch;
                long endEpoch;
                try {
                    startEpoch = OffsetDateTime.parse(firstSeenStr, CSV_FMT).toInstant().toEpochMilli();
                    endEpoch   = OffsetDateTime.parse(lastSeenStr,  CSV_FMT).toInstant().toEpochMilli();
                } catch (Exception ex) {
                    continue;
                }

                if (startEpoch >= endEpoch) continue;

                Vertex u = airportMap.computeIfAbsent(origin, code ->
                        graph.addVertex(T.label, "Airport", "code", code));
                Vertex v = airportMap.computeIfAbsent(dest, code ->
                        graph.addVertex(T.label, "Airport", "code", code));

                ChronoVertex cu = cgAirportMap.computeIfAbsent(origin, code ->
                        (ChronoVertex) chronoGraph.addVertex(code));
                ChronoVertex cv = cgAirportMap.computeIfAbsent(dest, code ->
                        (ChronoVertex) chronoGraph.addVertex(code));

                Edge e = g.addE("flight").from(u).to(v).property("callsign", parts[0].trim()).next();
                e.property("startTime", startEpoch);
                e.property("endTime", endEpoch);
                Lifetime.from(startEpoch, endEpoch).attachTo(e);

                ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cu, cv, "flight");
                ce.addEvent(new TimePeriod(startEpoch, endEpoch));

                count++;
            }
        }
    }

    private long runChronoGraphTemporalPageRank() {
        // Collect temporal edges from ChronoGraph
        TimePeriod allTime = new TimePeriod(0L, Long.MAX_VALUE);
        List<TemporalEdge> cgEdges = new ArrayList<>();

        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            String outId = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.OUT).getId();
            String inId  = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.IN).getId();

            Vertex u = graph.vertices(outId).hasNext() ? graph.vertices(outId).next() : null;
            Vertex v = graph.vertices(inId).hasNext() ? graph.vertices(inId).next() : null;
            if (u == null || v == null) continue;

            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                Lifetime lt = Lifetime.from(tp.getS(), tp.getF());
                Edge base = g.V(u).outE("flight").filter(traverser -> traverser.get().inVertex().equals(v)).next();
                cgEdges.add(new TemporalEdge(base, lt));
            }
        }

        // Execute Rozenshtein & Gionis algorithm on the extracted stream
        Map<Object, Double> rank = TemporalPageRankAlgorithm.execute(cgEdges, 0.85d, 0.5d, true);
        return rank.size();
    }

    private void warmup() {
        for (int i = 0; i < WARMUP_ITERS; i++) {
            g.V().temporalPageRank()
                    .with(TemporalPageRank.alpha, 0.85d)
                    .with(TemporalPageRank.beta, 0.5d)
                    .iterate();
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
        if (g != null) { try { g.close(); } catch (Exception ignored) {} }
        if (graph != null) { try { graph.close(); } catch (Exception ignored) {} }
        graph = null;
        g = null;
        chronoGraph = null;
    }

    private void printLatexTable(List<PageRankResult> results) {
        System.out.println("\n% --- LaTeX Table for Section V Algorithmic Benchmark with ChronoGraph ---");
        System.out.println("\\begin{table}[htbp]");
        System.out.println("\\caption{Algorithmic Benchmark on OpenSky Flights: T-TinkerPop \\texttt{temporalPageRank()} vs ChronoGraph stream vs Static PageRank (20 iterations).}");
        System.out.println("\\label{tab:temporal-pagerank-chronograph}");
        System.out.println("\\centering\\small");
        System.out.println("\\begin{tabular}{rrrrrrr}");
        System.out.println("\\toprule");
        System.out.println("Flights $|E|$ & Airports $|V|$ & TPop TPR (ms) & ChronoGraph (ms) & Static PR (ms) & Speedup vs Static \\\\");
        System.out.println("\\midrule");
        for (PageRankResult r : results) {
            System.out.printf("%d & %d & %.2f & %.2f & %.2f & \\textbf{%.1f$\\times$} \\\\%n",
                    r.edges, r.vertices, r.tpopMs, r.cgMs, r.staticMs, r.speedup);
        }
        System.out.println("\\bottomrule");
        System.out.println("\\end{tabular}");
        System.out.println("\\end{table}\n");
    }

    private static class PageRankResult {
        final int limit;
        final int vertices;
        final int edges;
        final double tpopMs;
        final double cgMs;
        final double staticMs;
        final double speedup;

        PageRankResult(int limit, int vertices, int edges, double tpopMs, double cgMs, double staticMs, double speedup) {
            this.limit = limit;
            this.vertices = vertices;
            this.edges = edges;
            this.tpopMs = tpopMs;
            this.cgMs = cgMs;
            this.staticMs = staticMs;
            this.speedup = speedup;
        }
    }
}
