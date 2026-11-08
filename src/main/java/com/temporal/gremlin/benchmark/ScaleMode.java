package com.temporal.gremlin.benchmark;

/**
 * Scale modes for running benchmarks.
 */
public enum ScaleMode {
    FULL("full", "Full Journal Paper Scale", "Evaluates full paper workloads up to 1,000,000+ elements."),
    QUICK("quick", "Quick Smoke-Test Scale", "Evaluates smaller workloads (~5k-20k elements) for fast verification in ~30s."),
    CUSTOM("custom", "Custom Scale", "Uses user-specified element sizes and iteration counts.");

    private final String id;
    private final String displayName;
    private final String description;

    ScaleMode(String id, String displayName, String description) {
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

    public static ScaleMode fromString(String str) {
        if (str == null || str.trim().isEmpty()) return FULL;
        String s = str.trim().toLowerCase();
        for (ScaleMode sm : values()) {
            if (sm.id.equalsIgnoreCase(s) || sm.name().equalsIgnoreCase(s)) {
                return sm;
            }
        }
        return FULL;
    }
}
