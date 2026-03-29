package com.temporal.gremlin.bench;

import com.temporal.gremlin.query.TemporalQuerySpec;
import com.temporal.gremlin.translator.*;
import com.temporal.gremlin.executor.GremlinExecutor;
import com.temporal.gremlin.util.GraphDataLoader;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
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
public class TranslatorVsNativeBenchmark {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 50;
    private static final String CSV_PATH = "flightlist_20190101_20190131.csv";
    
    private static final TemporalQuerySpec.AllenOperator[] OPERATORS = {
        TemporalQuerySpec.AllenOperator.temporalBefore,
        TemporalQuerySpec.AllenOperator.temporalAfter,
        TemporalQuerySpec.AllenOperator.temporalMeets,
        TemporalQuerySpec.AllenOperator.temporalMetBy,
        TemporalQuerySpec.AllenOperator.temporalOverlaps,
        TemporalQuerySpec.AllenOperator.temporalDuring,
        TemporalQuerySpec.AllenOperator.temporalEqual
    };

    private Graph graph;
    private GraphTraversalSource g;
    private TemporalGremlinTranslator translator;
    private GremlinExecutor executor;
    private Edge refEdge;

    public static void main(String[] args) {
        TranslatorVsNativeBenchmark bench = new TranslatorVsNativeBenchmark();
        
        // Scalability sizes
        int[] sizes = {1000, 10000, 100000}; 
        String[] labels = {"1,000", "10,000", "100,000"};

        for (int i = 0; i < sizes.length; i++) {
            bench.runForSize(sizes[i], labels[i]);
        }
    }

    private void runForSize(int size, String label) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  BENCHMARK SCALE: " + label + " Flights");
        System.out.println("=".repeat(70));

        try {
            initialize(size);
            
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
        GraphDataLoader loader = new GraphDataLoader(g);
        loader.load_dataset_flight_native(CSV_PATH, size); 

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
        for(int i=0; i<WARMUP_ITERATIONS; i++) {
            translator.translate(spec);
        }

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);
            totalParsing += result.metrics.getParsingTime();
            totalTransform += result.metrics.getTransformTime();
            totalOptimization += result.metrics.getOptimizationTime();
            totalCodegen += result.metrics.getCodegenTime();
        }
        
        double total = totalParsing + totalTransform + totalOptimization + totalCodegen;
        double avgTotal = total / BENCHMARK_ITERATIONS;

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
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
                .withElementType(TemporalQuerySpec.ElementType.EDGE)
                .withReferenceVertex(refId);
            TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);
            executor.executeIterate(result.bytecode);
        }
    }

    private double benchmarkTranslator(TemporalQuerySpec.AllenOperator op, Object refId) {
        long translateTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
                .withElementType(TemporalQuerySpec.ElementType.EDGE)
                .withReferenceVertex(refId);
            
            long start = System.nanoTime();
            translator.translate(spec);
            translateTime += (System.nanoTime() - start);
        }
        return translateTime / (1_000_000.0 * BENCHMARK_ITERATIONS);
    }

    private double benchmarkNative(TemporalQuerySpec.AllenOperator op) {
        long nativeTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
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
        return nativeTime / (1_000_000.0 * BENCHMARK_ITERATIONS);
    }

    private double benchmarkExecuteIterate(TemporalQuerySpec.AllenOperator op, Object refId) {
        TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
            .withElementType(TemporalQuerySpec.ElementType.EDGE)
            .withReferenceVertex(refId);
        TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);
        String translatedBytecode = result.bytecode;

        long execTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            executor.executeIterate(translatedBytecode);
            execTime += (System.nanoTime() - start);
        }
        return execTime / (1_000_000.0 * BENCHMARK_ITERATIONS);
    }

    private double benchmarkDirectQuery(TemporalQuerySpec.AllenOperator op) {
        if (!refEdge.property("startTime").isPresent() || !refEdge.property("endTime").isPresent()) return 0.0;
        
        Long refStart, refEnd;
        try {
             Object s = refEdge.property("startTime").value();
             if (s instanceof Number) {
                 refStart = ((Number) s).longValue();
                 refEnd = ((Number) refEdge.property("endTime").value()).longValue();
             } else {
                 refStart = LocalDateTime.parse(s.toString()).toInstant(ZoneOffset.UTC).toEpochMilli();
                 refEnd = LocalDateTime.parse(refEdge.property("endTime").value().toString()).toInstant(ZoneOffset.UTC).toEpochMilli();
             }
        } catch (Exception e) {
            return 0.0; 
        }

        long directTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            switch (op) {
                case temporalAfter: g.E().hasLabel("flight").has("startTime", P.gt(refEnd)).iterate(); break;
                case temporalBefore: g.E().hasLabel("flight").has("endTime", P.lt(refStart)).iterate(); break;
                case temporalMeets: g.E().hasLabel("flight").has("endTime", P.eq(refStart)).iterate(); break;
                case temporalMetBy: g.E().hasLabel("flight").has("startTime", P.eq(refEnd)).iterate(); break;
                case temporalOverlaps: g.E().hasLabel("flight").has("startTime", P.lt(refStart)).has("endTime", P.gt(refStart)).has("endTime", P.lt(refEnd)).iterate(); break;
                case temporalDuring: g.E().hasLabel("flight").has("startTime", P.gt(refStart)).has("endTime", P.lt(refEnd)).iterate(); break;
                case temporalEqual: g.E().hasLabel("flight").has("startTime", P.eq(refStart)).has("endTime", P.eq(refEnd)).iterate(); break;
                default: 
                    // Others skipped for brevity or complexity in manual construction
                    // (temporalOverlappedBy, Starts, StartedBy, Contains, Ends, EndedBy)
            }
            directTime += (System.nanoTime() - start);
        }
        return directTime / (1_000_000.0 * BENCHMARK_ITERATIONS);
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
