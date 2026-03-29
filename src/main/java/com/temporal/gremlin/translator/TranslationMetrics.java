package com.temporal.gremlin.translator;

import java.util.*;

/**
 * Collects and tracks translation performance metrics.
 * Measures overhead at each phase of the translation pipeline.
 */
public class TranslationMetrics {
    
    private String queryId;
    private long startTime;
    
    private double parsingTime;
    private double transformTime;
    private double optimizationTime;
    private double codegenTime;
    private double totalTime;
    
    private int bytecodeSize;
    private int stepCount;
    private int vertexCount;
    
    private Map<String, Long> phaseTiming;
    private Map<String, Object> additionalMetrics;

    public TranslationMetrics(String queryId) {
        this.queryId = queryId;
        this.startTime = System.nanoTime();
        this.phaseTiming = new HashMap<>();
        this.additionalMetrics = new HashMap<>();
    }

    public void recordParsingTime(double milliseconds) {
        this.parsingTime = milliseconds;
        phaseTiming.put("parsing", (long)milliseconds);
    }

    public void recordTransformTime(double milliseconds) {
        this.transformTime = milliseconds;
        phaseTiming.put("transform", (long)milliseconds);
    }

    public void recordOptimizationTime(double milliseconds) {
        this.optimizationTime = milliseconds;
        phaseTiming.put("optimization", (long)milliseconds);
    }

    public void recordCodegenTime(double milliseconds) {
        this.codegenTime = milliseconds;
        phaseTiming.put("codegen", (long)milliseconds);
    }

    public void recordBytecodeSize(int bytes) {
        this.bytecodeSize = bytes;
    }

    public void recordStepCount(int count) {
        this.stepCount = count;
    }

    public void recordVertexCount(int count) {
        this.vertexCount = count;
    }

    public void recordMetric(String key, Object value) {
        additionalMetrics.put(key, value);
    }

    public void finalizeMetric() {
        this.totalTime = parsingTime + transformTime + optimizationTime + codegenTime;
    }

    public String getQueryId() {
        return queryId;
    }

    public double getParsingTime() {
        return parsingTime;
    }

    public double getTransformTime() {
        return transformTime;
    }

    public double getOptimizationTime() {
        return optimizationTime;
    }

    public double getCodegenTime() {
        return codegenTime;
    }

    public double getTotalTime() {
        return totalTime;
    }

    public int getBytecodeSize() {
        return bytecodeSize;
    }

    public int getStepCount() {
        return stepCount;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public Map<String, Long> getPhaseTiming() {
        return phaseTiming;
    }

    public Map<String, Object> getAdditionalMetrics() {
        return additionalMetrics;
    }

    /**
     * Returns a summary report of translation metrics.
     */
    public String getReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Translation Metrics Report ===\n");
        sb.append("Query ID: ").append(queryId).append("\n");
        sb.append("\nPhase Timing:\n");
        sb.append(String.format("  Parsing:      %.3f ms\n", parsingTime));
        sb.append(String.format("  Transform:    %.3f ms\n", transformTime));
        sb.append(String.format("  Optimization: %.3f ms\n", optimizationTime));
        sb.append(String.format("  Codegen:      %.3f ms\n", codegenTime));
        sb.append(String.format("  Total:        %.3f ms\n", totalTime));
        sb.append("\nCodegeneration Metrics:\n");
        sb.append(String.format("  Bytecode Size: %d bytes\n", bytecodeSize));
        sb.append(String.format("  Step Count:    %d\n", stepCount));
        sb.append("\nAdditional Metrics:\n");
        additionalMetrics.forEach((k, v) -> 
            sb.append(String.format("  %s: %s\n", k, v))
        );
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("TranslationMetrics{total=%.3fms}", 
            totalTime);
    }
}
