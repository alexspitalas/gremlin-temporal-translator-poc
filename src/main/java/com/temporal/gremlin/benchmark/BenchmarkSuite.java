package com.temporal.gremlin.benchmark;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator for the T-TinkerPop Benchmarking & Correctness Suite.
 * Supports both an interactive CLI Menu and non-interactive command-line flags.
 */
public class BenchmarkSuite {

    public static void main(String[] args) {
        CliArgsParser.CliOptions opts = CliArgsParser.parse(args);

        if (opts.showHelp) {
            CliArgsParser.printHelp();
            return;
        }

        if (opts.listAll) {
            printCatalog();
            return;
        }

        if (opts.interactiveMenu) {
            CliMenu menu = new CliMenu();
            CliMenu.MenuConfig mCfg = menu.runInteractiveMenu();
            if (mCfg.cancelled) {
                return;
            }
            applyMenuConfig(opts, mCfg);
        }

        executeSuite(opts);
    }

    private static void applyMenuConfig(CliArgsParser.CliOptions opts, CliMenu.MenuConfig mCfg) {
        opts.mode = mCfg.mode;
        opts.benchmarkSuite = mCfg.benchmarkSuite;
        opts.targetBenchmark = mCfg.singleBenchmarkTarget;
        opts.testSuite = mCfg.testSuite;
        opts.demoTarget = mCfg.demoTarget;
        opts.datasetPath = mCfg.customDatasetPath;
        opts.scale = mCfg.scale;
        opts.customSizes = mCfg.customSizes;
        opts.outputPath = mCfg.outputDestination;
        opts.saveTxt = mCfg.saveTxt;
        opts.saveMd = mCfg.saveMd;
    }

    private static void executeSuite(CliArgsParser.CliOptions opts) {
        if (opts.datasetPath != null && !opts.datasetPath.trim().isEmpty()) {
            System.setProperty("dataset.path", opts.datasetPath.trim());
        }
        BenchmarkOutput output = new BenchmarkOutput(opts.outputPath, opts.saveTxt, opts.saveMd);
        BenchmarkContext ctx = new BenchmarkContext(
                opts.datasetPath,
                opts.scale,
                opts.customSizes,
                -1,
                -1,
                output
        );

        output.header("T-TINKERPOP BENCHMARK & CORRECTNESS SUITE");
        output.println("Mode:        " + opts.mode.toUpperCase());
        output.println("Scale:       " + opts.scale.getDisplayName());
        try {
            File ds = ctx.getDatasetFile();
            output.println("Dataset:     " + ds.getAbsolutePath() + " (" + (ds.length() / (1024 * 1024)) + " MB)");
        } catch (Exception e) {
            output.println("Dataset:     " + (opts.datasetPath != null ? opts.datasetPath : "Default OpenSky (Not required for all tests)"));
        }
        if (opts.outputPath != null) {
            output.println("Output Dest: " + opts.outputPath);
        }
        output.println("-".repeat(80));

        boolean overallSuccess = true;

        try {
            if ("demo".equals(opts.mode)) {
                output.beginCaptureSection("Feature Demo (" + (opts.demoTarget != null ? opts.demoTarget : "all") + ")");
                DemoRunner.runDemo(opts.demoTarget, output);
                output.endCaptureSection();
                return;
            }

            if ("test".equals(opts.mode) || "both".equals(opts.mode)) {
                boolean testsPassed = TestRunnerBridge.runTestSuite(opts.testSuite, output);
                if (!testsPassed) {
                    overallSuccess = false;
                }
            }

            if ("bench".equals(opts.mode) || "both".equals(opts.mode)) {
                List<BenchmarkTask> tasksToRun = resolveTasks(opts);
                output.println("\nExecuting " + tasksToRun.size() + " benchmark tasks...");

                for (int i = 0; i < tasksToRun.size(); i++) {
                    BenchmarkTask task = tasksToRun.get(i);
                    String sectionHeader = String.format("[%d/%d] %s — %s", i + 1, tasksToRun.size(), task.getId(), task.getName());
                    output.println(String.format("%n[%d/%d] Running %s (%s)...", i + 1, tasksToRun.size(), task.getId(), task.getName()));
                    output.beginCaptureSection(sectionHeader);
                    long s = System.currentTimeMillis();
                    try {
                        task.run(ctx);
                        long dur = System.currentTimeMillis() - s;
                        output.printf("Completed %s in %.2fs%n", task.getId(), dur / 1000.0);
                    } catch (Exception e) {
                        output.println("Error running benchmark '" + task.getId() + "': " + e.getMessage());
                        e.printStackTrace();
                        overallSuccess = false;
                    } finally {
                        output.endCaptureSection();
                    }
                }
            }
        } finally {
            output.finish();
        }

        if (!overallSuccess) {
            System.exit(1);
        }
    }

    private static List<BenchmarkTask> resolveTasks(CliArgsParser.CliOptions opts) {
        List<BenchmarkTask> tasks = new ArrayList<>();

        if (opts.targetBenchmark != null && !opts.targetBenchmark.trim().isEmpty()) {
            BenchmarkTask single = BenchmarkRegistry.getById(opts.targetBenchmark);
            if (single != null) {
                tasks.add(single);
                return tasks;
            } else {
                System.err.println("Warning: Benchmark target '" + opts.targetBenchmark + "' not found. Falling back to suite.");
            }
        }

        BenchmarkGroup group = BenchmarkGroup.fromString(opts.benchmarkSuite);
        return BenchmarkRegistry.getTasksByGroup(group);
    }

    private static void printCatalog() {
        System.out.println("==========================================================================================");
        System.out.println("  AVAILABLE BENCHMARKS & SUITES CATALOG");
        System.out.println("==========================================================================================");
        System.out.println("\n--- Benchmark Groups ---");
        for (BenchmarkGroup g : BenchmarkGroup.values()) {
            System.out.printf("  • %-12s - %-55s%n    %s%n", g.getId(), g.getDisplayName(), g.getDescription());
        }

        System.out.println("\n--- Registered Benchmarks ---");
        for (BenchmarkTask t : BenchmarkRegistry.getAllTasks()) {
            System.out.printf("  [%-22s] %s%n    Group: %-10s | %s%n",
                    t.getId(), t.getName(), t.getGroup().getId(), t.getDescription());
        }

        System.out.println("\n--- Correctness Test Suites ---");
        System.out.println("  • all          - All 17 JUnit correctness and cross-engine parity tests");
        for (String key : TestRunnerBridge.TEST_CLASS_MAP.keySet()) {
            System.out.printf("  • %-12s - %s%n", key, TestRunnerBridge.TEST_CLASS_MAP.get(key));
        }

        System.out.println("\n--- Interactive Demos ---");
        for (String key : DemoRunner.DEMO_MAP.keySet()) {
            System.out.printf("  • %-14s - %s%n", key, DemoRunner.DEMO_DESC.get(key));
        }
        System.out.println("==========================================================================================");
    }
}
