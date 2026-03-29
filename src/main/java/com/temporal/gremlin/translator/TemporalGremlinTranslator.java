package com.temporal.gremlin.translator;

import com.temporal.gremlin.query.TemporalQuerySpec;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import java.time.Instant;
import java.util.*;

/**
 * Main Temporal Gremlin Translator.
 * Coordinates the 4-phase translation pipeline: Parse → Transform → Optimize →
 * Codegen
 */
public class TemporalGremlinTranslator {

    private GremlinTransformer transformer;
    private StepOptimizer optimizer;
    private BytecodeGenerator codegen;

    private StepOptimizer.OptimizationLevel optimizationLevel;
    private BytecodeGenerator.TranslationStrategy strategy;
    private boolean verbose = false;

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Constructor with default settings.
     */
    public TemporalGremlinTranslator(GraphTraversalSource g) {
        this(g,
                StepOptimizer.OptimizationLevel.BASIC,
                BytecodeGenerator.TranslationStrategy.FUSION);
    }

    /**
     * Constructor with custom configuration.
     */
    public TemporalGremlinTranslator(GraphTraversalSource g,
            StepOptimizer.OptimizationLevel optLevel,
            BytecodeGenerator.TranslationStrategy strategy) {
        this.optimizationLevel = optLevel;
        this.strategy = strategy;

        // Lazy initialization - will be done per translation
        this.transformer = new GremlinTransformer(null, g);
    }

    /**
     * Main translation entry point: converts temporal query to Gremlin bytecode.
     */
    public TranslationResult translate(TemporalQuerySpec querySpec) {

        TranslationMetrics metrics = new TranslationMetrics(UUID.randomUUID().toString());

        try {
            // Phase 1: Transform
            this.transformer = new GremlinTransformer(metrics, null);
            List<GremlinTransformer.GremlinStep> steps = transformer.transform(querySpec);

            // Phase 3: Optimize
            this.optimizer = new StepOptimizer(metrics, optimizationLevel);
            List<GremlinTransformer.GremlinStep> optimized = optimizer.optimize(steps);

            // Phase 4: Codegen
            this.codegen = new BytecodeGenerator(metrics, strategy, verbose);
            String bytecode = codegen.generate(optimized);

            // Finalize metrics
            metrics.recordStepCount(optimized.size());
            metrics.finalizeMetric();

            return new TranslationResult(querySpec, steps, optimized, bytecode, metrics);

        } catch (Exception e) {
            throw new RuntimeException("Translation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Translates temporal transaction (mutations) to Gremlin bytecode.
     */
    public TranslationResult translate(com.temporal.gremlin.query.TemporalTransaction transaction) {
        TranslationMetrics metrics = new TranslationMetrics(UUID.randomUUID().toString());

        try {
            // Phase 1: Transform
            this.transformer = new GremlinTransformer(metrics, null);
            List<GremlinTransformer.GremlinStep> steps = transformer.transform(transaction);

            // Phase 3: Optimize
            this.optimizer = new StepOptimizer(metrics, optimizationLevel);
            List<GremlinTransformer.GremlinStep> optimized = optimizer.optimize(steps);

            // Phase 4: Codegen
            this.codegen = new BytecodeGenerator(metrics, strategy, verbose);
            String bytecode = codegen.generate(optimized);

            // Finalize metrics
            metrics.recordStepCount(optimized.size());
            metrics.finalizeMetric();

            return new TranslationResult(null, steps, optimized, bytecode, metrics);

        } catch (Exception e) {
            throw new RuntimeException("Translation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Translates temporal query with fluent API.
     */
    public TranslationResult translateQuery(String v1, String v2,
            TemporalQuerySpec.AllenOperator operator) {

        TemporalQuerySpec spec = new TemporalQuerySpec(v1, v2, operator);
        return translate(spec);
    }

    /**
     * Translates temporal query with lifetime constraints.
     */
    public TranslationResult translateQuery(String v1, String v2,
            TemporalQuerySpec.AllenOperator operator,
            Instant lifetimeStart,
            Instant lifetimeEnd) {

        TemporalQuerySpec spec = new TemporalQuerySpec(v1, v2, operator)
                .withLifetime(lifetimeStart, lifetimeEnd);
        return translate(spec);
    }

    public void setOptimizationLevel(StepOptimizer.OptimizationLevel level) {
        this.optimizationLevel = level;
    }

    public void setStrategy(BytecodeGenerator.TranslationStrategy strategy) {
        this.strategy = strategy;
    }

    public StepOptimizer.OptimizationLevel getOptimizationLevel() {
        return optimizationLevel;
    }

    public BytecodeGenerator.TranslationStrategy getStrategy() {
        return strategy;
    }

    /**
     * Container for translation results.
     */
    public static class TranslationResult {
        public final TemporalQuerySpec querySpec;
        public final List<GremlinTransformer.GremlinStep> untranslatedSteps;
        public final List<GremlinTransformer.GremlinStep> optimizedSteps;
        public final String bytecode;
        public final TranslationMetrics metrics;

        public TranslationResult(TemporalQuerySpec querySpec,
                List<GremlinTransformer.GremlinStep> untranslatedSteps,
                List<GremlinTransformer.GremlinStep> optimizedSteps,
                String bytecode, TranslationMetrics metrics) {
            this.querySpec = querySpec;
            this.untranslatedSteps = untranslatedSteps;
            this.optimizedSteps = optimizedSteps;
            this.bytecode = bytecode;
            this.metrics = metrics;
        }

        public void printReport() {
            System.out.println("\n" + metrics.getReport());
            System.out.println("\nGenerated Bytecode:\n" + bytecode);
        }
    }
}
