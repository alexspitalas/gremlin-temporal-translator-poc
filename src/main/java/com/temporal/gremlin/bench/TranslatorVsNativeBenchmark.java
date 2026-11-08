package com.temporal.gremlin.bench;

import com.temporal.gremlin.benchmark.BenchmarkContext;
import com.temporal.gremlin.benchmark.BenchmarkGroup;
import com.temporal.gremlin.benchmark.BenchmarkTask;
import com.temporal.gremlin.benchmark.ScaleMode;
import com.temporal.gremlin.query.TemporalQuerySpec;
import com.temporal.gremlin.translator.*;
import com.temporal.gremlin.executor.GremlinExecutor;
import com.temporal.gremlin.util.DatasetResolver;
import com.temporal.gremlin.util.GraphDataLoader;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

/**
 * Consolidated Benchmark Suite: Translator vs Native.
 * 
 * Merges functionality from:
 * 1. FlightBenchmark (Scalability, Execution Comparisons)
 * 2. TranslationBenchmark (Internal Phase Breakdown)
 * 
 * Objectives:
 * - Measure overhead of translation process itself (Parsing -> Transform -> CodeGen).
 * - Compare end-to-end execution time of Translated queries vs Native Java TinkerPop.
 * - Demonstrate scalability across different dataset sizes.
 */
public class TranslatorVsNativeBenchmark implements BenchmarkTask {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 50;
    private static final int[] SIZES = {1000, 10000, 100000};
    private static final int[] QUICK_SIZES = {1000, 5000};

    @Override
    public String getId() {
        return "translator-vs-native";
    }

    @Override
    public String getName() {
        return "Translator Overhead vs Native T-Gremlin Execution";
    }

    @Override
    public String getDescription() {
        return "Measures 4-phase translation pipeline latency and compares native vs translated execution.";
    }

    @Override
    public BenchmarkGroup getGroup() {
        return BenchmarkGroup.TRANSLATOR;
    }

    private File currentCsvFile;
    private int currentWarmupIters = WARMUP_ITERATIONS;
    private int currentBenchmarkIters = BENCHMARK_ITERATIONS;
    
    private static final TemporalQuerySpec.AllenOperator[] OPERATORS =
            TemporalQuerySpec.AllenOperator.values();

    private Graph graph;
    private GraphTraversalSource g;
    private TemporalGremlinTranslator translator;
    private GremlinExecutor executor;
    private Edge refEdge;

    public static void main(String[] args) {
        new TranslatorVsNativeBenchmark().run();
    }

    @Override
    public void run(BenchmarkContext ctx) {
        if (ctx == null) {
            ctx = new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null);
        }

        this.currentCsvFile = ctx.getDatasetFile();
        this.currentWarmupIters = ctx.getWarmupIterations(WARMUP_ITERATIONS);
        this.currentBenchmarkIters = ctx.getIterations(BENCHMARK_ITERATIONS);

        int[] sizes = ctx.getSizes(getId(), SIZES, QUICK_SIZES);

        ctx.println("==========================================================================================================");
        ctx.println("  TRANSLATOR OVERHEAD VS NATIVE T-GREMLIN BENCHMARK");
        ctx.println("  Dataset: " + currentCsvFile.getAbsolutePath());
        ctx.println("==========================================================================================================");

        for (int size : sizes) {
            String label = String.format("%,d", size);
            runForSize(size, label);
        }
    }

    public void run() {
        run(new BenchmarkContext(null, ScaleMode.FULL, null, -1, -1, null));
    }

    private void runForSize(int size, String label) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  BENCHMARK SCALE: " + label + " Flights");
        System.out.println("=".repeat(70));

        try {
            initialize(size);
            verifyOperatorParity(size);
            
            // 1. Internal Translation Phase Breakdown
            benchmarkTranslationPhases();

            // 2. End-to-End Operator Benchmarks
            runOperatorBenchmarks();
            
            cleanup();
        } catch (Exception e) {
            System.err.println("Error during benchmark for " + label + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initialize(int size) {
        graph = TinkerGraph.open();
        g = graph.traversal();
        
        // Fast Native Loading
        String csv = (currentCsvFile != null) ? currentCsvFile.getAbsolutePath() : DatasetResolver.resolveFlightCsv().getAbsolutePath();
        GraphDataLoader loader = new GraphDataLoader(g);
        loader.load_dataset_flight_native(csv, size); 

        // Get a reference flight edge for benchmarks
        if (g.E().hasLabel("flight").hasNext()) {
            refEdge = g.E().hasLabel("flight").limit(1).next();
        } else {
            // Should not happen if loading works, but handle strictly
            throw new IllegalStateException("No flight edges loaded!");
        }

        translator = new TemporalGremlinTranslator(g);
        executor = new GremlinExecutor(g);
    }

    private void verifyOperatorParity(int size) {
        Object refId = refEdge.id();
        for (TemporalQuerySpec.AllenOperator op : OPERATORS) {
            long nativeCount = countNative(op);
            TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
                    .withElementType(TemporalQuerySpec.ElementType.EDGE)
                    .withReferenceVertex(refId);
            String bytecode = translator.translate(spec).bytecode;
            long translatedCount = executor.execute(bytecode).size();
            long directCount = countDirectQuery(op);

            if (nativeCount != translatedCount || nativeCount != directCount) {
                throw new IllegalStateException(String.format(
                        "Translator vs Native parity mismatch at size=%d op=%s: Native=%d, Translated=%d, Direct=%d",
                        size, op.name(), nativeCount, translatedCount, directCount));
            }
        }
    }

    private long countNative(TemporalQuerySpec.AllenOperator op) {
        switch (op) {
            case temporalBefore: return g.E().hasLabel("flight").temporalBefore(refEdge).count().next();
            case temporalAfter: return g.E().hasLabel("flight").temporalAfter(refEdge).count().next();
            case temporalMeets: return g.E().hasLabel("flight").temporalMeets(refEdge).count().next();
            case temporalMetBy: return g.E().hasLabel("flight").temporalMetBy(refEdge).count().next();
            case temporalOverlaps: return g.E().hasLabel("flight").temporalOverlaps(refEdge).count().next();
            case temporalOverlappedBy: return g.E().hasLabel("flight").temporalOverlappedBy(refEdge).count().next();
            case temporalStarts: return g.E().hasLabel("flight").temporalStarts(refEdge).count().next();
            case temporalStartedBy: return g.E().hasLabel("flight").temporalStartedBy(refEdge).count().next();
            case temporalDuring: return g.E().hasLabel("flight").temporalDuring(refEdge).count().next();
            case temporalContains: return g.E().hasLabel("flight").temporalContains(refEdge).count().next();
            case temporalEnds: return g.E().hasLabel("flight").temporalFinishes(refEdge).count().next();
            case temporalEndedBy: return g.E().hasLabel("flight").temporalFinishedBy(refEdge).count().next();
            case temporalEqual: return g.E().hasLabel("flight").temporalEquals(refEdge).count().next();
            default: return 0L;
        }
    }

    private long countDirectQuery(TemporalQuerySpec.AllenOperator op) {
        Lifetime lt = Lifetime.getLifetimeFromProperties(refEdge);
        Date refStartDate = new Date(lt.getStartMillis(0));
        Date refEndDate = new Date(lt.getEndMillis(0));
        switch (op) {
            case temporalBefore: return g.E().hasLabel("flight").has("endTime", P.lt(refStartDate)).count().next();
            case temporalAfter: return g.E().hasLabel("flight").has("startTime", P.gt(refEndDate)).count().next();
            case temporalMeets: return g.E().hasLabel("flight").has("endTime", P.eq(refStartDate)).count().next();
            case temporalMetBy: return g.E().hasLabel("flight").has("startTime", P.eq(refEndDate)).count().next();
            case temporalOverlaps:
                return g.E().hasLabel("flight").has("startTime", P.lt(refStartDate)).has("endTime", P.gt(refStartDate)).has("endTime", P.lt(refEndDate)).count().next();
            case temporalOverlappedBy:
                return g.E().hasLabel("flight").has("startTime", P.gt(refStartDate)).has("startTime", P.lt(refEndDate)).has("endTime", P.gt(refEndDate)).count().next();
            case temporalStarts:
                return g.E().hasLabel("flight").has("startTime", P.eq(refStartDate)).has("endTime", P.lt(refEndDate)).count().next();
            case temporalStartedBy:
                return g.E().hasLabel("flight").has("startTime", P.eq(refStartDate)).has("endTime", P.gt(refEndDate)).count().next();
            case temporalDuring:
                return g.E().hasLabel("flight").has("startTime", P.gt(refStartDate)).has("endTime", P.lt(refEndDate)).count().next();
            case temporalContains:
                return g.E().hasLabel("flight").has("startTime", P.lt(refStartDate)).has("endTime", P.gt(refEndDate)).count().next();
            case temporalEnds:
                return g.E().hasLabel("flight").has("startTime", P.gt(refStartDate)).has("endTime", P.eq(refEndDate)).count().next();
            case temporalEndedBy:
                return g.E().hasLabel("flight").has("startTime", P.lt(refStartDate)).has("endTime", P.eq(refEndDate)).count().next();
            case temporalEqual:
                return g.E().hasLabel("flight").has("startTime", P.eq(refStartDate)).has("endTime", P.eq(refEndDate)).count().next();
            default: return 0L;
        }
    }
    
    /**
     * Measures the internal cost of the translation pipeline.
     * Adapted from TranslationBenchmark.
     */
    private void benchmarkTranslationPhases() {
        System.out.println("\n--- 1. Translation Phase Breakdown (Overhead) ---");
        
        // Use a complex operator to stress the translator slightly
        TemporalQuerySpec spec = new TemporalQuerySpec(
            "flight", "flight",
            TemporalQuerySpec.AllenOperator.temporalOverlaps
        ).withElementType(TemporalQuerySpec.ElementType.EDGE)
         .withReferenceVertex(refEdge.id());
        
        double totalParsing = 0, totalTransform = 0, totalOptimization = 0, totalCodegen = 0;
        
        // Warmup
        for(int i=0; i<currentWarmupIters; i++) {
            translator.translate(spec);
        }

        for (int i = 0; i < currentBenchmarkIters; i++) {
            TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);
            totalParsing += result.metrics.getParsingTime();
            totalTransform += result.metrics.getTransformTime();
            totalOptimization += result.metrics.getOptimizationTime();
            totalCodegen += result.metrics.getCodegenTime();
        }
        
        double total = totalParsing + totalTransform + totalOptimization + totalCodegen;
        double avgTotal = total / currentBenchmarkIters;

        System.out.printf("  Total Translation Time: %.3f ms\n", avgTotal);
        System.out.printf("  - Parsing:        %5.1f%%\n", (totalParsing / total) * 100);
        System.out.printf("  - Transform:      %5.1f%%\n", (totalTransform / total) * 100);
        System.out.printf("  - Optimization:   %5.1f%%\n", (totalOptimization / total) * 100);
        System.out.printf("  - Code Gen:       %5.1f%%\n", (totalCodegen / total) * 100);
    }

    private void runOperatorBenchmarks() {
        System.out.println("\n--- 2. Translator vs Native Execution Comparison ---");
        System.out.println("┌──────────────────┬──────────────────┬──────────────────┬──────────────────┬──────────────────┬──────────────┐");
        System.out.println("│ Operator         │  Translate (ms)  │   Native (ms)    │  Execute (ms)    │   Direct (ms)    │   Overhead   │");
        System.out.println("├──────────────────┼──────────────────┼──────────────────┼──────────────────┼──────────────────┼──────────────┤");

        Object refId = refEdge.id();

        for (TemporalQuerySpec.AllenOperator op : OPERATORS) {
            String opName = op.name();
            
            // Warm up
            warmupBenchmark(op, refId);

            // Benchmark: Translation time
            double translatorAvg = benchmarkTranslator(op, refId);

            // Benchmark: Native T-TinkerPop time
            double nativeAvg = benchmarkNative(op);

            // Benchmark: Execution time ONLY (no translation)
            double execAvg = benchmarkExecuteIterate(op, refId);

            // Benchmark: Direct query execution (manual has() filters)
            double directAvg = benchmarkDirectQuery(op);

            double overhead = (nativeAvg > 0) ? execAvg / nativeAvg : 0.0;
            System.out.printf("│ %-16s │ %16.3f │ %16.3f │ %16.3f │ %16.3f │ %12.1fx │\n",
                    opName, translatorAvg, nativeAvg, execAvg, directAvg, overhead);
        }
        System.out.println("└──────────────────┴──────────────────┴──────────────────┴──────────────────┴──────────────────┴──────────────┘");
        System.out.println(" * Translate: Time to convert Spec -> Bytecode");
        System.out.println(" * Native:    Time to run g.E().temporalX().iterate() (Java)");
        System.out.println(" * Execute:   Time to run generated bytecode.iterate()");
        System.out.println(" * Direct:    Time to run manual g.E().has(...).iterate()");
    }

    private void warmupBenchmark(TemporalQuerySpec.AllenOperator op, Object refId) {
        for (int i = 0; i < currentWarmupIters; i++) {
            TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
                .withElementType(TemporalQuerySpec.ElementType.EDGE)
                .withReferenceVertex(refId);
            TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);
            executor.executeIterate(result.bytecode);
        }
    }

    private double benchmarkTranslator(TemporalQuerySpec.AllenOperator op, Object refId) {
        long translateTime = 0;
        for (int i = 0; i < currentBenchmarkIters; i++) {
            TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
                .withElementType(TemporalQuerySpec.ElementType.EDGE)
                .withReferenceVertex(refId);
            
            long start = System.nanoTime();
            translator.translate(spec);
            translateTime += (System.nanoTime() - start);
        }
        return translateTime / (1_000_000.0 * currentBenchmarkIters);
    }

    private double benchmarkNative(TemporalQuerySpec.AllenOperator op) {
        long nativeTime = 0;
        for (int i = 0; i < currentBenchmarkIters; i++) {
            long start = System.nanoTime();
            switch (op) {
                case temporalBefore: g.E().hasLabel("flight").temporalBefore(refEdge).iterate(); break;
                case temporalAfter: g.E().hasLabel("flight").temporalAfter(refEdge).iterate(); break;
                case temporalMeets: g.E().hasLabel("flight").temporalMeets(refEdge).iterate(); break;
                case temporalMetBy: g.E().hasLabel("flight").temporalMetBy(refEdge).iterate(); break;
                case temporalOverlaps: g.E().hasLabel("flight").temporalOverlaps(refEdge).iterate(); break;
                case temporalOverlappedBy: g.E().hasLabel("flight").temporalOverlappedBy(refEdge).iterate(); break;
                case temporalStarts: g.E().hasLabel("flight").temporalStarts(refEdge).iterate(); break;
                case temporalStartedBy: g.E().hasLabel("flight").temporalStartedBy(refEdge).iterate(); break;
                case temporalDuring: g.E().hasLabel("flight").temporalDuring(refEdge).iterate(); break;
                case temporalContains: g.E().hasLabel("flight").temporalContains(refEdge).iterate(); break;
                case temporalEnds: g.E().hasLabel("flight").temporalFinishes(refEdge).iterate(); break;
                case temporalEndedBy: g.E().hasLabel("flight").temporalFinishedBy(refEdge).iterate(); break;
                case temporalEqual: g.E().hasLabel("flight").temporalEquals(refEdge).iterate(); break;
                default: 
                    // Fallback or skip
            }
            nativeTime += (System.nanoTime() - start);
        }
        return nativeTime / (1_000_000.0 * currentBenchmarkIters);
    }

    private double benchmarkExecuteIterate(TemporalQuerySpec.AllenOperator op, Object refId) {
        TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
            .withElementType(TemporalQuerySpec.ElementType.EDGE)
            .withReferenceVertex(refId);
        TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);
        String translatedBytecode = result.bytecode;

        long execTime = 0;
        for (int i = 0; i < currentBenchmarkIters; i++) {
            long start = System.nanoTime();
            executor.executeIterate(translatedBytecode);
            execTime += (System.nanoTime() - start);
        }
        return execTime / (1_000_000.0 * currentBenchmarkIters);
    }

    private double benchmarkDirectQuery(TemporalQuerySpec.AllenOperator op) {
        Date refStartDate;
        Date refEndDate;
        try {
            Lifetime lt = Lifetime.getLifetimeFromProperties(refEdge);
            if (lt != null && !lt.isEmpty()) {
                refStartDate = new Date(lt.getStartMillis(0));
                refEndDate = new Date(lt.getEndMillis(0));
            } else if (refEdge.property("startTime").isPresent() && refEdge.property("endTime").isPresent()) {
                Object s = refEdge.property("startTime").value();
                Object e = refEdge.property("endTime").value();
                if (s instanceof Date) {
                    refStartDate = (Date) s;
                    refEndDate = (Date) e;
                } else if (s instanceof Number) {
                    refStartDate = new Date(((Number) s).longValue());
                    refEndDate = new Date(((Number) e).longValue());
                } else {
                    refStartDate = new Date(LocalDateTime.parse(s.toString()).toInstant(ZoneOffset.UTC).toEpochMilli());
                    refEndDate = new Date(LocalDateTime.parse(e.toString()).toInstant(ZoneOffset.UTC).toEpochMilli());
                }
            } else {
                return 0.0;
            }
        } catch (Exception e) {
            return 0.0;
        }

        long directTime = 0;
        for (int i = 0; i < currentBenchmarkIters; i++) {
            long start = System.nanoTime();
            switch (op) {
                case temporalBefore:
                    g.E().hasLabel("flight").has("endTime", P.lt(refStartDate)).iterate();
                    break;
                case temporalAfter:
                    g.E().hasLabel("flight").has("startTime", P.gt(refEndDate)).iterate();
                    break;
                case temporalMeets:
                    g.E().hasLabel("flight").has("endTime", P.eq(refStartDate)).iterate();
                    break;
                case temporalMetBy:
                    g.E().hasLabel("flight").has("startTime", P.eq(refEndDate)).iterate();
                    break;
                case temporalOverlaps:
                    g.E().hasLabel("flight")
                            .has("startTime", P.lt(refStartDate))
                            .has("endTime", P.gt(refStartDate))
                            .has("endTime", P.lt(refEndDate)).iterate();
                    break;
                case temporalOverlappedBy:
                    g.E().hasLabel("flight")
                            .has("startTime", P.gt(refStartDate))
                            .has("startTime", P.lt(refEndDate))
                            .has("endTime", P.gt(refEndDate)).iterate();
                    break;
                case temporalStarts:
                    g.E().hasLabel("flight")
                            .has("startTime", P.eq(refStartDate))
                            .has("endTime", P.lt(refEndDate)).iterate();
                    break;
                case temporalStartedBy:
                    g.E().hasLabel("flight")
                            .has("startTime", P.eq(refStartDate))
                            .has("endTime", P.gt(refEndDate)).iterate();
                    break;
                case temporalDuring:
                    g.E().hasLabel("flight")
                            .has("startTime", P.gt(refStartDate))
                            .has("endTime", P.lt(refEndDate)).iterate();
                    break;
                case temporalContains:
                    g.E().hasLabel("flight")
                            .has("startTime", P.lt(refStartDate))
                            .has("endTime", P.gt(refEndDate)).iterate();
                    break;
                case temporalEnds:
                    g.E().hasLabel("flight")
                            .has("startTime", P.gt(refStartDate))
                            .has("endTime", P.eq(refEndDate)).iterate();
                    break;
                case temporalEndedBy:
                    g.E().hasLabel("flight")
                            .has("startTime", P.lt(refStartDate))
                            .has("endTime", P.eq(refEndDate)).iterate();
                    break;
                case temporalEqual:
                    g.E().hasLabel("flight")
                            .has("startTime", P.eq(refStartDate))
                            .has("endTime", P.eq(refEndDate)).iterate();
                    break;
                default:
                    break;
            }
            directTime += (System.nanoTime() - start);
        }
        return directTime / (1_000_000.0 * currentBenchmarkIters);
    }

    private void cleanup() {
        if (g != null) {
            try { g.close(); } catch (Exception e) {}
        }
        if (graph != null) {
            try { graph.close(); } catch (Exception e) {}
        }
    }
}
