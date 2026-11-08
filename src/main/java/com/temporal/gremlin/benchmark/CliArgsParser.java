package com.temporal.gremlin.benchmark;

/**
 * Parses command-line arguments for non-interactive execution of benchmarks,
 * tests, and demos.
 */
public class CliArgsParser {

    public static class CliOptions {
        public boolean showHelp = false;
        public boolean listAll = false;
        public boolean interactiveMenu = false;

        public String mode = "bench";              // bench, test, both, demo, list
        public String benchmarkSuite = "paper";     // paper, macro, micro, translator, all
        public String targetBenchmark = null;       // specific benchmark id
        public String testSuite = "all";            // all, paper, chronograph, pagerank, multi-interval, representation
        public String demoTarget = null;
        public String datasetPath = null;
        public ScaleMode scale = ScaleMode.FULL;
        public int[] customSizes = null;
        public String outputPath = "results/";
        public boolean saveTxt = true;
        public boolean saveMd = true;
    }

    public static CliOptions parse(String[] args) {
        CliOptions opts = new CliOptions();

        if (args == null || args.length == 0) {
            // Default when no args is interactive menu
            opts.interactiveMenu = true;
            return opts;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();

            if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                opts.showHelp = true;
                return opts;
            } else if ("--list".equalsIgnoreCase(arg) || "-l".equalsIgnoreCase(arg)) {
                opts.listAll = true;
                return opts;
            } else if ("--menu".equalsIgnoreCase(arg) || "-i".equalsIgnoreCase(arg) || "--interactive".equalsIgnoreCase(arg)) {
                opts.interactiveMenu = true;
                return opts;
            } else if (arg.startsWith("--mode=")) {
                opts.mode = arg.substring("--mode=".length()).trim().toLowerCase();
            } else if ("-m".equalsIgnoreCase(arg) && i + 1 < args.length) {
                opts.mode = args[++i].trim().toLowerCase();
            } else if (arg.startsWith("--suite=")) {
                opts.benchmarkSuite = arg.substring("--suite=".length()).trim().toLowerCase();
            } else if ("-s".equalsIgnoreCase(arg) && i + 1 < args.length) {
                opts.benchmarkSuite = args[++i].trim().toLowerCase();
            } else if (arg.startsWith("--target=")) {
                opts.targetBenchmark = arg.substring("--target=".length()).trim().toLowerCase();
            } else if ("-t".equalsIgnoreCase(arg) && i + 1 < args.length) {
                opts.targetBenchmark = args[++i].trim().toLowerCase();
            } else if (arg.startsWith("--test-suite=")) {
                opts.testSuite = arg.substring("--test-suite=".length()).trim().toLowerCase();
                if ("bench".equals(opts.mode)) opts.mode = "test";
            } else if (arg.startsWith("--demo=")) {
                opts.demoTarget = arg.substring("--demo=".length()).trim().toLowerCase();
                opts.mode = "demo";
            } else if (arg.startsWith("--dataset=")) {
                opts.datasetPath = arg.substring("--dataset=".length()).trim();
            } else if ("-d".equalsIgnoreCase(arg) && i + 1 < args.length) {
                opts.datasetPath = args[++i].trim();
            } else if (arg.startsWith("--scale=")) {
                opts.scale = ScaleMode.fromString(arg.substring("--scale=".length()).trim());
            } else if ("-sc".equalsIgnoreCase(arg) && i + 1 < args.length) {
                opts.scale = ScaleMode.fromString(args[++i].trim());
            } else if (arg.startsWith("--sizes=")) {
                opts.scale = ScaleMode.CUSTOM;
                opts.customSizes = parseSizes(arg.substring("--sizes=".length()));
            } else if (arg.startsWith("--output=")) {
                opts.outputPath = arg.substring("--output=".length()).trim();
            } else if ("-o".equalsIgnoreCase(arg) && i + 1 < args.length) {
                opts.outputPath = args[++i].trim();
            } else if (arg.startsWith("--format=")) {
                String fmt = arg.substring("--format=".length()).trim().toLowerCase();
                configureFormats(opts, fmt);
            } else if ("-f".equalsIgnoreCase(arg) && i + 1 < args.length) {
                configureFormats(opts, args[++i].trim().toLowerCase());
            } else if ("--no-save".equalsIgnoreCase(arg) || "--no-file".equalsIgnoreCase(arg)) {
                opts.outputPath = null;
                opts.saveTxt = false;
                opts.saveMd = false;
            }
        }

        return opts;
    }

    private static void configureFormats(CliOptions opts, String fmt) {
        if ("txt".equals(fmt)) {
            opts.saveTxt = true;
            opts.saveMd = false;
        } else if ("md".equals(fmt) || "markdown".equals(fmt)) {
            opts.saveTxt = false;
            opts.saveMd = true;
        } else if ("none".equals(fmt)) {
            opts.saveTxt = false;
            opts.saveMd = false;
            opts.outputPath = null;
        } else {
            opts.saveTxt = true;
            opts.saveMd = true;
        }
    }

    private static int[] parseSizes(String str) {
        String[] tokens = str.split("[,\\s]+");
        int[] result = new int[tokens.length];
        int count = 0;
        for (String t : tokens) {
            try {
                result[count++] = Integer.parseInt(t.trim());
            } catch (NumberFormatException ignored) {}
        }
        if (count == 0) return null;
        int[] trimmed = new int[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return trimmed;
    }

    public static void printHelp() {
        System.out.println("==========================================================================================");
        System.out.println("  T-TinkerPop Benchmarking & Cross-Engine Parity Verification Suite");
        System.out.println("==========================================================================================");
        System.out.println("Usage:");
        System.out.println("  ./bench.sh [OPTIONS]");
        System.out.println("  mvn exec:java -Dexec.args=\"[OPTIONS]\"");
        System.out.println();
        System.out.println("Core Options:");
        System.out.println("  --menu, -i                  Launch interactive CLI Menu (Default when no args given)");
        System.out.println("  --mode, -m <mode>           Execution mode: bench | test | both | demo | list (default: bench)");
        System.out.println("  --suite, -s <suite>         Benchmark suite to run: paper | macro | micro | translator | all (default: paper)");
        System.out.println("  --target, -t <target>       Run single specific benchmark by name/id:");
        System.out.println("                              chronograph | index-selectivity | representation-ablation |");
        System.out.println("                              multi-interval | pagerank | capabilities | lifetime-storage |");
        System.out.println("                              translator-vs-native");
        System.out.println("  --test-suite <suite>        Run correctness test suite: all | paper | chronograph | pagerank |");
        System.out.println("                              multi-interval | representation (default: all)");
        System.out.println("  --demo <demo-name>          Run demo: translator | correctness | apps | continuous-path | string");
        System.out.println();
        System.out.println("Dataset & Scale Options:");
        System.out.println("  --dataset, -d <path>        Custom dataset CSV path (default: auto-detected OpenSky CSV)");
        System.out.println("  --scale, -sc <scale>        Workload scale: full | quick | custom (default: full)");
        System.out.println("  --sizes <s1,s2,...>         Custom sizes (e.g. --sizes=10000,50000,100000)");
        System.out.println();
        System.out.println("Output & Reporting Options:");
        System.out.println("  --output, -o <path>         Output directory or file path (default: results/)");
        System.out.println("  --format, -f <format>       Output format: both | txt | md | none (default: both)");
        System.out.println("  --no-save                   Disable saving results to file (console output only)");
        System.out.println();
        System.out.println("Informational Options:");
        System.out.println("  --list, -l                  List all available benchmarks, test suites, and demos");
        System.out.println("  --help, -h                  Display this help reference");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ./bench.sh                                      # Launch interactive CLI menu");
        System.out.println("  ./bench.sh --mode=bench --suite=paper           # Run paper benchmark suite (default)");
        System.out.println("  ./bench.sh --mode=bench --scale=quick           # Fast smoke-test run (~30s)");
        System.out.println("  ./bench.sh --mode=test                          # Run all 17 cross-engine parity tests");
        System.out.println("  ./bench.sh --mode=both                          # Run parity tests + full benchmarks");
        System.out.println("  ./bench.sh --target=chronograph                 # Run single benchmark (Table V)");
        System.out.println("  ./bench.sh --dataset=/data/my_flights.csv       # Run with custom dataset");
        System.out.println("  ./bench.sh --output=results/my_run.txt          # Save to specific file");
        System.out.println("==========================================================================================");
    }
}
