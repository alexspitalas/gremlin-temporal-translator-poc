package com.temporal.gremlin.bench;

import com.temporal.gremlin.benchmark.BenchmarkContext;
import com.temporal.gremlin.benchmark.BenchmarkGroup;
import com.temporal.gremlin.benchmark.BenchmarkTask;
import com.temporal.gremlin.benchmark.ScaleMode;
import com.temporal.gremlin.util.DatasetResolver;
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
public class TemporalPageRankBenchmark implements BenchmarkTask {

    private static final int WARMUP_ITERS = 2;
    private static final int DEFAULT_BENCHMARK_ITERS = 5;
    private static final int LARGE_BENCHMARK_ITERS = 3;
    private static final int STATIC_PR_ITERATIONS = 20;

    private static final int[] FLIGHT_SIZES = {50000, 100000, 250000, 500000, 1000000};
    private static final int[] QUICK_SIZES = {5000, 20000};

    @Override
    public String getId() {
        return "pagerank";
    }

    @Override
    public String getName() {
        return "Table X: Temporal PageRank on OpenSky";
    }

    @Override
    public String getDescription() {
        return "Compares T-TinkerPop stream TPR vs ChronoGraph stream TPR vs Static TinkerPop PageRank on OpenSky.";
    }

    @Override
    public BenchmarkGroup getGroup() {
        return BenchmarkGroup.PAPER;
    }

    private static final DateTimeFormatter CSV_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private static volatile Object sink;

    private File currentCsvFile;
    private int currentWarmupIters = WARMUP_ITERS;
    private int currentDefaultIters = DEFAULT_BENCHMARK_ITERS;
    private int currentLargeIters = LARGE_BENCHMARK_ITERS;

    private TinkerGraph graph;
    private GraphTraversalSource g;
    private ChronoGraph chronoGraph;
    private Map<String, Edge> routeEdgeMap = new HashMap<>();

    public static void main(String[] args) {
        new TemporalPageRankBenchmark().run();
    }

    @Override
    public void run(BenchmarkContext ctx) {
        if (ctx == null) {
            ctx = new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null);
        }

        this.currentCsvFile = ctx.getDatasetFile();
        this.currentWarmupIters = ctx.getWarmupIterations(WARMUP_ITERS);
        this.currentDefaultIters = ctx.getIterations(DEFAULT_BENCHMARK_ITERS);
        this.currentLargeIters = ctx.getIterations(LARGE_BENCHMARK_ITERS);

        int[] sizes = ctx.getSizes(getId(), FLIGHT_SIZES, QUICK_SIZES);

        ctx.println("==========================================================================================================");
        ctx.println("  T-TINKERPOP VS CHRONOGRAPH ALGORITHMIC BENCHMARK: TEMPORAL PAGERANK ON OPENSKY FLIGHTS");
        ctx.println("  T-TinkerPop temporalPageRank() vs ChronoGraph Stream vs Static PageRank (Aggregated Window)");
        ctx.println("  Dataset: " + currentCsvFile.getAbsolutePath());
        ctx.println("==========================================================================================================");

        ctx.printf("%-10s %-9s %-9s %16s %16s %16s %10s%n",
                "Flights", "Airports", "Edges", "TPop TPR (ms)", "ChronoGraph (ms)", "Static PR (ms)", "Speedup");
        ctx.println("----------------------------------------------------------------------------------------------------------");

        List<PageRankResult> results = new ArrayList<>();

        for (int limit : sizes) {
            try {
                loadDataset(limit);
                int vertexCount = g.V().count().next().intValue();
                int edgeCount   = g.E().count().next().intValue();

                warmup();

                int iters = limit >= 500000 ? currentLargeIters : currentDefaultIters;

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

    public void run() {
        run(new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null));
    }

    private void loadDataset(int limit) throws Exception {
        if (currentCsvFile == null) {
            currentCsvFile = DatasetResolver.resolveFlightCsv(null);
        }
        graph = TinkerGraph.open();
        g = graph.traversal();
        chronoGraph = new ChronoGraph();
        routeEdgeMap.clear();

        Map<String, Vertex> airportMap = new HashMap<>();
        Map<String, ChronoVertex> cgAirportMap = new HashMap<>();
        Map<String, ChronoEdge> cgRouteEdgeMap = new HashMap<>();
        Map<String, List<Lifetime.Interval>> routeIntervals = new LinkedHashMap<>();
        Map<String, String> routeOrigins = new HashMap<>();
        Map<String, String> routeDests = new HashMap<>();
        int count = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(currentCsvFile))) {
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

                String routeKey = origin + "->" + dest;
                routeIntervals.computeIfAbsent(routeKey, k -> new ArrayList<>())
                        .add(new Lifetime.Interval(new Date(startEpoch), new Date(endEpoch)));
                routeOrigins.putIfAbsent(routeKey, origin);
                routeDests.putIfAbsent(routeKey, dest);

                count++;
            }
        }

        // Attach canonical coalesced multi-interval lifetimes to consolidated route edges in both engines
        for (Map.Entry<String, List<Lifetime.Interval>> entry : routeIntervals.entrySet()) {
            String routeKey = entry.getKey();
            List<Lifetime.Interval> intervals = entry.getValue();
            String origin = routeOrigins.get(routeKey);
            String dest = routeDests.get(routeKey);
            Vertex u = airportMap.get(origin);
            Vertex v = airportMap.get(dest);

            Edge e = u.addEdge("flight", v);
            Lifetime lt = Lifetime.from(intervals);
            lt.attachTo(e);
            routeEdgeMap.put(routeKey, e);

            ChronoVertex cu = cgAirportMap.get(origin);
            ChronoVertex cv = cgAirportMap.get(dest);
            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cu, cv, "flight");
            cgRouteEdgeMap.put(routeKey, ce);
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }
        }
    }

    private long runChronoGraphTemporalPageRank() {
        // Collect temporal edges from ChronoGraph
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        List<TemporalEdge> cgEdges = new ArrayList<>();

        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            String outId = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.OUT).getId();
            String inId  = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.IN).getId();

            Edge base = routeEdgeMap.get(outId + "->" + inId);
            if (base == null) continue;

            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                Lifetime lt = Lifetime.from(new Date(tp.getS()), new Date(tp.getF()));
                cgEdges.add(new TemporalEdge(base, lt));
            }
        }

        // Execute Rozenshtein & Gionis algorithm on the extracted stream
        Map<Object, Double> rank = TemporalPageRankAlgorithm.execute(cgEdges, 0.85d, 0.5d, true);
        return rank.size();
    }

    private void warmup() {
        for (int i = 0; i < currentWarmupIters; i++) {
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
        if (routeEdgeMap != null) {
            routeEdgeMap.clear();
        }
    }

    private void printLatexTable(List<PageRankResult> results) {
        System.out.println("\n% --- LaTeX Table for Section V Algorithmic Benchmark with ChronoGraph ---");
        System.out.println("\\begin{table}[htbp]");
        System.out.println("\\caption{Algorithmic Benchmark on OpenSky Flights: T-TinkerPop \\texttt{temporalPageRank()} (multi-interval consolidated route edges) vs ChronoGraph stream vs Static PageRank (20 iterations).}");
        System.out.println("\\label{tab:temporal-pagerank-chronograph}");
        System.out.println("\\centering\\small");
        System.out.println("\\begin{tabular}{rrrrrrr}");
        System.out.println("\\toprule");
        System.out.println("Flights (Events) & Routes $|E|$ & Airports $|V|$ & TPop TPR (ms) & ChronoGraph (ms) & Static PR (ms) & Speedup vs Static \\\\");
        System.out.println("\\midrule");
        for (PageRankResult r : results) {
            System.out.printf("%d & %d & %d & %.2f & %.2f & %.2f & \\textbf{%.1f$\\times$} \\\\%n",
                    r.limit, r.edges, r.vertices, r.tpopMs, r.cgMs, r.staticMs, r.speedup);
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
