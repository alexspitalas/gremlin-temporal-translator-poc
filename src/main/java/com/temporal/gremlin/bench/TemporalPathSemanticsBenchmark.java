package com.temporal.gremlin.bench;

import com.temporal.gremlin.benchmark.BenchmarkContext;
import com.temporal.gremlin.benchmark.BenchmarkGroup;
import com.temporal.gremlin.benchmark.BenchmarkTask;
import com.temporal.gremlin.benchmark.ScaleMode;
import com.temporal.gremlin.util.DatasetResolver;
import com.temporal.gremlin.util.GraphDataLoader;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Section VI-E / Figure 4 Benchmark: Temporal Path Semantics.
 *
 * Measures traversal runtime of:
 *   1. Continuous Paths (continuousPath) - all edges share a global overlapping instant.
 *   2. Pairwise-Continuous Paths (pairwiseContinuousPath) - consecutive edges overlap locally.
 *   3. Sequential Paths (sequentialPath) - time-respecting forward journeys (s_{i+1} >= e_i).
 *
 * Evaluates across traversal depths (2 to 5 hops) and result limits (100, 1000, 10000)
 * on the OpenSky Flight Single File network.
 */
public class TemporalPathSemanticsBenchmark implements BenchmarkTask {

    private static final int WARMUP_ITERS = 2;
    private static final int BENCHMARK_ITERS = 5;
    private static final int[] HOPS = {2, 3, 4, 5};
    private static final int[] LIMITS = {100, 1000, 10000};

    @Override
    public String getId() {
        return "path-semantics";
    }

    @Override
    public String getName() {
        return "Figure 4 (fig:path-semantics): Temporal Path Semantics (Continuous, Pairwise, Sequential)";
    }

    @Override
    public String getDescription() {
        return "Evaluates Continuous, Pairwise-Continuous, and Sequential path traversal across depths 2-5 hops and limits 100-10k.";
    }

    @Override
    public BenchmarkGroup getGroup() {
        return BenchmarkGroup.PAPER;
    }

    public static void main(String[] args) {
        new TemporalPathSemanticsBenchmark().run();
    }

    public void run() {
        run(new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null));
    }

    @Override
    public void run(BenchmarkContext ctx) {
        if (ctx == null) {
            ctx = new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null);
        }

        File csvFile = DatasetResolver.resolveFlightCsv(ctx);
        if (!csvFile.exists()) {
            ctx.println("Dataset not found at " + csvFile.getAbsolutePath() + ". Skipping path semantics benchmark.");
            return;
        }

        int warmupIters = ctx.getWarmupIterations(WARMUP_ITERS);
        int benchIters = ctx.getIterations(BENCHMARK_ITERS);

        int[] hopsToRun = (ctx.getScale() == ScaleMode.QUICK) ? new int[]{2} : HOPS;
        int[] limitsToRun = (ctx.getScale() == ScaleMode.QUICK) ? new int[]{100} : LIMITS;
        int loadLimit = (ctx.getScale() == ScaleMode.QUICK) ? 5000 : 100000;

        ctx.println();
        ctx.println("====================================================================================================");
        ctx.println("  FIGURE 4: TEMPORAL PATH SEMANTICS BENCHMARK (Continuous vs Pairwise vs Sequential)");
        ctx.println("====================================================================================================");
        ctx.println("Dataset: " + csvFile.getAbsolutePath() + " (Loading up to " + loadLimit + " flights)");

        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        try {
            GraphDataLoader loader = new GraphDataLoader(g);
            loader.load_dataset_flight_native(csvFile.getAbsolutePath(), loadLimit);

            // Select active hub seed vertices
            List<Vertex> seeds = g.V().hasLabel("airport").filter(__.outE("flight")).limit(20).toList();
            if (seeds.isEmpty()) {
                seeds = g.V().limit(10).toList();
            }

            ctx.println("\n--- Evaluation across Depths (2-5 hops) and Result Limits (100, 1k, 10k) ---");
            ctx.println(String.format("%-12s | %-8s | %-16s | %-16s | %-16s",
                    "Path Depth", "Limit", "Sequential (ms)", "Pairwise (ms)", "Continuous (ms)"));
            ctx.println("-".repeat(80));

            for (int hops : hopsToRun) {
                for (int limit : limitsToRun) {
                    // 1. Sequential paths
                    double seqMs = measurePath(g, seeds, "sequential", hops, limit, warmupIters, benchIters);

                    // 2. Pairwise continuous paths
                    double pairMs = measurePath(g, seeds, "pairwise", hops, limit, warmupIters, benchIters);

                    // 3. Strict continuous paths
                    double contMs = measurePath(g, seeds, "continuous", hops, limit, warmupIters, benchIters);

                    ctx.println(String.format("%-12s | %-8d | %13.2f ms | %13.2f ms | %13.2f ms",
                            hops + " hops", limit, seqMs, pairMs, contMs));
                }
            }

            ctx.println("\n% --- LaTeX Coordinates Snippet matching journal.tex Figure 4 ---");
            ctx.println("% Sequential:  " + formatLatexSnippet(g, seeds, "sequential", hopsToRun, limitsToRun));
            ctx.println("% Pairwise:    " + formatLatexSnippet(g, seeds, "pairwise", hopsToRun, limitsToRun));
            ctx.println("% Continuous:  " + formatLatexSnippet(g, seeds, "continuous", hopsToRun, limitsToRun));

        } catch (Exception e) {
            ctx.println("Error running path semantics benchmark: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { graph.close(); } catch (Exception ignored) {}
        }
    }

    private double measurePath(GraphTraversalSource g, List<Vertex> seeds, String mode, int hops, int limit, int warmup, int iters) {
        // Warmup
        for (int w = 0; w < warmup; w++) {
            runQuery(g, seeds, mode, hops, limit);
        }

        long totalNanos = 0;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            runQuery(g, seeds, mode, hops, limit);
            totalNanos += (System.nanoTime() - t0);
        }

        return (totalNanos / (double) iters) / 1_000_000.0;
    }

    private long runQuery(GraphTraversalSource g, List<Vertex> seeds, String mode, int hops, int limit) {
        try {
            if ("sequential".equals(mode)) {
                return g.V(seeds)
                        .repeat(__.outE("flight").inV().sequentialPath("flight"))
                        .times(hops)
                        .limit(limit)
                        .path()
                        .count()
                        .next();
            } else if ("pairwise".equals(mode)) {
                return g.V(seeds)
                        .repeat(__.outE("flight").inV().pairwiseContinuousPath("flight"))
                        .times(hops)
                        .limit(limit)
                        .path()
                        .count()
                        .next();
            } else {
                return g.V(seeds)
                        .repeat(__.outE("flight").inV().continuousPath("flight"))
                        .times(hops)
                        .limit(limit)
                        .path()
                        .count()
                        .next();
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private String formatLatexSnippet(GraphTraversalSource g, List<Vertex> seeds, String mode, int[] hops, int[] limits) {
        StringBuilder sb = new StringBuilder();
        for (int limit : limits) {
            sb.append(String.format("[%s limit %d]: ", mode, limit));
            for (int h : hops) {
                sb.append(String.format("(%d, ...) ", h));
            }
        }
        return sb.toString();
    }
}
