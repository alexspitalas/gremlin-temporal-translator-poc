package com.temporal.gremlin.bench;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AllenFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;
import java.util.function.LongSupplier;

/**
 * Benchmarks temporal reads over values written through lifetime().
 *
 * This benchmark intentionally uses synthetic data so the result isolates lifetime access and comparison costs rather
 * than CSV parsing or translator overhead. Run the same benchmark on the old and new implementations to compare the
 * old string-backed lifetime storage against the new Date-backed lifetime storage through the intended API.
 *
 * The primary Allen measurement uses AllenFilterStep directly because that was the parse-heavy filter implementation:
 * it reads startTime/endTime from each traversed element, parses or normalizes those values, then evaluates an Allen
 * relation. The public temporalBefore()/temporalAfter() DSL methods currently route through AllenStep, which decomposes
 * into has()+P predicates. P.lt/P.gt compare strings lexicographically and do not parse strings, so canonical ISO-like
 * strings make that path look artificially good. Date-backed lifetime storage should help AllenFilterStep-style
 * parse-on-read paths more than the AllenStep has()+P baseline.
 */
public class TemporalLifetimeStorageBenchmark {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 30;
    private static final int MUTATION_ITERATIONS = 1000;
    private static final int[] SIZES = {1000, 10000, 100000};

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final long REFERENCE_START = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli();
    private static final long REFERENCE_END = Instant.parse("2024-01-25T12:00:00Z").toEpochMilli();
    private static final DateTimeFormatter OLD_COMPATIBLE_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String[] OLD_LIFETIME_DATE_FORMATS = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
    };

    private static volatile Object sink;

    private Graph graph;
    private GraphTraversalSource g;
    private Edge referenceEdge;

    public static void main(String[] args) {
        new TemporalLifetimeStorageBenchmark().run();
    }

    public void run() {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  TEMPORAL LIFETIME STORAGE BENCHMARK");
        System.out.println("============================================================");
        System.out.println("Creates temporal edges only through lifetime().");
        System.out.println();
        System.out.println("How to read the tables:");
        System.out.println("  MAIN     AllenFilterStep parse-on-read path. This is the key read benchmark.");
        System.out.println("  BASELINE AllenStep public DSL has()+P path. P.lt/P.gt do not parse strings.");
        System.out.println("  OTHER    temporalPageRank, another parse-on-read path.");
        System.out.println("  WRITE    lifetime() write-time normalization cost.");

        runMixedFormatCorrectnessCheck();

        for (int size : SIZES) {
            runReadBenchmarks(size);
        }

        runMutationBenchmark();
    }

    private void runReadBenchmarks(int edgeCount) {
        System.out.println();
        System.out.println("READ: " + edgeCount + " edges");
        System.out.println("------------------------------------------------------------");

        try {
            initializeGraph(edgeCount);
            warmupReadBenchmarks();

            printReadHeader();
            benchmarkRead("MAIN", "AllenFilterStep", "before", () -> allenFilterStepCount(AllenFilterStep.AllenRelation.BEFORE));
            benchmarkRead("MAIN", "AllenFilterStep", "after", () -> allenFilterStepCount(AllenFilterStep.AllenRelation.AFTER));
            benchmarkRead("MAIN", "AllenFilterStep", "overlaps", () -> allenFilterStepCount(AllenFilterStep.AllenRelation.OVERLAPS));
            benchmarkRead("MAIN", "AllenFilterStep", "during", () -> allenFilterStepCount(AllenFilterStep.AllenRelation.DURING));
            benchmarkRead("MAIN", "AllenFilterStep", "contains", () -> allenFilterStepCount(AllenFilterStep.AllenRelation.CONTAINS));
            benchmarkRead("BASELINE", "AllenStep DSL", "before", () -> g.E().hasLabel("event").temporalBefore(referenceEdge).count().next());
            benchmarkRead("BASELINE", "AllenStep DSL", "overlaps", () -> g.E().hasLabel("event").temporalOverlaps(referenceEdge).count().next());
            benchmarkRead("OTHER", "temporalPageRank", "rank", () -> g.V().temporalPageRank().count().next());
        } finally {
            cleanup();
        }
    }

    private void initializeGraph(int edgeCount) {
        graph = TinkerGraph.open();
        g = graph.traversal();

        Vertex[] vertices = new Vertex[Math.max(16, Math.min(edgeCount / 4, 4096))];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = graph.addVertex(T.label, "eventVertex", "name", "v" + i);
        }

        referenceEdge = addTemporalEdge(vertices[0], vertices[1], REFERENCE_START, REFERENCE_END);

        for (int i = 1; i < edgeCount; i++) {
            Vertex out = vertices[i % vertices.length];
            Vertex in = vertices[(i + 1) % vertices.length];
            long[] interval = intervalFor(i);
            addTemporalEdge(out, in, interval[0], interval[1]);
        }

        long loadedEdges = g.E().hasLabel("event").count().next();
        if (loadedEdges != edgeCount) {
            throw new IllegalStateException("Expected " + edgeCount + " edges but loaded " + loadedEdges);
        }
    }

    private Edge addTemporalEdge(Vertex out, Vertex in, long start, long end) {
        return g.V(out).as("a").V(in).as("b")
                .addE("event").from("a").to("b")
                .lifetime(temporalString(start), temporalString(end))
                .next();
    }

    private String temporalString(long epochMillis) {
        return OLD_COMPATIBLE_DATE_TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    private long[] intervalFor(int edgeIndex) {
        long offset = (edgeIndex / 5) * DAY;
        switch (edgeIndex % 5) {
            case 0:
                return new long[] {REFERENCE_START - (20 * DAY) - offset, REFERENCE_START - (10 * DAY) - offset};
            case 1:
                return new long[] {REFERENCE_END + (10 * DAY) + offset, REFERENCE_END + (20 * DAY) + offset};
            case 2:
                return new long[] {REFERENCE_START - (5 * DAY), REFERENCE_START + (5 * DAY)};
            case 3:
                return new long[] {REFERENCE_START + DAY, REFERENCE_START + (2 * DAY)};
            default:
                return new long[] {REFERENCE_START - (5 * DAY), REFERENCE_END + (5 * DAY)};
        }
    }

    private void warmupReadBenchmarks() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sink = allenFilterStepCount(AllenFilterStep.AllenRelation.BEFORE);
            sink = allenFilterStepCount(AllenFilterStep.AllenRelation.AFTER);
            sink = allenFilterStepCount(AllenFilterStep.AllenRelation.OVERLAPS);
            sink = allenFilterStepCount(AllenFilterStep.AllenRelation.DURING);
            sink = allenFilterStepCount(AllenFilterStep.AllenRelation.CONTAINS);
            sink = g.E().hasLabel("event").temporalBefore(referenceEdge).count().next();
            sink = g.E().hasLabel("event").temporalOverlaps(referenceEdge).count().next();
            sink = g.V().temporalPageRank().count().next();
        }
    }

    private long allenFilterStepCount(AllenFilterStep.AllenRelation relation) {
        GraphTraversal<Edge, Edge> traversal = g.E().hasLabel("event");
        traversal.asAdmin().addStep(new AllenFilterStep<>(traversal.asAdmin(), relation, referenceEdge));
        return traversal.count().next();
    }

    private void printReadHeader() {
        System.out.printf("%-9s %-18s %-10s %12s %12s%n", "Role", "Path", "Operation", "Avg ms", "Count");
        System.out.println("------------------------------------------------------------");
    }

    private void benchmarkRead(String role, String path, String operationName, LongSupplier operation) {
        long resultCount = operation.getAsLong();
        long totalTime = 0L;

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            sink = operation.getAsLong();
            totalTime += System.nanoTime() - start;
        }

        double avgMs = totalTime / (1_000_000.0 * BENCHMARK_ITERATIONS);
        System.out.printf("%-9s %-18s %-10s %12.3f %12d%n", role, path, operationName, avgMs, resultCount);
    }

    private void runMutationBenchmark() {
        System.out.println();
        System.out.println("WRITE: lifetime() normalization");
        System.out.println("------------------------------------------------------------");

        graph = TinkerGraph.open();
        g = graph.traversal();

        Vertex out = graph.addVertex(T.label, "eventVertex", "name", "out");
        Vertex in = graph.addVertex(T.label, "eventVertex", "name", "in");

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sink = g.V(out).as("a").V(in).as("b")
                    .addE("event").from("a").to("b")
                    .lifetime("2024-01-15T12:00:00", "2024-01-25T12:00:00")
                    .next();
        }

        long totalTime = 0L;
        for (int i = 0; i < MUTATION_ITERATIONS; i++) {
            long start = System.nanoTime();
            sink = g.V(out).as("a").V(in).as("b")
                    .addE("event").from("a").to("b")
                    .lifetime("2024-01-15T12:00:00", "2024-01-25T12:00:00")
                    .next();
            totalTime += System.nanoTime() - start;
        }

        double avgMs = totalTime / (1_000_000.0 * MUTATION_ITERATIONS);
        System.out.printf("%-24s %12s%n", "Operation", "Avg ms/op");
        System.out.println("-------------------------------------");
        System.out.printf("%-24s %12.3f%n", "addE().lifetime()", avgMs);

        cleanup();
    }

    private void runMixedFormatCorrectnessCheck() {
        System.out.println();
        System.out.println("CORRECTNESS: mixed date formats");
        System.out.println("------------------------------------------------------------");
        System.out.println("All sample dates are before the reference date.");
        System.out.println("Rows below are the cases where raw string comparison gives the wrong answer.");

        String referenceStart = temporalString(REFERENCE_START);
        String[][] samples = {
                {"yyyy-MM-dd'T'HH:mm:ss", "2024-01-02T00:00:00"},
                {"yyyy-MM-dd HH:mm:ss", "2024-01-02 00:00:00"},
                {"yyyy/MM/dd HH:mm:ss", "2024/01/02 00:00:00"},
                {"dd/MM/yyyy HH:mm:ss", "31/12/2023 00:00:00"},
                {"MM/dd/yyyy HH:mm:ss", "12/31/2023 00:00:00"},
                {"epoch millis string", Long.toString(Instant.parse("2024-01-02T00:00:00Z").toEpochMilli())}
        };

        int unsafeComparisons = 0;
        Date parsedReference = parseOldLifetimeDate(referenceStart);
        for (String[] sample : samples) {
            String value = sample[1];
            boolean rawBefore = value.compareTo(referenceStart) < 0;
            Date parsedValue = parseOldLifetimeDate(value);
            boolean parsedBefore = parsedValue != null && parsedReference != null && parsedValue.before(parsedReference);
            if (rawBefore != parsedBefore) {
                if (unsafeComparisons == 0) {
                    System.out.println("Reference: " + referenceStart);
                    System.out.printf("%-24s %-22s %-10s %-10s%n", "Format", "Value", "Raw", "Parsed");
                    System.out.println("----------------------------------------------------------------");
                }
                unsafeComparisons++;
                System.out.printf("%-24s %-22s %-10s %-10s%n", sample[0], value, rawBefore, parsedBefore);
            }
        }

        if (unsafeComparisons == 0) {
            System.out.println("No unsafe raw string comparisons found in this sample set.");
        } else {
            System.out.println("Unsafe raw string comparisons: " + unsafeComparisons + " of " + samples.length);
        }
    }

    private Date parseOldLifetimeDate(String value) {
        try {
            return new Date(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // Try the old LifetimeStep accepted date formats below.
        }

        for (String format : OLD_LIFETIME_DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                sdf.setLenient(false);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = sdf.parse(value);
                if (value.equals(sdf.format(parsed))) {
                    return parsed;
                }
            } catch (ParseException ignored) {
                // Try next format.
            }
        }

        return null;
    }

    private void cleanup() {
        if (g != null) {
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
        if (graph != null) {
            try {
                graph.close();
            } catch (Exception ignored) {
            }
        }
        g = null;
        graph = null;
        referenceEdge = null;
    }
}
