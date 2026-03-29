package com.temporal.gremlin.demo;

import com.temporal.gremlin.query.TemporalQuerySpec;
import com.temporal.gremlin.translator.TemporalGremlinTranslator;
import com.temporal.gremlin.executor.GremlinExecutor;
import com.temporal.gremlin.util.GraphDataLoader;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Correctness Demo: Compares Graph A (Native) vs Graph B (Translator).
 */
public class CorrectnessDemo {

    private static final String CSV_PATH = "flightlist_20190101_20190131.csv";
    private static final int LIMIT = 10000;
    private static final int SAMPLE_SIZE = 500;

    public static void main(String[] args) {
        System.out.println("=== STARTING CORRECTNESS DEMO (Native A vs Translator B) ===");

        // 1. Setup Graph A (Native)
        Graph graphA = TinkerGraph.open();
        GraphTraversalSource gA = graphA.traversal();
        GraphDataLoader loaderA = new GraphDataLoader(gA);
        loaderA.load_dataset_flight_native(CSV_PATH, LIMIT);

        // 2. Setup Graph B (Translator)
        Graph graphB = TinkerGraph.open();
        GraphTraversalSource gB = graphB.traversal();
        GraphDataLoader loaderB = new GraphDataLoader(gB);
        loaderB.load_dataset_flight_translator(CSV_PATH, LIMIT);

        // 3. Compare Graph States
        compareGraphs(gA, gB);

        // 4. Compare Query Results
        compareQueries(gA, gB);

        try {
            graphA.close();
            graphB.close();
        } catch (Exception e) {}
    }

    private static void compareGraphs(GraphTraversalSource gA, GraphTraversalSource gB) {
        System.out.println("\n--- Step 1: Comparing Graph States ---");
        
        long vA = gA.V().count().next();
        long vB = gB.V().count().next();
        long eA = gA.E().count().next();
        long eB = gB.E().count().next();

        System.out.println("Graph A (Native): " + vA + " vertices, " + eA + " edges");
        System.out.println("Graph B (Trans) : " + vB + " vertices, " + eB + " edges");

        if (vA == vB && eA == eB) {
            System.out.println("Graph topologies are identical.");
        } else {
            System.err.println("Mismatch in topology!");
        }

        // Check a sample flight
        String callsign = "HVN19";
        System.out.println("\nVerifying properties for flight " + callsign + ":");
        
        if (!gA.E().has("flight", "callsign", callsign).hasNext()) {
            System.err.println("Flight " + callsign + " not found in Graph A!");
            return;
        }
        if (!gB.E().has("flight", "callsign", callsign).hasNext()) {
            System.err.println("Flight " + callsign + " not found in Graph B!");
            return;
        }

        Map<Object, Object> propsA = gA.E().has("flight", "callsign", callsign).elementMap().next();
        Map<Object, Object> propsB = gB.E().has("flight", "callsign", callsign).elementMap().next();
        
        System.out.println("Graph A props: " + propsA);
        System.out.println("Graph B props: " + propsB);

        System.out.println("Graph A keys: " + propsA.keySet());
        System.out.println("Graph B keys: " + propsB.keySet());

        Object startA = propsA.get("startTime");
        Object startB = propsB.get("startTime");

        System.out.println("Storage type (A): " + (startA != null ? startA.getClass().getSimpleName() : "null"));
        System.out.println("Storage type (B): " + (startB != null ? startB.getClass().getSimpleName() : "null"));

        if (startA instanceof String && startB instanceof String && startA.equals(startB)) {
            System.out.println("Temporal properties match and are stored as Strings.");
        } else {
            System.err.println("Property mismatch!");
        }
    }

    private static void compareQueries(GraphTraversalSource gA, GraphTraversalSource gB) {
        System.out.println("\n--- Step 2: Comparing Query Results ---");

        TemporalGremlinTranslator translator = new TemporalGremlinTranslator(gB);
        GremlinExecutor executor = new GremlinExecutor(gB);

        TemporalQuerySpec.AllenOperator[] opsToTest = TemporalQuerySpec.AllenOperator.values();
        // Sample candidates from across the dataset to find varied lifetimes
        List<Edge> candidates = gA.E().hasLabel("flight").range(0, LIMIT/2).toList();
        // Shuffle to get a good spread
        Collections.shuffle(candidates, new Random(42));
        candidates = candidates.subList(0, SAMPLE_SIZE);

        for (TemporalQuerySpec.AllenOperator op : opsToTest) {
            // Find good enough reference edge for this operator to maximize results
            Edge refEdgeA = null;
            long targetCount = 0;
            
            for (Edge e : candidates) {
                long c = getNativeCount(gA, op, e);
                if (c > targetCount) {
                    targetCount = c;
                    refEdgeA = e;
                    if (targetCount >= 50) break; 
                }
            }

            if (refEdgeA == null) {
                refEdgeA = candidates.get(0);
            }

            String callsign = (String) refEdgeA.value("callsign");
            Edge refEdgeB = gB.E().has("flight", "callsign", callsign).next();
            Object refIdB = refEdgeB.id();

            System.out.println("\nOperator: " + op + " (Reference: " + callsign + ")");

            // 1. Native
            List<String> callsA = getNativeResults(gA, op, refEdgeA);

            // 2. Translated
            TemporalQuerySpec spec = new TemporalQuerySpec("flight", null, op)
                    .withElementType(TemporalQuerySpec.ElementType.EDGE)
                    .withReferenceVertex(refIdB);
            
            TemporalGremlinTranslator.TranslationResult translationResult = translator.translate(spec);
            List<String> callsB = executor.execute(translationResult.bytecode).stream()
                    .map(obj -> {
                        if (obj instanceof Map) {
                            Edge e = (Edge) ((Map<?, ?>) obj).get("v1");
                            return (String) e.value("callsign");
                        } else if (obj instanceof Edge) {
                            return (String) ((Edge) obj).value("callsign");
                        }
                        return obj.toString();
                    })
                    .sorted()
                    .collect(Collectors.toList());

            System.out.println("  Native results    : " + callsA.size());
            System.out.println("  Translated results: " + callsB.size());

            if (callsA.equals(callsB)) {
                System.out.println("  Results match!");
            } else {
                System.err.println("  Results mismatch!");
            }
        }
    }

    private static long getNativeCount(GraphTraversalSource g, TemporalQuerySpec.AllenOperator op, Edge ref) {
        try {
            switch(op) {
                case temporalBefore: return g.E().hasLabel("flight").temporalBefore(ref).count().next();
                case temporalAfter: return g.E().hasLabel("flight").temporalAfter(ref).count().next();
                case temporalOverlaps: return g.E().hasLabel("flight").temporalOverlaps(ref).count().next();
                case temporalDuring: return g.E().hasLabel("flight").temporalDuring(ref).count().next();
                case temporalStarts: return g.E().hasLabel("flight").temporalStarts(ref).count().next();
                case temporalEnds: return g.E().hasLabel("flight").temporalFinishes(ref).count().next();
                case temporalMeets: return g.E().hasLabel("flight").temporalMeets(ref).count().next();
                case temporalStartedBy: return g.E().hasLabel("flight").temporalStartedBy(ref).count().next();
                case temporalEndedBy: return g.E().hasLabel("flight").temporalFinishedBy(ref).count().next();
                case temporalContains: return g.E().hasLabel("flight").temporalContains(ref).count().next();
                case temporalOverlappedBy: return g.E().hasLabel("flight").temporalOverlappedBy(ref).count().next();
                case temporalMetBy: return g.E().hasLabel("flight").temporalMetBy(ref).count().next();
                case temporalEqual: return g.E().hasLabel("flight").temporalEquals(ref).count().next();
            }
        } catch (Exception e) {}
        return 0;
    }

    private static List<String> getNativeResults(GraphTraversalSource g, TemporalQuerySpec.AllenOperator op, Edge ref) {
        try {
            switch(op) {
                case temporalBefore: return g.E().hasLabel("flight").temporalBefore(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalAfter: return g.E().hasLabel("flight").temporalAfter(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalOverlaps: return g.E().hasLabel("flight").temporalOverlaps(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalDuring: return g.E().hasLabel("flight").temporalDuring(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalStarts: return g.E().hasLabel("flight").temporalStarts(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalEnds: return g.E().hasLabel("flight").temporalFinishes(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalMeets: return g.E().hasLabel("flight").temporalMeets(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalStartedBy: return g.E().hasLabel("flight").temporalStartedBy(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalEndedBy: return g.E().hasLabel("flight").temporalFinishedBy(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalContains: return g.E().hasLabel("flight").temporalContains(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalOverlappedBy: return g.E().hasLabel("flight").temporalOverlappedBy(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalMetBy: return g.E().hasLabel("flight").temporalMetBy(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
                case temporalEqual: return g.E().hasLabel("flight").temporalEquals(ref).values("callsign").toList().stream().map(Object::toString).sorted().collect(Collectors.toList());
            }
        } catch (Exception e) {}
        return new ArrayList<>();
    }
}
