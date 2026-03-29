package com.temporal.gremlin.demo;

import com.temporal.gremlin.query.TemporalQuerySpec;
import com.temporal.gremlin.query.TemporalTransaction;
import com.temporal.gremlin.translator.*;
import com.temporal.gremlin.executor.GremlinExecutor;
import com.temporal.gremlin.util.TemporalDataHelper;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Interactive demo showcasing the Temporal Gremlin Translator with Execution.
 * Demonstrates 4-phase translation pipeline + actual query execution.
 */
public class TranslatorDemo {

    private static Graph graph;
    private static GraphTraversalSource g;
    private static TemporalGremlinTranslator translator;
    private static GremlinExecutor executor;
    private static TemporalDataHelper dataHelper;
    private static Instant now, end;
    private static Duration oneDay;

    public static void main(String[] args) {
        System.out.println("\n╔════════════════════════════════════════════════════════╗");
        System.out.println("║ Gremlin Temporal Translator - Translation + Execution ║");
        System.out.println("║      Translates Query → Executes → Shows Results      ║");
        System.out.println("╚═══════════════════════════════════════════════════════╝\n");


        now = Instant.now();
        oneDay = ChronoUnit.DAYS.getDuration();
        end = now.plus(30, ChronoUnit.DAYS);

        try {
            initializeGraph();

            // Demo 1: Basic temporal relationship
            System.out.println("\n┌─ Demo 1: BEFORE Relationship ─────────────────────────┐");
            demoBasicTemporal();

            // Demo 2: With lifetime constraints
            System.out.println("\n┌─ Demo 2: OVERLAPS with Lifetime Constraints ──────────┐");
            demoWithLifetime();

            // Demo 3: Complex Allen operators
            System.out.println("\n┌─ Demo 3: DURING Operator ────────────────────────────┐");
            demoComplexOperator();

            // Demo 4: Optimization level comparison
            System.out.println("\n┌─ Demo 4: Optimization Level Comparison ───────────────┐");
            demoOptimizationLevels();

            // Demo 5: Simple query execution
            System.out.println("\n┌─ Demo 5: Find All Events ────────────────────────────┐");
            demoSimpleQueryExecution();

            // Demo 6: Transaction with property lifetime
            System.out.println("\n┌─ Demo 6: Transaction with Property Lifetime ──────────┐");
            demoTransactionWithPropertyLifetime();

            // Demo 7: Reference Vertex Query
            System.out.println("\n┌─ Demo 7: Reference Vertex Query ──────────────────────┐");
            demoReferenceVertex();

            // Demo 8: Reference Vertex Query
            System.out.println("\n┌─ Demo 8: Reference Vertex Specific Type Query ──────────┐");
            demoReferenceVertexSpecificType();

            System.out.println("\nDemo completed successfully!\n");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();

        } finally {
            cleanup();
        }
    }

    /**
     * Initialize test graph with sample events
     */
    private static void initializeGraph() {
        graph = TinkerGraph.open();
        g = graph.traversal();

        translator = new TemporalGremlinTranslator(g);
        executor = new GremlinExecutor(g);
        dataHelper = new TemporalDataHelper(translator, executor);

        // 1. Conferences (BEFORE Meeting)
        // Conf 1: 10 days ago -> 5 days ago
        dataHelper.addVertex("Conference", "name", "JavaOne", now.minus(oneDay.multipliedBy(10)),
                now.minus(oneDay.multipliedBy(5)));

        // Conf 2: 12 days ago -> 8 days ago
        dataHelper.addVertex("Conference", "name", "QCon", now.minus(oneDay.multipliedBy(12)), now.minus(oneDay.multipliedBy(8)));

        // Conf 3: 15 days ago -> 10 days ago
        dataHelper.addVertex("Conference", "name", "GDC", now.minus(oneDay.multipliedBy(15)), now.minus(oneDay.multipliedBy(10)));

        // 2. Meetings (Target for BEFORE, OVERLAPS with Event, DURING Workshop)
        // Meeting 1: 3 days ago -> Now
        dataHelper.addVertex("Meeting", "name", "Planning", "status", "active", now.minus(oneDay.multipliedBy(3)), now);

        // Meeting 2: 2 days ago -> 1 day ago
        dataHelper.addVertex("Meeting", "name", "Review", "status", "active", now.minus(oneDay.multipliedBy(2)),
                now.minus(oneDay.multipliedBy(1)));

        // 3. Workshops (DURING: Meeting is during Workshop)
        // Workshop 1: Tomorrow -> +7 days (No overlap with current meetings)
        dataHelper.addVertex("Workshop", "name", "Gremlin 101", now.plus(oneDay), now.plus(oneDay.multipliedBy(7)));

        // Workshop 2: 5 days ago -> +1 day (Encompasses Planning Meeting)
        dataHelper.addVertex("Workshop", "name", "Advanced Temporal", now.minus(oneDay.multipliedBy(5)), now.plus(oneDay));

        // 4. Events (OVERLAPS with Meeting)
        // Event 1: 4 days ago -> 1 day ago (Overlaps with Planning)
        dataHelper.addVertex("Event", "name", "Hackathon", now.minus(oneDay.multipliedBy(4)), now.minus(oneDay.multipliedBy(1)));

        // Event 2: 3 days ago -> 2 days ago (Contained in Planning)
        dataHelper.addVertex("Event", "name", "Networking", now.minus(oneDay.multipliedBy(3)), now.minus(oneDay.multipliedBy(2)));

        // 5. Pairs for MEETS (A meets B: A.end == B.start)
        // Pair 1
        dataHelper.addVertex("A", "name", "Task A1", now.minus(oneDay.multipliedBy(2)), now.minus(oneDay.multipliedBy(1)));
        dataHelper.addVertex("B", "name", "Task B1", now.minus(oneDay.multipliedBy(1)), now);

        // Pair 2
        dataHelper.addVertex("A", "name", "Task A2", now.minus(oneDay.multipliedBy(5)), now.minus(oneDay.multipliedBy(4)));
        dataHelper.addVertex("B", "name", "Task B2", now.minus(oneDay.multipliedBy(4)), now.minus(oneDay.multipliedBy(3)));

        System.out.println("Graph initialized with temporal data:");
        System.out.println("  - Conferences: JavaOne, QCon");
        System.out.println("  - Meetings: Planning (active), Review (active)");
        System.out.println("  - Workshops: Gremlin 101, Advanced Temporal (encompasses Planning)");
        System.out.println("  - Events: Hackathon (overlaps Planning), Networking");
        System.out.println("  - Semantic Pairs: 2x (A meets B) relationships");
    }

    /**
     * Demo 1: Basic temporal Before relationship
     */
    private static void demoBasicTemporal() {
        TemporalQuerySpec spec = new TemporalQuerySpec(
                "Conference", "Meeting",
                TemporalQuerySpec.AllenOperator.temporalBefore);

        TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);

        System.out.println("Query: " + spec);
        System.out.println("\nTranslation Metrics:");
        System.out.println(String.format("  Translation Time: %.3f ms", result.metrics.getTotalTime()));
        System.out.println(String.format("  Steps: %d", result.optimizedSteps.size()));
        System.out.println(String.format("  Bytecode Size: %d bytes", result.metrics.getBytecodeSize()));

        System.out.println("\nGenerated Bytecode:");
        System.out.println("  " + result.bytecode);

        // Execute the bytecode
        System.out.println("\nExecution:");
        GremlinExecutor.ExecutionResult execResult = executor.executeWithMetrics(result.bytecode);
        execResult.printReport();
    }

    /**
     * Demo 2: With lifetime constraints
     */
    private static void demoWithLifetime() {
        Instant lifetimeStart = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant lifetimeEnd = Instant.now().plus(30, ChronoUnit.DAYS);

        TemporalQuerySpec spec = new TemporalQuerySpec(
                "Event", "Meeting",
                TemporalQuerySpec.AllenOperator.temporalOverlaps).withLifetime(lifetimeStart, lifetimeEnd);

        TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);

        System.out.println("Query: Find events that OVERLAP with Meeting within ±30 days");
        System.out.println("\nTranslation Metrics:");
        System.out.println(String.format("  Translation Time: %.3f ms", result.metrics.getTotalTime()));

        System.out.println("\nPhase Breakdown:");
        System.out.println(String.format("  - Parsing: %.3f ms", result.metrics.getParsingTime()));
        System.out.println(String.format("  - Transform: %.3f ms", result.metrics.getTransformTime()));
        System.out.println(String.format("  - Optimization: %.3f ms", result.metrics.getOptimizationTime()));
        System.out.println(String.format("  - Codegen: %.3f ms", result.metrics.getCodegenTime()));

        System.out.println("\nGenerated Steps:");
        for (int i = 0; i < result.optimizedSteps.size(); i++) {
            Object step = result.optimizedSteps.get(i);
            System.out.println(String.format("  %d. %s", i + 1, step.toString()));
        }

        System.out.println("\nGenerated Bytecode:");
        System.out.println("  " + result.bytecode);

        System.out.println("\nExecution:");
        GremlinExecutor.ExecutionResult execResult = executor.executeWithMetrics(result.bytecode);
        execResult.printReport();
    }

    /**
     * Demo 3: Complex Allen operators
     */
    private static void demoComplexOperator() {
        TemporalQuerySpec spec = new TemporalQuerySpec(
                "Meeting", "Workshop",
                TemporalQuerySpec.AllenOperator.temporalDuring)
                .withConstraints("it.get().get('v1').value('status') == 'active'");

        TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);

        System.out.println("Query: Find Meeting events that occur DURING Workshop");
        System.out.println("\nGenerated Steps:");
        for (int i = 0; i < result.optimizedSteps.size(); i++) {
            Object step = result.optimizedSteps.get(i);
            System.out.println(String.format("  %d. %s", i + 1, step.toString()));
        }

        System.out.println("\nGenerated Bytecode:");
        System.out.println("  " + result.bytecode);

        System.out.println("\nExecution:");
        GremlinExecutor.ExecutionResult execResult = executor.executeWithMetrics(result.bytecode);
        execResult.printReport();
    }

    /**
     * Demo 4: Optimization level comparison
     */
    private static void demoOptimizationLevels() {
        TemporalQuerySpec spec = new TemporalQuerySpec(
                "A", "B",
                TemporalQuerySpec.AllenOperator.temporalMeets);

        System.out.println("Query: Find events with MEETS relationship\n");
        System.out.println("Optimization Level Comparison:");
        System.out.println("┌──────────────────┬──────────────┬──────────────┬──────────────┐");
        System.out.println("│ Level            │ Time (ms)    │ Steps        │ Size (bytes) │");
        System.out.println("├──────────────────┼──────────────┼──────────────┼──────────────┤");

        StepOptimizer.OptimizationLevel[] levels = {
                StepOptimizer.OptimizationLevel.NONE,
                StepOptimizer.OptimizationLevel.BASIC,
                StepOptimizer.OptimizationLevel.ADVANCED
        };

        for (StepOptimizer.OptimizationLevel level : levels) {
            translator.setOptimizationLevel(level);
            TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);

            System.out.printf("│ %-16s │ %12.3f │ %12d │ %12d │\n",
                    level.toString(),
                    result.metrics.getTotalTime(),
                    result.optimizedSteps.size(),
                    result.metrics.getBytecodeSize());

            // Execute each version
            GremlinExecutor.ExecutionResult execResult = executor.executeWithMetrics(result.bytecode);
            if (!execResult.success && execResult.errorMessage != null) {
                System.out.println("  (Execution: " + execResult.errorMessage + ")");
            }
        }

        System.out.println("└──────────────────┴──────────────┴──────────────┴──────────────┘");
    }

    /**
     * Demo 5: Simple query to find all events
     */
    private static void demoSimpleQueryExecution() {
        System.out.println("Direct Execution: Find all 'Event' vertices");

        String simpleQuery = "g.V().hasLabel('Event')";
        System.out.println("\nQuery: " + simpleQuery);

        System.out.println("\nExecution:");
        GremlinExecutor.ExecutionResult result = executor.executeWithMetrics(simpleQuery);
        result.printReport();
    }

    /**
     * Demo 6: Transaction with property lifetime
     */
    private static void demoTransactionWithPropertyLifetime() {

        TemporalTransaction tx = new TemporalTransaction();

        // Add a vertex with vertex lifetime AND property lifetime
        tx.addVertex("Project")
                .property("name", "Project X")
                .property("status", "Active", now, end)
                .lifetime(now, end);

        TemporalGremlinTranslator.TranslationResult result = translator.translate(tx);

        System.out.println("Transaction: Add Project with Status (Active for 30 days)");
        System.out.println("\nGenerated Bytecode:");
        System.out.println("  " + result.bytecode);

        System.out.println("\nExecution:");
        GremlinExecutor.ExecutionResult execResult = executor.executeWithMetrics(result.bytecode);
        execResult.printReport();
    }


    /**
     * Demo 7: Reference Vertex Query
     */
    private static void demoReferenceVertex() {
        Object planningId = g.V().has("name", "Planning").id().next();

        System.out.println("Using Reference Vertex: Planning Meeting (ID=" + planningId + ")");

        // Find any Type Objects that OVERLAP with this specific Meeting
        TemporalQuerySpec spec = new TemporalQuerySpec(
                null, null,
                TemporalQuerySpec.AllenOperator.temporalOverlaps)
                .withReferenceVertex(planningId);

        TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);

        System.out.println("Query: Find Any Type Objects that OVERLAP with specific Planning Meeting");
        System.out.println("\nGenerated Bytecode:");
        System.out.println("  " + result.bytecode);

        System.out.println("\nExecution:");
        GremlinExecutor.ExecutionResult execResult = executor.executeWithMetrics(result.bytecode);
        execResult.printReport();
    }

    /**
     * Demo 8: Reference Vertex Query
     */
    private static void demoReferenceVertexSpecificType() {
        Object planningId = g.V().has("name", "Planning").id().next();

        System.out.println("Using Reference Vertex: Planning Meeting (ID=" + planningId + ")");

        // Find Meetings that OVERLAP with this specific Meeting
        TemporalQuerySpec spec = new TemporalQuerySpec(
                "Meeting", null,
                TemporalQuerySpec.AllenOperator.temporalOverlaps)
                .withReferenceVertex(planningId);

        TemporalGremlinTranslator.TranslationResult result = translator.translate(spec);

        System.out.println("Query: Find Meetings that OVERLAP with specific Planning Meeting");
        System.out.println("\nGenerated Bytecode:");
        System.out.println("  " + result.bytecode);

        System.out.println("\nExecution:");
        GremlinExecutor.ExecutionResult execResult = executor.executeWithMetrics(result.bytecode);
        execResult.printReport();
    }

    /**
     * Clean up resources
     */
    private static void cleanup() {
        if (g != null) {
            try {
                g.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (graph != null) {
            try {
                graph.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}