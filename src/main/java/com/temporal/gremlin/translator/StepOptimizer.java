package com.temporal.gremlin.translator;

import java.util.*;

/**
 * Phase 3: Optimizes Gremlin step sequences.
 * Applies operator fusion, filter combination, and step elimination.
 */
public class StepOptimizer {
    
    private TranslationMetrics metrics;
    private OptimizationLevel level;

    public enum OptimizationLevel {
        NONE,           // No optimization
        BASIC,          // Operator fusion and filter combination
        ADVANCED        // Full optimization: reordering, elimination, predicate pushdown
    }

    public StepOptimizer(TranslationMetrics metrics, OptimizationLevel level) {
        this.metrics = metrics;
        this.level = level;
    }

    /**
     * Optimizes step sequence based on configured level.
     */
    public List<GremlinTransformer.GremlinStep> optimize(List<GremlinTransformer.GremlinStep> steps) {
        long startTime = System.nanoTime();

        List<GremlinTransformer.GremlinStep> optimized = new ArrayList<>(steps);

        if (level == OptimizationLevel.NONE) {
            long endTime = System.nanoTime();
            metrics.recordOptimizationTime((endTime - startTime) / 1_000_000.0);
            return optimized;
        }

        if (level == OptimizationLevel.BASIC || level == OptimizationLevel.ADVANCED) {
            optimized = fuseAdjacentFilters(optimized);
            optimized = eliminateRedundantSteps(optimized);
        }

        if (level == OptimizationLevel.ADVANCED) {
            optimized = reorderSteps(optimized);
            optimized = pushdownPredicates(optimized);
        }

        long endTime = System.nanoTime();
        metrics.recordOptimizationTime((endTime - startTime) / 1_000_000.0);

        return optimized;
    }

    /**
     * Fuses adjacent filter steps into a single composite filter.
     */
    private List<GremlinTransformer.GremlinStep> fuseAdjacentFilters(List<GremlinTransformer.GremlinStep> steps) {
        List<GremlinTransformer.GremlinStep> result = new ArrayList<>();
        List<String> filterBuffer = new ArrayList<>();

        for (GremlinTransformer.GremlinStep step : steps) {
            if (step.code.startsWith("filter(")) {
                String condition = extractFilterCondition(step.code);
                filterBuffer.add(condition);
            } else {
                // Flush accumulated filters
                if (!filterBuffer.isEmpty()) {
                    String combined = String.join(" && ", filterBuffer);
                    result.add(new GremlinTransformer.GremlinStep(
                        "filter(" + combined + ")",
                        "Fused filter: " + combined.substring(0, Math.min(50, combined.length())) + "..."
                    ));
                    filterBuffer.clear();
                }
                result.add(step);
            }
        }

        // Flush remaining filters
        if (!filterBuffer.isEmpty()) {
            String combined = String.join(" && ", filterBuffer);
            result.add(new GremlinTransformer.GremlinStep(
                "filter(" + combined + ")",
                "Fused filter"
            ));
        }

        return result;
    }

    /**
     * Eliminates redundant steps (e.g., duplicate filters, no-op operations).
     */
    private List<GremlinTransformer.GremlinStep> eliminateRedundantSteps(List<GremlinTransformer.GremlinStep> steps) {
        Set<String> seen = new HashSet<>();
        List<GremlinTransformer.GremlinStep> result = new ArrayList<>();

        for (GremlinTransformer.GremlinStep step : steps) {
            String normalized = normalizeStep(step.code);
            
            if (!seen.contains(normalized)) {
                seen.add(normalized);
                result.add(step);
            }
        }

        return result;
    }

    /**
     * Reorders steps for better execution efficiency.
     * Principle: cheap filters first, expensive operations last.
     */
    private List<GremlinTransformer.GremlinStep> reorderSteps(List<GremlinTransformer.GremlinStep> steps) {
        // Separate steps by cost category
        List<GremlinTransformer.GremlinStep> initialization = new ArrayList<>();
        List<GremlinTransformer.GremlinStep> filters = new ArrayList<>();
        List<GremlinTransformer.GremlinStep> orderby = new ArrayList<>();
        List<GremlinTransformer.GremlinStep> terminal = new ArrayList<>();

        for (GremlinTransformer.GremlinStep step : steps) {
            if (step.code.contains("g.V()") || step.code.contains("has(")) {
                initialization.add(step);
            } else if (step.code.contains("filter")) {
                filters.add(step);
            } else if (step.code.contains("order")) {
                orderby.add(step);
            } else {
                terminal.add(step);
            }
        }

        List<GremlinTransformer.GremlinStep> result = new ArrayList<>();
        result.addAll(initialization);
        result.addAll(filters);
        result.addAll(orderby);
        result.addAll(terminal);

        return result;
    }

    /**
     * Pushes predicates down the pipeline for early filtering.
     * Reduces amount of data processed by expensive operations.
     */
    private List<GremlinTransformer.GremlinStep> pushdownPredicates(List<GremlinTransformer.GremlinStep> steps) {
        // Identify selective filters that can be pushed down
        List<GremlinTransformer.GremlinStep> result = new ArrayList<>(steps);
        
        List<Integer> filterIndices = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).code.contains("filter")) {
                filterIndices.add(i);
            }
        }

        // Move selective filters earlier in the sequence
        for (int idx : filterIndices) {
            GremlinTransformer.GremlinStep filter = result.get(idx);
            if (isSelectiveFilter(filter.code)) {
                result.remove(idx);
                int insertPos = Math.min(2, result.size());
                result.add(insertPos, filter);
            }
        }

        return result;
    }

    private String extractFilterCondition(String filterStep) {
        int start = filterStep.indexOf('(') + 1;
        int end = filterStep.lastIndexOf(')');
        return filterStep.substring(start, end).trim();
    }

    private String normalizeStep(String code) {
        return code.replaceAll("\\s+", " ").toLowerCase();
    }

    private boolean isSelectiveFilter(String filterCode) {
        // Heuristics: filters with equality, range checks are selective
        return filterCode.contains("==") || filterCode.contains("<") || filterCode.contains(">");
    }

    public OptimizationLevel getLevel() {
        return level;
    }

    public void setLevel(OptimizationLevel level) {
        this.level = level;
    }
}
