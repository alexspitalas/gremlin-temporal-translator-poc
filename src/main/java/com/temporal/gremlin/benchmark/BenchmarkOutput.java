package com.temporal.gremlin.benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Handles dual output to the console and structured files (Plaintext and Markdown).
 */
public class BenchmarkOutput {

    private final boolean saveToFile;
    private final boolean markdownEnabled;
    private final File txtOutputFile;
    private final File mdOutputFile;

    private final PrintStream originalOut;
    private PrintStream teeOut;
    private PrintWriter txtWriter;
    private PrintWriter mdWriter;
    private StringBuilder activeSectionBuffer;
    private String activeSectionTitle;

    private final List<TestRecord> testRecords = new ArrayList<>();
    private final long startTimeMillis;

    public static class TestRecord {
        public final String testClass;
        public final String testMethod;
        public final boolean passed;
        public final long durationMs;
        public final String failureDetail;

        public TestRecord(String testClass, String testMethod, boolean passed, long durationMs, String failureDetail) {
            this.testClass = testClass;
            this.testMethod = testMethod;
            this.passed = passed;
            this.durationMs = durationMs;
            this.failureDetail = failureDetail;
        }
    }

    public BenchmarkOutput(String destinationPath, boolean enableTxt, boolean enableMd) {
        this.startTimeMillis = System.currentTimeMillis();
        this.originalOut = System.out;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date(startTimeMillis));

        if (destinationPath == null || destinationPath.trim().isEmpty() || "none".equalsIgnoreCase(destinationPath.trim())) {
            this.saveToFile = false;
            this.markdownEnabled = false;
            this.txtOutputFile = null;
            this.mdOutputFile = null;
            return;
        }

        this.saveToFile = enableTxt || enableMd;
        this.markdownEnabled = enableMd;

        File dest = new File(destinationPath.trim());
        File baseDir;
        String baseName;

        if (dest.isDirectory() || destinationPath.endsWith("/") || destinationPath.endsWith("\\") || !dest.getName().contains(".")) {
            baseDir = dest;
            baseName = "benchmark_" + timestamp;
        } else {
            baseDir = dest.getParentFile() != null ? dest.getParentFile() : new File(".");
            String name = dest.getName();
            int dotIdx = name.lastIndexOf('.');
            baseName = dotIdx > 0 ? name.substring(0, dotIdx) : name;
        }

        if (baseDir != null && !baseDir.exists()) {
            baseDir.mkdirs();
        }

        if (enableTxt) {
            this.txtOutputFile = new File(baseDir, baseName + ".txt");
            try {
                this.txtWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(this.txtOutputFile), StandardCharsets.UTF_8), true);
            } catch (IOException e) {
                originalOut.println("Warning: Could not create TXT output file: " + e.getMessage());
                this.txtWriter = null;
            }
        } else {
            this.txtOutputFile = null;
        }

        if (enableMd) {
            this.mdOutputFile = new File(baseDir, baseName + ".md");
            try {
                this.mdWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(this.mdOutputFile), StandardCharsets.UTF_8), true);
                initMarkdownReport();
            } catch (IOException e) {
                originalOut.println("Warning: Could not create Markdown output file: " + e.getMessage());
                this.mdWriter = null;
            }
        } else {
            this.mdOutputFile = null;
        }

        if (this.saveToFile) {
            installTeeStream();
        }
    }

    private void installTeeStream() {
        OutputStream teeStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                originalOut.write(b);
                String s = new String(new byte[]{(byte) b}, StandardCharsets.UTF_8);
                if (txtWriter != null) {
                    txtWriter.print(s);
                }
                if (activeSectionBuffer != null) {
                    activeSectionBuffer.append(s);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                originalOut.write(b, off, len);
                String s = new String(b, off, len, StandardCharsets.UTF_8);
                if (txtWriter != null) {
                    txtWriter.print(s);
                }
                if (activeSectionBuffer != null) {
                    activeSectionBuffer.append(s);
                }
            }

            @Override
            public void flush() throws IOException {
                originalOut.flush();
                if (txtWriter != null) {
                    txtWriter.flush();
                }
            }
        };
        this.teeOut = new PrintStream(teeStream, true, StandardCharsets.UTF_8);
        System.setOut(this.teeOut);
    }

    public void beginCaptureSection(String title) {
        endCaptureSection();
        this.activeSectionTitle = title;
        this.activeSectionBuffer = new StringBuilder();
    }

    public void endCaptureSection() {
        if (this.activeSectionBuffer != null && mdWriter != null) {
            String content = this.activeSectionBuffer.toString().trim();
            if (!content.isEmpty()) {
                if (activeSectionTitle != null && !activeSectionTitle.isEmpty()) {
                    mdWriter.println("### " + activeSectionTitle);
                    mdWriter.println();
                }
                mdWriter.println("```text");
                mdWriter.println(content);
                mdWriter.println("```");
                mdWriter.println();
                mdWriter.flush();
            }
        }
        this.activeSectionBuffer = null;
        this.activeSectionTitle = null;
    }

    private void initMarkdownReport() {
        if (mdWriter == null) return;
        mdWriter.println("# T-TinkerPop Benchmarks & Correctness Report");
        mdWriter.println();
        mdWriter.println("**Date:** " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new Date(startTimeMillis)));
        mdWriter.println("**Java Version:** " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        mdWriter.println("**OS:** " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        mdWriter.println("**Available Processors:** " + Runtime.getRuntime().availableProcessors());
        mdWriter.println("**Max Memory:** " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        mdWriter.println();
        mdWriter.println("---");
        mdWriter.println();
    }

    public void println(String msg) {
        System.out.println(msg);
    }

    public void println() {
        System.out.println();
    }

    public void print(String msg) {
        System.out.print(msg);
    }

    public void printf(String format, Object... args) {
        System.out.printf(format, args);
    }

    public void header(String title) {
        String bar = "=".repeat(100);
        println();
        println(bar);
        println("  " + title);
        println(bar);
        println();

        if (mdWriter != null) {
            mdWriter.println("## " + title);
            mdWriter.println();
        }
    }

    public void subHeader(String title) {
        String bar = "-".repeat(80);
        println();
        println("--- " + title + " ---");
        println(bar);

        if (mdWriter != null) {
            mdWriter.println("### " + title);
            mdWriter.println();
        }
    }

    public void recordMarkdownSection(String text) {
        if (mdWriter != null) {
            mdWriter.println(text);
            mdWriter.println();
        }
    }

    public void recordMarkdownTable(String title, List<String> headers, List<List<String>> rows) {
        if (mdWriter == null) return;
        if (title != null && !title.isEmpty()) {
            mdWriter.println("#### " + title);
            mdWriter.println();
        }
        // Header row
        mdWriter.print("|");
        for (String h : headers) {
            mdWriter.print(" " + h + " |");
        }
        mdWriter.println();

        // Separator row
        mdWriter.print("|");
        for (int i = 0; i < headers.size(); i++) {
            mdWriter.print(" :--- |");
        }
        mdWriter.println();

        // Data rows
        for (List<String> row : rows) {
            mdWriter.print("|");
            for (String cell : row) {
                mdWriter.print(" " + cell + " |");
            }
            mdWriter.println();
        }
        mdWriter.println();
    }

    public void recordTestResult(String testClass, String testMethod, boolean passed, long durationMs, String failureDetail) {
        testRecords.add(new TestRecord(testClass, testMethod, passed, durationMs, failureDetail));
    }

    public void finish() {
        endCaptureSection();
        long elapsed = System.currentTimeMillis() - startTimeMillis;
        println();
        println("=".repeat(100));
        printf("  EXECUTION FINISHED (Total Elapsed: %.2f seconds)%n", elapsed / 1000.0);

        if (!testRecords.isEmpty()) {
            int passed = 0;
            int failed = 0;
            for (TestRecord r : testRecords) {
                if (r.passed) passed++; else failed++;
            }
            println(String.format("  Tests Summary: %d passed, %d failed out of %d total", passed, failed, testRecords.size()));

            if (mdWriter != null) {
                mdWriter.println("### Correctness Verification Summary");
                mdWriter.println();
                mdWriter.println(String.format("- **Total Tests:** %d", testRecords.size()));
                mdWriter.println(String.format("- **Passed:** %d", passed));
                mdWriter.println(String.format("- **Failed:** %d", failed));
                mdWriter.println(String.format("- **Parity Status:** %s", failed == 0 ? "✅ **100% PARITY VERIFIED**" : "❌ **FAILURES DETECTED**"));
                mdWriter.println();

                List<String> testHeaders = Arrays.asList("Test Class", "Test Method", "Result", "Duration (ms)");
                List<List<String>> testRows = new ArrayList<>();
                for (TestRecord r : testRecords) {
                    testRows.add(Arrays.asList(
                            r.testClass,
                            r.testMethod,
                            r.passed ? "PASS" : "**FAIL**",
                            String.valueOf(r.durationMs)
                    ));
                }
                recordMarkdownTable("JUnit Verification Results", testHeaders, testRows);
            }
        }

        if (saveToFile) {
            println("  Saved Artifacts:");
            if (txtOutputFile != null) {
                println("    - Console Log:    " + txtOutputFile.getAbsolutePath());
            }
            if (mdOutputFile != null) {
                println("    - Markdown Report: " + mdOutputFile.getAbsolutePath());
            }
        }
        println("=".repeat(100));
        println();

        if (teeOut != null) {
            teeOut.flush();
            System.setOut(originalOut);
            teeOut = null;
        }
        if (txtWriter != null) {
            txtWriter.flush();
            txtWriter.close();
            txtWriter = null;
        }
        if (mdWriter != null) {
            mdWriter.flush();
            mdWriter.close();
            mdWriter = null;
        }
    }

    public File getTxtOutputFile() {
        return txtOutputFile;
    }

    public File getMdOutputFile() {
        return mdOutputFile;
    }
}
