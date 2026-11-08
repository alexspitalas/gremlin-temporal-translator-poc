package com.temporal.gremlin.benchmark;

import com.temporal.gremlin.bench.*;

import java.util.*;

/**
 * Central catalog of all available benchmarks, organized by unique ID and BenchmarkGroup.
 */
public class BenchmarkRegistry {

    private static final Map<String, BenchmarkTask> TASKS_BY_ID = new LinkedHashMap<>();

    static {
        register(new ChronoGraphBenchmark());
        register(new IndexSelectivityTradeoffBenchmark());
        register(new RepresentationAblationBenchmark());
        register(new MultiIntervalStressBenchmark());
        register(new TemporalPageRankBenchmark());
        register(new TTinkerPopCapabilitiesScalingBenchmark());
        register(new TemporalLifetimeStorageBenchmark());
        register(new TranslatorVsNativeBenchmark());
    }

    private static void register(BenchmarkTask task) {
        TASKS_BY_ID.put(task.getId().toLowerCase(), task);
    }

    public static BenchmarkTask getById(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase();
        BenchmarkTask task = TASKS_BY_ID.get(key);
        if (task != null) return task;

        // Try fuzzy prefix / contains match
        for (Map.Entry<String, BenchmarkTask> entry : TASKS_BY_ID.entrySet()) {
            if (entry.getKey().contains(key) || entry.getValue().getName().toLowerCase().contains(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static List<BenchmarkTask> getAllTasks() {
        return new ArrayList<>(TASKS_BY_ID.values());
    }

    public static List<BenchmarkTask> getTasksByGroup(BenchmarkGroup group) {
        List<BenchmarkTask> list = new ArrayList<>();
        if (group == BenchmarkGroup.ALL) {
            return getAllTasks();
        }

        for (BenchmarkTask task : TASKS_BY_ID.values()) {
            if (task.getGroup() == group) {
                list.add(task);
            } else if (group == BenchmarkGroup.MACRO && isMacroTask(task.getId())) {
                list.add(task);
            } else if (group == BenchmarkGroup.MICRO && isMicroTask(task.getId())) {
                list.add(task);
            }
        }
        return list;
    }

    private static boolean isMacroTask(String id) {
        return "chronograph".equals(id) || "pagerank".equals(id) || "multi-interval".equals(id);
    }

    private static boolean isMicroTask(String id) {
        return "index-selectivity".equals(id) || "representation-ablation".equals(id) || "lifetime-storage".equals(id);
    }

    public static Set<String> getRegisteredIds() {
        return Collections.unmodifiableSet(TASKS_BY_ID.keySet());
    }
}
