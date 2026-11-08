package com.temporal.gremlin.benchmark;

import com.temporal.gremlin.util.DatasetResolver;

import java.io.File;
import java.util.List;
import java.util.Scanner;

/**
 * Interactive text-based CLI menu with sensible defaults for running benchmarks,
 * correctness tests, and demos.
 */
public class CliMenu {

    public static class MenuConfig {
        public String mode = "bench";              // bench, test, both, demo
        public String benchmarkSuite = "paper";     // paper, macro, micro, translator, all, single
        public String singleBenchmarkTarget = null;
        public String testSuite = "all";            // all, paper, chronograph, pagerank, multi-interval, representation
        public String demoTarget = "translator";
        public String customDatasetPath = null;
        public ScaleMode scale = ScaleMode.FULL;
        public int[] customSizes = null;
        public String outputDestination = "results/";
        public boolean saveTxt = true;
        public boolean saveMd = true;
        public boolean cancelled = false;
    }

    private final Scanner scanner;

    public CliMenu() {
        this.scanner = new Scanner(System.in);
    }

    public MenuConfig runInteractiveMenu() {
        MenuConfig cfg = new MenuConfig();

        printBanner();

        // 1. Choose Main Action
        System.out.println("What would you like to run?");
        System.out.println("  [1] Run Benchmark Suite (Default: Paper Suite Tables VI-XI & Figures)  [Default]");
        System.out.println("  [2] Run Correctness & Cross-Engine Parity Tests (JUnit 17 Tests)");
        System.out.println("  [3] Run Full Verification + Benchmarks (Both)");
        System.out.println("  [4] Run Interactive Demos (Translator, Apps, Continuous Path)");
        System.out.println("  [0] Exit");
        System.out.print("Select an option [1]: ");

        String choice = readLineTrimmed();
        if ("0".equals(choice) || "q".equalsIgnoreCase(choice)) {
            cfg.cancelled = true;
            return cfg;
        }

        if ("2".equals(choice)) {
            cfg.mode = "test";
            configureTestSuite(cfg);
        } else if ("3".equals(choice)) {
            cfg.mode = "both";
            configureBenchmarkSuite(cfg);
        } else if ("4".equals(choice)) {
            cfg.mode = "demo";
            configureDemo(cfg);
            return cfg; // Demos run directly
        } else {
            cfg.mode = "bench";
            configureBenchmarkSuite(cfg);
        }

        // Configure Dataset (needed for benchmarks and tests)
        configureDataset(cfg);

        // Configure Scale (for benchmarks)
        if (!"test".equals(cfg.mode)) {
            configureScale(cfg);
        }

        // Configure Output Destination
        configureOutput(cfg);

        // Confirmation summary
        if (!confirmRun(cfg)) {
            cfg.cancelled = true;
            System.out.println("Run cancelled.");
            return cfg;
        }

        return cfg;
    }

    private void printBanner() {
        System.out.println();
        System.out.println("==================================================================================");
        System.out.println("   T-TinkerPop Benchmarking & Cross-Engine Parity Verification Suite");
        System.out.println("   Temporal Graph Analytics: Apache TinkerPop 3.7.3-T vs ChronoGraph");
        System.out.println("==================================================================================");
        System.out.println();
    }

    private void configureBenchmarkSuite(MenuConfig cfg) {
        System.out.println("\nSelect Benchmark Suite to Run:");
        System.out.println("  [1] Paper Reproduction Suite (Tables VI-XI & Figures 1, 2, 4)     [Default]");
        System.out.println("  [2] Macro-Benchmarks (tBFS reachability, PageRank, Multi-Interval, Path Semantics)");
        System.out.println("  [3] Micro-Benchmarks (Index Selectivity, Representation Ablation, Storage)");
        System.out.println("  [4] Translator vs Native Benchmark (Pipeline breakdown & T-Gremlin)");
        System.out.println("  [5] Run All Benchmarks");
        System.out.println("  [6] Single Benchmark by Name/Number...");
        System.out.print("Select suite [1]: ");

        String sChoice = readLineTrimmed();
        if ("2".equals(sChoice)) {
            cfg.benchmarkSuite = "macro";
        } else if ("3".equals(sChoice)) {
            cfg.benchmarkSuite = "micro";
        } else if ("4".equals(sChoice)) {
            cfg.benchmarkSuite = "translator";
        } else if ("5".equals(sChoice)) {
            cfg.benchmarkSuite = "all";
        } else if ("6".equals(sChoice)) {
            cfg.benchmarkSuite = "single";
            selectSingleBenchmark(cfg);
        } else {
            cfg.benchmarkSuite = "paper";
        }
    }

    private void selectSingleBenchmark(MenuConfig cfg) {
        List<BenchmarkTask> all = BenchmarkRegistry.getAllTasks();
        System.out.println("\nSelect Specific Benchmark:");
        for (int i = 0; i < all.size(); i++) {
            BenchmarkTask t = all.get(i);
            System.out.printf("  [%d] %-25s - %s%n", i + 1, t.getId(), t.getName());
        }
        System.out.print("Enter number [1]: ");
        String bChoice = readLineTrimmed();
        int idx = 0;
        try {
            if (!bChoice.isEmpty()) {
                idx = Integer.parseInt(bChoice) - 1;
            }
        } catch (NumberFormatException ignored) {}

        if (idx >= 0 && idx < all.size()) {
            cfg.singleBenchmarkTarget = all.get(idx).getId();
        } else {
            cfg.singleBenchmarkTarget = all.get(0).getId();
        }
    }

    private void configureTestSuite(MenuConfig cfg) {
        System.out.println("\nSelect Correctness Test Suite:");
        System.out.println("  [1] All 17 Parity & Correctness Tests (Paper Tables VI-XI + ChronoGraph) [Default]");
        System.out.println("  [2] Paper Parity Suite (PaperBenchmarksCorrectnessTest)");
        System.out.println("  [3] ChronoGraph Allen & BFS Parity (ChronoGraphParityTest)");
        System.out.println("  [4] Temporal PageRank Score Parity (TemporalPageRankTest)");
        System.out.println("  [5] Multi-Interval Sweep Parity (MultiIntervalStressTest)");
        System.out.println("  [6] Representation Match Parity (RepresentationAblationTest)");
        System.out.print("Select test suite [1]: ");

        String tChoice = readLineTrimmed();
        if ("2".equals(tChoice)) {
            cfg.testSuite = "paper";
        } else if ("3".equals(tChoice)) {
            cfg.testSuite = "chronograph";
        } else if ("4".equals(tChoice)) {
            cfg.testSuite = "pagerank";
        } else if ("5".equals(tChoice)) {
            cfg.testSuite = "multi-interval";
        } else if ("6".equals(tChoice)) {
            cfg.testSuite = "representation";
        } else {
            cfg.testSuite = "all";
        }
    }

    private void configureDemo(MenuConfig cfg) {
        System.out.println("\nSelect Demo Program:");
        System.out.println("  [1] TranslatorDemo (Interactive 4-phase translation pipeline)   [Default]");
        System.out.println("  [2] CorrectnessDemo (Native vs Translator Graph Comparison)");
        System.out.println("  [3] TemporalApplicationsDemo (Temporal algorithms)");
        System.out.println("  [4] TestContinuousPath (Continuous temporal path demo)");
        System.out.println("  [5] StringTransformerDemo (String query parsing demo)");
        System.out.print("Select demo [1]: ");

        String dChoice = readLineTrimmed();
        if ("2".equals(dChoice)) cfg.demoTarget = "correctness";
        else if ("3".equals(dChoice)) cfg.demoTarget = "apps";
        else if ("4".equals(dChoice)) cfg.demoTarget = "continuous-path";
        else if ("5".equals(dChoice)) cfg.demoTarget = "string";
        else cfg.demoTarget = "translator";
    }

    private void configureDataset(MenuConfig cfg) {
        System.out.println("\nDataset Configuration:");
        boolean defaultAvail = DatasetResolver.isDefaultDatasetAvailable();
        String defaultMsg = defaultAvail ? "(Found: " + DatasetResolver.DEFAULT_FLIGHT_CSV + ")" : "(Not detected in default paths)";
        System.out.println("  [1] Default OpenSky Dataset " + defaultMsg + " [Default]");
        System.out.println("  [2] Enter Custom CSV File Path");
        System.out.print("Select dataset [1]: ");

        String dChoice = readLineTrimmed();
        if ("2".equals(dChoice)) {
            System.out.print("Enter absolute or relative path to CSV dataset: ");
            String customPath = readLineTrimmed();
            File f = new File(customPath);
            if (!f.exists() || !f.isFile()) {
                System.out.println("  ⚠️ Warning: File does not exist at '" + customPath + "'. Falling back to default resolver.");
            } else {
                cfg.customDatasetPath = f.getAbsolutePath();
            }
        }
    }

    private void configureScale(MenuConfig cfg) {
        System.out.println("\nBenchmark Scale / Workload Size:");
        System.out.println("  [1] Full Journal Paper Scale (exact sizes up to 1M+ elements)    [Default]");
        System.out.println("  [2] Quick Smoke-Test Scale (fast run for testing, ~30s)");
        System.out.println("  [3] Custom Sizes (specify comma-separated integers)");
        System.out.print("Select scale [1]: ");

        String sChoice = readLineTrimmed();
        if ("2".equals(sChoice)) {
            cfg.scale = ScaleMode.QUICK;
        } else if ("3".equals(sChoice)) {
            cfg.scale = ScaleMode.CUSTOM;
            System.out.print("Enter sizes (e.g. 10000,50000,100000): ");
            String input = readLineTrimmed();
            String[] tokens = input.split("[,\\s]+");
            int[] sizes = new int[tokens.length];
            int count = 0;
            for (String tok : tokens) {
                try {
                    sizes[count++] = Integer.parseInt(tok.trim());
                } catch (NumberFormatException ignored) {}
            }
            if (count > 0) {
                int[] trimmed = new int[count];
                System.arraycopy(sizes, 0, trimmed, 0, count);
                cfg.customSizes = trimmed;
            } else {
                cfg.scale = ScaleMode.FULL;
            }
        } else {
            cfg.scale = ScaleMode.FULL;
        }
    }

    private void configureOutput(MenuConfig cfg) {
        System.out.println("\nResults Destination & Format:");
        System.out.println("  [1] Console + Save to results/ (TXT Log and Markdown Report)   [Default]");
        System.out.println("  [2] Custom output directory or file path");
        System.out.println("  [3] Console only (do not save to file)");
        System.out.print("Select output [1]: ");

        String oChoice = readLineTrimmed();
        if ("2".equals(oChoice)) {
            System.out.print("Enter output directory or file path [results/]: ");
            String path = readLineTrimmed();
            cfg.outputDestination = path.isEmpty() ? "results/" : path;
            cfg.saveTxt = true;
            cfg.saveMd = true;
        } else if ("3".equals(oChoice)) {
            cfg.outputDestination = null;
            cfg.saveTxt = false;
            cfg.saveMd = false;
        } else {
            cfg.outputDestination = "results/";
            cfg.saveTxt = true;
            cfg.saveMd = true;
        }
    }

    private boolean confirmRun(MenuConfig cfg) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  PLANNED EXECUTION SUMMARY");
        System.out.println("=".repeat(80));
        System.out.printf("  • Mode:              %s%n", cfg.mode.toUpperCase());
        if ("bench".equals(cfg.mode) || "both".equals(cfg.mode)) {
            String target = cfg.singleBenchmarkTarget != null ? cfg.singleBenchmarkTarget : cfg.benchmarkSuite.toUpperCase();
            System.out.printf("  • Benchmark Suite:   %s%n", target);
            System.out.printf("  • Workload Scale:    %s%n", cfg.scale.getDisplayName());
        }
        if ("test".equals(cfg.mode) || "both".equals(cfg.mode)) {
            System.out.printf("  • Correctness Suite: %s%n", cfg.testSuite.toUpperCase());
        }
        if (cfg.customDatasetPath != null) {
            System.out.printf("  • Dataset:           %s%n", cfg.customDatasetPath);
        } else {
            System.out.printf("  • Dataset:           %s (Auto-resolved)%n", DatasetResolver.DEFAULT_FLIGHT_CSV);
        }
        if (cfg.outputDestination != null) {
            System.out.printf("  • Output Saved To:   %s (TXT + Markdown)%n", cfg.outputDestination);
        } else {
            System.out.printf("  • Output:            Console Only%n");
        }
        System.out.println("=".repeat(80));
        System.out.print("Press [Enter] to start execution (or 'q' to cancel): ");

        String conf = readLineTrimmed();
        return !"q".equalsIgnoreCase(conf) && !"n".equalsIgnoreCase(conf);
    }

    private String readLineTrimmed() {
        try {
            if (scanner.hasNextLine()) {
                return scanner.nextLine().trim();
            }
        } catch (Exception ignored) {}
        return "";
    }
}
