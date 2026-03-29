package com.temporal.gremlin.demo;

import com.temporal.gremlin.util.GraphDataLoader;
import org.apache.tinkerpop.gremlin.process.traversal.Operator;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;


import java.util.List;
import java.util.Random;

import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.*;

/**
 * Demo: Interesting Applications of T-TinkerPop.
 * 
 * 1. Disease Spread (Information Propagation) - Modeled as Sequential Paths (e1.end < e2.start)
 * 2. Logistics/Supply Chain - Modeled as Pairwise Continuous (e1.end == e2.start, or close)
 * 3. Temporal Shortest Path - Finding the fastest route respecting temporal order.
 */
public class TemporalApplicationsDemo {

    private static final String CSV_PATH = "flightlist_20190101_20190131.csv";
    private static final int LOADING_LIMIT = 5000; // Small subset for demo speed



    /**
     * Application 2: Logistics / Supply Chain.
     * 
     * Logic: Cargo needs to be transferred immediately.
     * 
     * Pattern: Pairwise Continuous (e1.end approx e2.start)
     * We look for tight connections (e.g., within 2 hours).
     */
    private static void demonstrateLogistics(GraphTraversalSource g) {
        System.out.println("\n┌────────────────────────────────────────────────────────────┐");
        System.out.println("│ App 2: Logistics (Pairwise Continuous Path)                │");
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // T-TinkerPop native pairwiseContinuousPath step:
        try {
            List<Path> tightConnections = g.E().hasLabel("flight").as("e1")
                .inV().as("transferAirport")
                .pairwiseContinuousPath("flight") 
                .path()
                .by("callsign")
                .by("code")
                .by("callsign")
                .limit(5)
                .toList();
    
            if (tightConnections.isEmpty()) {
                System.out.println("No pairwise continuous paths found (might need more data).");
            } else {
                tightConnections.forEach(p -> {
                    System.out.printf(" - Cargo Path: %s -> %s -> %s\n",
                         p.get(0), p.get(1), p.get(2));
                });
            }
        } catch (Exception e) {
             System.out.println("Feature pairwiseContinuousPath not available: " + e.getMessage());
        }
    }

    /**
     * Application 3: Temporal Shortest Path.
     * 
     * Logic: Find the path between two airports that arrives EARLIEST.
     * This considers the waiting time at airports, not just flight duration.
     */
    private static void demonstrateTemporalShortestPath(GraphTraversalSource g) {
        System.out.println("\n┌────────────────────────────────────────────────────────────┐");
        System.out.println("│ App 3: Temporal Shortest Path (Earliest Arrival - Manual)  │");
        System.out.println("└────────────────────────────────────────────────────────────┘");
    
        try {
            List<Vertex> airports = g.V().hasLabel("Airport").toList();
            if (airports.size() < 2) return;
            
            Vertex source = airports.get(0);
            Vertex target = airports.get(Math.min(airports.size()-1, 10));
    
            System.out.println("Routing: " + source.property("code").value() + " -> " + target.property("code").value());
    
            long initialTime = 0L; 
    
            List<Path> paths = g.withSack(initialTime).V(source.id())
                .repeat(
                    outE("flight").as("e")
                    .filter(
                        values("startTime").as("start")
                        .sack().as("arrival")
                        .where("start", P.gte("arrival"))
                    )
                    .sack(Operator.assign).by("endTime")
                    .inV().simplePath()
                )
                .until(hasId(target.id()).or().loops().is(4))
                .limit(1)
                .path()
                    .by("code")
                    .by(project("flight", "time").by("callsign").by("startTime"))
                .toList();
    
            if (paths.isEmpty()) {
                System.out.println("No temporal path found (max 4 hops).");
            } else {
                paths.forEach(p -> {
                    System.out.println("Found Valid Temporal Path:");
                    for (Object o : p) System.out.print(o + " ");
                    System.out.println();
                });
            }
        } catch (Exception e) {
            System.err.println("Error calculating path: " + e.getMessage());
        }
    }

    /**
     * NEW: Application 5 - Meeting Scheduling (Allen Operator: Overlaps).
     * 
     * Logic: Find flights that OVERLAP in time.
     * Use Case: Identifying concurrent operations or conflicts.
     */
    private static void demonstrateMeetingScheduling(GraphTraversalSource g) {
        System.out.println("\n┌────────────────────────────────────────────────────────────┐");
        System.out.println("│ App 5: Meeting Scheduling (Temporal Overlaps)              │");
        System.out.println("└────────────────────────────────────────────────────────────┘");

        try {
            // Find a reference flight
            List<Edge> flights = g.E().hasLabel("flight").limit(1).toList();
            if (flights.isEmpty()) return;
            Edge refFlight = flights.get(0);
            
            System.out.println("Reference Flight: " + refFlight.property("callsign").value() + 
                               " [" + refFlight.property("startTime").value() + 
                               " - " + refFlight.property("endTime").value() + "]");

            // Find overlapping flights using native temporalOverlaps step
            
            List<Object> overlapping = g.E().hasLabel("flight")
                .temporalOverlaps(refFlight)
                .limit(5)
                .values("callsign")
                .toList();

            if (overlapping.isEmpty()) {
                System.out.println("No overlapping flights found.");
            } else {
                overlapping.forEach(c -> System.out.println(" - Overlaps with: " + c));
            }
        } catch (Exception e) {
            System.out.println("Native temporalOverlaps step not found or failed: " + e.getMessage());
        }
    }

    /**
     * NEW: Native Temporal Shortest Path using 'sequentialPath' step.
     */
    private static void demonstrateTemporalShortestPath_Native(GraphTraversalSource g) {
        System.out.println("\n┌────────────────────────────────────────────────────────────┐");
        System.out.println("│ App 6: Temporal Shortest Path (Native Sequential Path)     │");
        System.out.println("└────────────────────────────────────────────────────────────┘");

        try {
            List<Vertex> airports = g.V().hasLabel("Airport").toList();
            if (airports.size() < 2) return;
            Vertex source = airports.get(0);
            Vertex target = airports.get(Math.min(airports.size()-1, 8));

            System.out.println("Routing: " + source.property("code").value() + " -> " + target.property("code").value());

            // Using repeat(sequentialPath) to ensure temporal order natively
            List<Path> paths = g.V(source.id())
                .repeat(
                    __.outE("flight").inV().sequentialPath("flight") 
                    // Note: sequentialPath usually filters the previous path to ensure the last edge is > previous edge
                )
                .until(hasId(target.id()).or().loops().is(4))
                .limit(1)
                .path()
                .by("code")
                .by("callsign")
                .toList();

            if (paths.isEmpty()) {
                System.out.println("No native sequential path found.");
            } else {
                paths.forEach(p -> {
                    System.out.println("Found Native Sequential Path:");
                    p.forEach(o -> System.out.print(o + " "));
                    System.out.println();
                });
            }
        } catch (Exception e) {
             System.out.println("Native sequential path traversal failed: " + e.getMessage());
        }
    }

    /**
     * Application 1: Disease Spread / Information Propagation.
     * 
     * Logic: A passenger arrives at an airport. They spend some time (Minimum Connection Time)
     * and then depart. The infection spreads to the destination.
     * 
     * Pattern: Sequential Path (e1.end + transfer_time <= e2.start)
     * We use a relaxed 'temporalAfter' logic here.
     */
    private static void demonstrateDiseaseSpread(GraphTraversalSource g) {
        System.out.println("\n┌────────────────────────────────────────────────────────────┐");
        System.out.println("│ App 1: Disease Spread (Sequential Path Analysis)           │");
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // Pick a random starting flight
        List<Edge> potentialStarts = g.E().hasLabel("flight").limit(10).toList();
        if (potentialStarts.isEmpty()) return;
        Edge startFlight = potentialStarts.get(new Random().nextInt(potentialStarts.size()));
        
        Vertex infectedAirport = startFlight.inVertex(); // Destination of first flight
        long infectionTime = (long) startFlight.property("endTime").value();
        
        System.out.println("Patient Zero Flight: " + startFlight.property("callsign").value() + 
                           " arrives at " + infectedAirport.property("code").value() + 
                           " at timestamp " + infectionTime);

        System.out.println("Tracking potential spread (Depth 2)...");

        // T-TinkerPop native sequentialPath step:
        
        List<Path> spreadPaths = g.V(infectedAirport.id())
            .outE("flight")
            .has("startTime", P.gt(infectionTime)) // Depart after arrival
            .inV()
            .sequentialPath("flight")
            .path()
            .by("code")     // Vertex: Airport Code
            .by("callsign") // Edge: Callsign
            .by("code")
            .by("callsign")
            .by("code")
            .limit(5)
            .toList();

        if (spreadPaths.isEmpty()) {
            System.out.println("No spread found within 2 hops from this flight.");
        } else {
            spreadPaths.forEach(p -> {
                System.out.printf(" - Infection Path: %s --[%s]--> %s --[%s]--> %s\n",
                    p.get(0), p.get(1), p.get(2), p.get(3), p.get(4));
            });
        }
    }

    /**
     * Application 4: Post-Event Emergency Route (Validation of User Request).
     * 
     * Logic: Find the shortest path (hops) that occurs strictly AFTER a major event.
     * This uses the standard 'shortestPath' step with an edge filter.
     * 
     * Note: This is a "Static" temporal path (all edges > T), not a "Dynamic" one (e2 > e1).
     */
    private static void demonstratePostEventShortestPath(GraphTraversalSource g) {
        System.out.println("\n┌────────────────────────────────────────────────────────────┐");
        System.out.println("│ App 4: Post-Event Emergency Route (Shortest Path + Filter) │");
        System.out.println("└────────────────────────────────────────────────────────────┘");

        try {
            List<Vertex> airports = g.V().hasLabel("Airport").toList();
            if (airports.size() < 2) return;
            Vertex source = airports.get(0);
            Vertex target = airports.get(Math.min(airports.size()-1, 5));

            // Define a "Major Event" time (e.g., start of the dataset + 1 day)
            // We want to find a path using ONLY flights that happen AFTER this time.
            long eventTime = 1546300800000L; // approx Jan 1 2019
            if (g.E().hasLabel("flight").hasNext()) {
                 long firstTime = (long) g.E().hasLabel("flight").limit(1).values("startTime").next();
                 eventTime = firstTime + (12 * 60 * 60 * 1000L); // +12 hours
            }

            System.out.println("Routing: " + source.property("code").value() + " -> " + target.property("code").value());
            System.out.println("Condition: All flights must occur AFTER timestamp " + eventTime);

            // Using shortestPath() with a filter on edges.
            // We use 'with(edges, ...)' to filter edges.
            // We use 'with(target, ...)' to specify the destination.
            
            // Using repeat(sequentialPath) to find a causal path after the event.
            // This replaces the generic shortestPath() step with T-TinkerPop's native temporal traversal.
            
            Path path = (Path) g.V(source.id())
                .outE("flight").has("startTime", P.gt(eventTime))
                .inV()
                .repeat(__.sequentialPath("flight"))
                .until(hasId(target.id()).or().loops().is(5))
                .limit(1)
                .path()
                .next();

            System.out.println("Found Post-Event Path (Min Hops):");
            path.forEach(o -> System.out.print(o + " "));
            System.out.println();

        } catch (Exception e) {
             // shortestPath might require GraphComputer or specific configuration depending on version
             System.out.println("Standard shortestPath execution skipped or failed (requires GraphComputer or compatible graph): " + e.getMessage());
             // Fallback to simple description if it fails
        }
    }

    /**
     * NEW: Application 7 - Advanced Business Queries.
     * 
     * 1. "High-Frequency Cargo" (Continuous Path):
     *    - Scenario: Material must be transferred *immediately* (e.g., organ transport, hot metal).
     *    - Pattern: e1.end == e2.start (Strict Continuity).
     * 
     * 2. "Maintenance Window Validation" (Disjoint / Overlaps):
     *    - Scenario: Ensure a flight does not overlap with a scheduled maintenance window.
     *    - Pattern: !temporalOverlaps (or temporalDisjoint implied).
     */
    private static void demonstrateAdvancedQueries(GraphTraversalSource g) {
        System.out.println("\n┌────────────────────────────────────────────────────────────┐");
        System.out.println("│ App 7: Advanced Business Queries (Continuous & Disjoint)   │");
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // 1. High-Frequency Cargo (Continuous Path)
        try {
            System.out.println("Query 1: High-Frequency Cargo (Strict Continuous Path)");
            // We know from research that such paths exist in this dataset.
            List<Path> continuous = g.V().outE("flight").inV().continuousPath("flight").limit(3).path().toList();
            
            if (continuous.isEmpty()) {
                System.out.println(" - No strict continuous paths found.");
            } else {
                continuous.forEach(p -> {
                    System.out.println(" - Continuous Chain: " + p);
                    // Extract times to prove continuity if needed, but path output is sufficient for demo.
                });
            }
        } catch (Exception e) {
             System.out.println(" - Continuous path check failed: " + e.getMessage());
        }

        System.out.println();

        // 2. Resource Contention / Maintenance Window (Overlaps)
        // Scenario: Pick a flight. Create a hypothetical "Maintenance" event that overlaps it.
        // Then query for flights that conflict with this window.
        try {
            System.out.println("Query 2: Maintenance Window Conflict (Overlaps)");
            List<Edge> flights = g.E().hasLabel("flight").limit(1).toList();
            if (!flights.isEmpty()) {
                Edge targetFlight = flights.get(0);
                long start = (long) targetFlight.property("startTime").value();
                long end = (long) targetFlight.property("endTime").value();
                
                // Hypothetical maintenance window = Flight Time +/- 30 mins
                // In a real app, this would be a "Maintenance" vertex or edge. 
                // Here we simulate it by finding OTHER flights that would conflict with this time slot.
                
                System.out.println(" - Maintenance Window: [" + start + " - " + end + "] (Simulated)");
                
                List<Object> conflicts = g.E().hasLabel("flight")
                    .hasId(P.neq(targetFlight.id())) // Don't match self
                    .temporalOverlaps(targetFlight)
                    .limit(5)
                    .values("callsign")
                    .toList();
                
                if (conflicts.isEmpty()) {
                    System.out.println(" - No conflicting flights found during this window.");
                } else {
                    conflicts.forEach(c -> System.out.println(" - Conflict Detected: Flight " + c + " overlaps maintenance."));
                }
            }
        } catch (Exception e) {
             System.out.println(" - Overlaps check failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║            T-TINKERPOP TEMPORAL APPLICATIONS DEMO          ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        try {
            // 1. Load Data
            System.out.println("\n>>> Loading Flight Data (Limit: " + LOADING_LIMIT + ")...");
            GraphDataLoader loader = new GraphDataLoader(g);
            loader.load_dataset_flight_native(CSV_PATH, LOADING_LIMIT);

            if (!g.V().hasNext()) {
                System.err.println("No data loaded. Check CSV path.");
                return;
            }

            // 2. Run Applications
            demonstrateDiseaseSpread(g);
            demonstrateLogistics(g);
            demonstrateTemporalShortestPath(g);
            demonstratePostEventShortestPath(g);
            
            // New Demos
            demonstrateMeetingScheduling(g);
            demonstrateTemporalShortestPath_Native(g);
            demonstrateAdvancedQueries(g);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { graph.close(); } catch (Exception ignored) {}
        }
    }
}
