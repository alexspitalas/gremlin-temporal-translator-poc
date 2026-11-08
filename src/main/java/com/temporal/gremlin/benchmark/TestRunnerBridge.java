package com.temporal.gremlin.benchmark;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Executes JUnit correctness and cross-engine parity tests programmatically,
 * logging structured outcomes directly to the BenchmarkOutput.
 */
public class TestRunnerBridge {

    public static final Map<String, String> TEST_CLASS_MAP = new LinkedHashMap<>();
    static {
        TEST_CLASS_MAP.put("paper", "com.temporal.gremlin.PaperBenchmarksCorrectnessTest");
        TEST_CLASS_MAP.put("chronograph", "com.temporal.gremlin.ChronoGraphParityTest");
        TEST_CLASS_MAP.put("pagerank", "com.temporal.gremlin.TemporalPageRankTest");
        TEST_CLASS_MAP.put("multi-interval", "com.temporal.gremlin.MultiIntervalStressTest");
        TEST_CLASS_MAP.put("representation", "com.temporal.gremlin.RepresentationAblationTest");
    }

    /**
     * Runs tests for the specified suite ("all", "paper", "chronograph", etc.)
     */
    public static boolean runTestSuite(String suiteName, BenchmarkOutput output) {
        output.header("RUNNING CORRECTNESS & CROSS-ENGINE PARITY TESTS");
        output.println("Target Suite: " + suiteName.toUpperCase());
        output.println("Verifying exact match parity, mathematical semantics, and score parity across execution modes...");
        output.println("-".repeat(80));

        List<String> targetClasses = new ArrayList<>();
        if ("all".equalsIgnoreCase(suiteName) || suiteName == null || suiteName.trim().isEmpty()) {
            targetClasses.addAll(TEST_CLASS_MAP.values());
        } else {
            String lower = suiteName.trim().toLowerCase();
            if (TEST_CLASS_MAP.containsKey(lower)) {
                targetClasses.add(TEST_CLASS_MAP.get(lower));
            } else {
                for (Map.Entry<String, String> entry : TEST_CLASS_MAP.entrySet()) {
                    if (entry.getValue().toLowerCase().contains(lower)) {
                        targetClasses.add(entry.getValue());
                        break;
                    }
                }
            }
        }

        if (targetClasses.isEmpty()) {
            output.println("Unknown test suite: '" + suiteName + "'. Available: " + TEST_CLASS_MAP.keySet() + " or 'all'");
            return false;
        }

        // Check if classes are loadable in-process
        boolean inProcessPossible = true;
        List<Class<?>> classesToRun = new ArrayList<>();
        for (String cName : targetClasses) {
            try {
                classesToRun.add(Class.forName(cName));
            } catch (ClassNotFoundException e) {
                inProcessPossible = false;
                break;
            }
        }

        if (inProcessPossible) {
            return runInProcess(classesToRun, output);
        } else {
            return runViaMavenSubprocess(targetClasses, output);
        }
    }

    private static boolean runInProcess(List<Class<?>> classes, BenchmarkOutput output) {
        JUnitCore junit = new JUnitCore();
        final Map<String, Long> startTimes = new HashMap<>();
        final Set<String> failedMethods = new HashSet<>();

        junit.addListener(new RunListener() {
            @Override
            public void testStarted(Description description) {
                String key = description.getClassName() + "#" + description.getMethodName();
                startTimes.put(key, System.currentTimeMillis());
            }

            @Override
            public void testFinished(Description description) {
                String key = description.getClassName() + "#" + description.getMethodName();
                long start = startTimes.getOrDefault(key, System.currentTimeMillis());
                long dur = System.currentTimeMillis() - start;

                String simpleClass = description.getTestClass().getSimpleName();
                String method = description.getMethodName();

                if (!failedMethods.contains(key)) {
                    output.printf("  ✓ [PASS] %-35s :: %-45s (%d ms)%n", simpleClass, method, dur);
                    output.recordTestResult(simpleClass, method, true, dur, null);
                }
            }

            @Override
            public void testFailure(Failure failure) {
                Description desc = failure.getDescription();
                String key = desc.getClassName() + "#" + desc.getMethodName();
                failedMethods.add(key);

                long start = startTimes.getOrDefault(key, System.currentTimeMillis());
                long dur = System.currentTimeMillis() - start;

                String simpleClass = desc.getTestClass() != null ? desc.getTestClass().getSimpleName() : desc.getClassName();
                String method = desc.getMethodName();
                String msg = failure.getMessage();

                output.printf("  ✗ [FAIL] %-35s :: %-45s (%d ms)%n", simpleClass, method, dur);
                output.println("     Error: " + msg);
                output.recordTestResult(simpleClass, method, false, dur, msg);
            }
        });

        long overallStart = System.currentTimeMillis();
        Result res = junit.run(classes.toArray(new Class<?>[0]));
        long totalElapsed = System.currentTimeMillis() - overallStart;

        output.println("-".repeat(80));
        output.printf("Summary: %d tests completed in %.2fs. %d failures, %d ignored.%n",
                res.getRunCount(), totalElapsed / 1000.0, res.getFailureCount(), res.getIgnoreCount());

        if (res.wasSuccessful()) {
            output.println("Status: 100% PARITY & CORRECTNESS VERIFIED across all engines! ✅");
        } else {
            output.println("Status: FAILURES ENCOUNTERED ❌");
        }
        return res.wasSuccessful();
    }

    private static boolean runViaMavenSubprocess(List<String> targetClasses, BenchmarkOutput output) {
        output.println("Test classes not found on runtime classpath. Running via 'mvn test' subprocess...");
        StringBuilder testArg = new StringBuilder();
        for (int i = 0; i < targetClasses.size(); i++) {
            String c = targetClasses.get(i);
            int idx = c.lastIndexOf('.');
            String simple = idx > 0 ? c.substring(idx + 1) : c;
            testArg.append(simple);
            if (i < targetClasses.size() - 1) testArg.append(",");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "test", "-Dtest=" + testArg.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Running com.temporal.gremlin") ||
                            line.contains("Tests run:") ||
                            line.contains("BUILD SUCCESS") ||
                            line.contains("BUILD FAILURE") ||
                            line.contains("<<< FAILURE!")) {
                        output.println("  " + line);
                    }
                }
            }
            int exitCode = p.waitFor();
            boolean ok = (exitCode == 0);
            output.println("Maven test execution finished with code: " + exitCode + (ok ? " (SUCCESS)" : " (FAILED)"));
            return ok;
        } catch (Exception e) {
            output.println("Error executing maven test process: " + e.getMessage());
            return false;
        }
    }
}
