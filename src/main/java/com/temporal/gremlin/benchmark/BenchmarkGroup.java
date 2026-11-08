package com.temporal.gremlin.benchmark;

/**
 * Grouping categories for benchmarks.
 */
public enum BenchmarkGroup {
    PAPER("paper", "Paper Reproduction Suite (Tables V, VI, VIII, IX, X, XI)",
            "Reproduces all benchmark tables published in the T-TinkerPop journal paper."),
    MACRO("macro", "Macro-Benchmarks (Reachability & Streaming Analytics)",
            "Complex end-to-end algorithms: Temporal BFS reachability, Temporal PageRank, and Multi-Interval stress."),
    MICRO("micro", "Micro-Benchmarks (Storage, Representation, Selectivity)",
            "Component-level measurements: index crossover, representation ablation, and lifetime storage."),
    TRANSLATOR("translator", "Translator Overhead vs Native T-Gremlin",
            "4-phase translation pipeline latency breakdown and comparison with native Java TinkerPop."),
    ALL("all", "All Available Benchmarks",
            "Runs all 8 macro and micro benchmarks sequentially.");

    private final String id;
    private final String displayName;
    private final String description;

    BenchmarkGroup(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static BenchmarkGroup fromString(String str) {
        if (str == null || str.trim().isEmpty()) return PAPER;
        String s = str.trim().toLowerCase();
        for (BenchmarkGroup g : values()) {
            if (g.id.equalsIgnoreCase(s) || g.name().equalsIgnoreCase(s)) {
                return g;
            }
        }
        return PAPER;
    }
}
