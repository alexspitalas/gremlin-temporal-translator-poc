package com.temporal.gremlin.translator;

import java.util.*;

/**
 * Phase 4: Generates Gremlin bytecode from optimized steps.
 * Converts step sequence to executable bytecode format.
 */
public class BytecodeGenerator {
    
    private TranslationMetrics metrics;
    private TranslationStrategy strategy;
    private boolean verbose = false;

    public enum TranslationStrategy {
        LITERAL,        // 1:1 step to bytecode mapping
        FUSION,         // Combine related steps
        BYTECODE_DIRECT // Skip AST, generate bytecode directly
    }

    public BytecodeGenerator(TranslationMetrics metrics, TranslationStrategy strategy) {
        this.metrics = metrics;
        this.strategy = strategy;
    }

    public BytecodeGenerator(TranslationMetrics metrics, TranslationStrategy strategy, boolean verbose) {
        this.metrics = metrics;
        this.strategy = strategy;
        this.verbose = verbose;
    }

    /**
     * Generates Gremlin bytecode from step sequence.
     */
    public String generate(List<GremlinTransformer.GremlinStep> steps) {
        long startTime = System.nanoTime();

        StringBuilder bytecode = new StringBuilder();
        if (verbose) {
            bytecode.append("// Generated Gremlin Bytecode\n");
            bytecode.append("// Strategy: ").append(strategy).append("\n");
            bytecode.append("// Steps: ").append(steps.size()).append("\n\n");
        }

        switch (strategy) {
            case LITERAL:
                bytecode.append(generateLiteralBytecode(steps));
                break;
            case FUSION:
                bytecode.append(generateFusedBytecode(steps));
                break;
            case BYTECODE_DIRECT:
                bytecode.append(generateDirectBytecode(steps));
                break;
        }

        long endTime = System.nanoTime();
        metrics.recordCodegenTime((endTime - startTime) / 1_000_000.0);
        metrics.recordBytecodeSize(bytecode.length());

        return bytecode.toString();
    }

    /**
     * Generates 1:1 step-to-bytecode mapping (most verbose).
     */
    private String generateLiteralBytecode(List<GremlinTransformer.GremlinStep> steps) {
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < steps.size(); i++) {
            GremlinTransformer.GremlinStep step = steps.get(i);
            int indent = i > 0 ? 2 : 0;
            String indentation = " ".repeat(indent);
            
            if (i == 0) {
                sb.append(step.code);
            } else {
                sb.append("\n").append(indentation).append(".").append(step.code);
            }
            
            if (i < steps.size() - 1) {
                sb.append(" \\");
            }
        }

        return sb.toString();
    }

    /**
     * Generates fused bytecode with combined operations.
     */
    private String generateFusedBytecode(List<GremlinTransformer.GremlinStep> steps) {
        StringBuilder sb = new StringBuilder();
        List<String> fusedLines = new ArrayList<>();

        String currentChain = "";
        
        for (GremlinTransformer.GremlinStep step : steps) {
            if (step.code.startsWith("g.")) {
                if (!currentChain.isEmpty()) {
                    fusedLines.add(currentChain);
                }
                currentChain = step.code;
            } else if (step.code.startsWith(".")) {
                currentChain += step.code;
            } else {
                currentChain += "." + step.code;
            }
        }

        if (!currentChain.isEmpty()) {
            fusedLines.add(currentChain);
        }

        for (int i = 0; i < fusedLines.size(); i++) {
            String line = fusedLines.get(i);
            if (i == 0) {
                sb.append(line);
            } else {
                sb.append("\n  .").append(line.substring(1));
            }
        }

        return sb.toString();
    }

    /**
     * Generates direct bytecode without intermediate representation.
     */
    private String generateDirectBytecode(List<GremlinTransformer.GremlinStep> steps) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("[\n");
        
        for (int i = 0; i < steps.size(); i++) {
            GremlinTransformer.GremlinStep step = steps.get(i);
            String bytecodeOp = convertToBytecodeOp(step.code);
            
            sb.append("  {\n");
            sb.append("    \"op\": \"").append(bytecodeOp).append("\",\n");
            sb.append("    \"args\": [").append(extractArgs(step.code)).append("]\n");
            sb.append("  }");
            
            if (i < steps.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }
        
        sb.append("]");
        return sb.toString();
    }

    /**
     * Converts Gremlin step to bytecode operation code.
     */
    private String convertToBytecodeOp(String step) {
        if (step.contains("has(")) return "has";
        if (step.contains("filter(")) return "filter";
        if (step.contains("order(")) return "order";
        if (step.contains("path(")) return "path";
        if (step.contains("by(")) return "by";
        return "step";
    }

    /**
     * Extracts arguments from a Gremlin step.
     */
    private String extractArgs(String step) {
        List<String> args = new ArrayList<>();
        
        int start = step.indexOf('(');
        int end = step.lastIndexOf(')');
        
        if (start > 0 && end > start) {
            String content = step.substring(start + 1, end);
            args.add("\"" + content.replace("\"", "\\\"") + "\"");
        }
        
        return String.join(", ", args);
    }

    public TranslationStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(TranslationStrategy strategy) {
        this.strategy = strategy;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }
}
