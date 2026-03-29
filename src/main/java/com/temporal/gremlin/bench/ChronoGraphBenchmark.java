package com.temporal.gremlin.bench;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

// ChronoGraph and Blueprints imports
import com.tinkerpop.blueprints.*;
import org.dfpl.chronograph.crud.memory.ChronoGraph;
import org.dfpl.chronograph.crud.memory.ChronoEdge;
import org.dfpl.chronograph.crud.memory.ChronoVertex;
import org.dfpl.chronograph.crud.memory.ChronoEdgeEvent;
import org.dfpl.chronograph.common.TemporalRelation;
import com.tinkerpop.blueprints.TimePeriod;
import org.dfpl.chronograph.algorithm.temporal.search.bfs.TemporalBreadthFirstSearch;      

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Scalability Benchmark: ChronoGraph vs T-TinkerPop Native.
 * 
 * Objectives:
 * 1. Compare Temporal BFS performance across dataset sizes (1k -> 50k).
 *    - Standard: T-TinkerPop (Earliest Arrival) vs ChronoGraph.
 *    - Strict Parity: T-TinkerPop (Earliest Departure) vs ChronoGraph.
 * 2. Demonstrate scalability of the Native T-TinkerPop implementation.
 */
public class ChronoGraphBenchmark {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 10;
    private static final String CSV_PATH = "flightlist_20190101_20190131.csv";
    
    // Scalability Sizes
    private static final int[] SIZES = {1000, 5000, 10000, 25000}; 
    // private static final int[] SIZES = {1000, 5000, 10000, 25000, 50000}; 
    private static final String[] LABELS = {"1k", "5k", "10k", "25k"};

    private Graph tGraph;
    private GraphTraversalSource g;
    private ChronoGraph chronoGraph;
    
    // Cached source
    private Vertex tSource;
    private ChronoVertex cSource;

    public static void main(String[] args) {
        ChronoGraphBenchmark bench = new ChronoGraphBenchmark();
        bench.run();
    }

    public void run() {
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║       CHRONOGRAPH VS TINKERPOP SCALABILITY BENCHMARK       ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        for (int i = 0; i < SIZES.length; i++) {
            runForSize(SIZES[i], LABELS[i]);
        }
    }

    private void runForSize(int size, String label) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  DATASET SCALE: " + label + " Flights");
        System.out.println("=".repeat(60));

        try {
            initializeGraphs(size);
            
            System.out.println("Warming up...");
            warmup();
            
            runBFSBenchmark(size);
            
            runBFSBenchmark(size);
            
            // Operator Benchmarks (restored per request)
            runOperatorBenchmarks();
            
        } catch (Exception e) {
            System.err.println("Benchmark error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void initializeGraphs(int limit) throws Exception {
        // Initialize graphs
        tGraph = TinkerGraph.open();
        g = tGraph.traversal();
        chronoGraph = new ChronoGraph();

        Map<String, Vertex> tAirports = new HashMap<>();
        Map<String, ChronoVertex> cAirports = new HashMap<>();
        
        // Cache used for merging logic simulation
        Map<String, List<EdgeInfo>> tEdgeCache = new HashMap<>();
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(CSV_PATH))) {
            String line = br.readLine();
            while ((line = br.readLine()) != null && count < limit) {
                String[] parts = line.split(",");
                if (parts.length < 9) continue;

                String origin = parts[5];
                String destination = parts[6];
                if (origin.isEmpty() || destination.isEmpty()) continue;

                long start, end;
                try {
                    start = OffsetDateTime.parse(parts[7], formatter).toInstant().toEpochMilli();
                    end = OffsetDateTime.parse(parts[8], formatter).toInstant().toEpochMilli();
                } catch (Exception e) { continue; }

                // Vertices
                if (!tAirports.containsKey(origin)) {
                    Vertex v = g.addV("Airport").property("code", origin).next();
                    tAirports.put(origin, v);
                    cAirports.put(origin, (ChronoVertex) chronoGraph.addVertex(origin));
                    cAirports.get(origin).setProperty("code", origin);
                }
                if (!tAirports.containsKey(destination)) {
                    Vertex v = g.addV("Airport").property("code", destination).next();
                    tAirports.put(destination, v);
                    cAirports.put(destination, (ChronoVertex) chronoGraph.addVertex(destination));
                    cAirports.get(destination).setProperty("code", destination);
                }

                // ChronoGraph: Try to add event
                com.tinkerpop.blueprints.Edge ce = chronoGraph.addEdge(cAirports.get(origin), cAirports.get(destination), "flight");
                TimePeriod period = new TimePeriod(start, end);
                ChronoEdgeEvent addedEvent = ((ChronoEdge) ce).addEvent(period);
                
                // Only add to TinkerPop if ChronoGraph accepted the event
                if (addedEvent != null) {
                    // Apply same deduplication/merging logic
                    String route = origin + "->" + destination;
                    List<EdgeInfo> cachedEdges = tEdgeCache.getOrDefault(route, new ArrayList<>());
                    
                    boolean shouldAddToTinkerPop = true;
                    List<EdgeInfo> edgesToRemove = new ArrayList<>();
                    long finalStart = start;
                    long finalEnd = end;
                    
                    for (EdgeInfo edgeInfo : cachedEdges) {
                        long exStart = edgeInfo.start;
                        long exEnd = edgeInfo.end;
                        TimePeriod exPeriod = new TimePeriod(exStart, exEnd);
                        
                        if (period.checkTemporalRelation(exPeriod, TemporalRelation.cotemporal) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.during) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.starts) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.finishes)) {
                            shouldAddToTinkerPop = false;
                            break;
                        }
                        
                        if (period.checkTemporalRelation(exPeriod, TemporalRelation.contains) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.isStartedBy) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.isFinishedBy) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.isOverlappedBy) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.overlapsWith) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.meets) ||
                            period.checkTemporalRelation(exPeriod, TemporalRelation.isMetBy)) {
                            
                            finalStart = Math.min(finalStart, exStart);
                            finalEnd = Math.max(finalEnd, exEnd);
                            edgesToRemove.add(edgeInfo);
                        }
                    }
                    
                    if (shouldAddToTinkerPop) {
                        for (EdgeInfo info : edgesToRemove) {
                            info.edge.remove();
                            cachedEdges.remove(info);
                        }
                        
                        Edge newEdge = g.addE("flight").from(tAirports.get(origin)).to(tAirports.get(destination))
                            .property("startTime", finalStart).property("endTime", finalEnd).next();
                        
                        EdgeInfo newInfo = new EdgeInfo(newEdge, finalStart, finalEnd);
                        cachedEdges.add(newInfo);
                        tEdgeCache.put(route, cachedEdges);
                    }
                }
                count++;
            }
        }
        System.out.println("  Loaded " + count + " flights.");
        
        // Pick source
        if (g.V().hasLabel("Airport").hasNext()) {
             tSource = g.V().hasLabel("Airport").order().by(__.outE("flight").count(), org.apache.tinkerpop.gremlin.process.traversal.Order.desc).limit(1).next();
             cSource = (ChronoVertex) chronoGraph.getVertex(tSource.property("code").value().toString());
        }
    }
    
    private void warmup() {
        if (tSource == null) return;
        long startTime = 0L;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            new TemporalBreadthFirstSearch().compute(cSource, new TimeInstant(startTime), "flight");
            
            com.temporal.gremlin.algo.TemporalBFS algo = new com.temporal.gremlin.algo.TemporalBFS();
            algo.computeEarliestArrival(g, tSource.id(), startTime);
        }
    }

    private void runBFSBenchmark(int size) {
        System.out.println("\n--- Temporal BFS Performance (Depth 3) ---");
        System.out.println("┌────────────────────┬──────────────┬──────────────┬──────────────┐");
        System.out.println("│ Implementation     │   Time (ms)  │ Reachability │ Speedup Fac. │");
        System.out.println("├────────────────────┼──────────────┼──────────────┼──────────────┤");

        if (tSource == null) {
            System.out.println("No source vertex found.");
            return;
        }

        // 1. Benchmark ChronoGraph
        double chronoTime = benchmarkChronoGraphBFS();
        
        // 2. Benchmark Native (Earliest Arrival) - Standard
        double nativeArrivalTime = benchmarkNativeBFS_Arrival();
        
        // 3. Benchmark Native (Earliest Departure) - Parity
        double nativeDepartureTime = benchmarkNativeBFS_Departure();
        
        // Get counts (just one run to verifying)
        com.temporal.gremlin.algo.TemporalBFS algo = new com.temporal.gremlin.algo.TemporalBFS();
        int arrivalCount = algo.computeEarliestArrival(g, tSource.id(), 0L).size();
        int departureCount = algo.computeEarliestDeparture(g, tSource.id(), 0L).size();
        // Chrono count
        TemporalBreadthFirstSearch tbfs = new TemporalBreadthFirstSearch();
        tbfs.compute(cSource, new TimeInstant(0L), "flight");
        int chronoCount = getChronoCount(tbfs);

        // Report
        System.out.printf("│ %-18s │ %12.3f │ %12d │      -       │\n", 
            "ChronoGraph", chronoTime, chronoCount);
        
        double speedupArr = chronoTime / nativeArrivalTime;
        System.out.printf("│ %-18s │ %12.3f │ %12d │ %11.2fx │\n", 
            "TPop (Arrival)", nativeArrivalTime, arrivalCount, speedupArr);

        double speedupDep = chronoTime / nativeDepartureTime;
        System.out.printf("│ %-18s │ %12.3f │ %12d │ %11.2fx │\n", 
            "TPop (Departure)", nativeDepartureTime, departureCount, speedupDep);
            
        System.out.println("└────────────────────┴──────────────┴──────────────┴──────────────┘");
    }

    private double benchmarkChronoGraphBFS() {
        long total = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            new TemporalBreadthFirstSearch().compute(cSource, new TimeInstant(0L), "flight");
            total += (System.nanoTime() - start);
        }
        return total / (1_000_000.0 * BENCHMARK_ITERATIONS);
    }
    
    private double benchmarkNativeBFS_Arrival() {
        com.temporal.gremlin.algo.TemporalBFS algo = new com.temporal.gremlin.algo.TemporalBFS();
        long total = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            algo.computeEarliestArrival(g, tSource.id(), 0L);
            total += (System.nanoTime() - start);
        }
        return total / (1_000_000.0 * BENCHMARK_ITERATIONS);
    }

    private double benchmarkNativeBFS_Departure() {
        com.temporal.gremlin.algo.TemporalBFS algo = new com.temporal.gremlin.algo.TemporalBFS();
        long total = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            algo.computeEarliestDeparture(g, tSource.id(), 0L);
            total += (System.nanoTime() - start);
        }
        return total / (1_000_000.0 * BENCHMARK_ITERATIONS);
    }
    private void runOperatorBenchmarks() {
        System.out.println("\n--- Temporal Operator Performance (1000 iterations) ---");
        System.out.println("┌────────────────────┬──────────────┬──────────────┬──────────────┐");
        System.out.println("│ Operator           │ ChronoGraph  │ Native T-Pop │ Speedup Fac. │");
        System.out.println("├────────────────────┼──────────────┼──────────────┼──────────────┤");

        if (tSource == null) return;
        
        // Use the first outgoing flight from source as reference
        if (!g.E().hasLabel("flight").hasNext()) return;
        Edge refEdge = g.V(tSource.id()).outE("flight").next();
        
        benchmarkOperator("temporalAfter", refEdge);
        benchmarkOperator("temporalOverlaps", refEdge);
        
        System.out.println("└────────────────────┴──────────────┴──────────────┴──────────────┘");
    }

    private void benchmarkOperator(String operator, Edge refEdge) {
        long startRef = (long) refEdge.property("startTime").value();
        long endRef = (long) refEdge.property("endTime").value();
        TimePeriod refPeriod = new TimePeriod(startRef, endRef);
        
        // 1. ChronoGraph Benchmark (Event Scanning)
        long chronoTotal = 0;
        int chronoCount = 0;
        
        
        // Use a wide time range to retrieve all events (assuming all are within 0 to Long.MAX_VALUE)
        TimePeriod allTime = new TimePeriod(0L, Long.MAX_VALUE);

        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            int count = 0;
            // ChronoGraph doesn't have a direct index, we iterate all edges and check events
            for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
                ChronoEdge ce = (ChronoEdge) e;
                // Use getEvents with 'during' relation to fetch all events within the infinite range
                for (com.tinkerpop.blueprints.Event event : ce.getEvents(allTime, TemporalRelation.during)) {
                    TimePeriod tp = (TimePeriod) event.getTime();
                    boolean match = false;
                    if (operator.equals("temporalAfter")) {
                         match = tp.checkTemporalRelation(refPeriod, TemporalRelation.isAfter);
                    } else if (operator.equals("temporalOverlaps")) {
                         match = tp.checkTemporalRelation(refPeriod, TemporalRelation.overlapsWith);
                    }
                    if (match) count++;
                }
            }
            chronoTotal += (System.nanoTime() - start);
            chronoCount = count;
        }
        double chronoAvg = chronoTotal / 1_000_000.0 / 1000;
        
        // 2. Native Benchmark
        long nativeTotal = 0;
        int nativeCount = 0;
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            if (operator.equals("temporalAfter")) {
                 nativeCount = g.E().hasLabel("flight").temporalAfter(refEdge).count().next().intValue();
            } else if (operator.equals("temporalOverlaps")) {
                 nativeCount = g.E().hasLabel("flight").temporalOverlaps(refEdge).count().next().intValue();
            }
            nativeTotal += (System.nanoTime() - start);
            nativeCount = nativeCount; // Update count
        }
        double nativeAvg = nativeTotal / 1_000_000.0 / 1000;
        
        double speedup = (nativeAvg > 0) ? chronoAvg / nativeAvg : 0.0;
        
        System.out.printf("│ %-18s │ %9.3f ms │ %9.3f ms │ %11.2fx │\n", 
            operator, chronoAvg, nativeAvg, speedup);
    }

    private int getChronoCount(TemporalBreadthFirstSearch tbfs) {
        try {
            java.lang.reflect.Field f = TemporalBreadthFirstSearch.class.getDeclaredField("distances");
            f.setAccessible(true);
            Map<?, ?> distances = (Map<?, ?>) f.get(tbfs);
            return distances.size();
        } catch (Exception e) { return -1; }
    }

    private void cleanup() {
        try { if(g!=null) g.close(); if(tGraph!=null) tGraph.close(); } catch(Exception e) {}
    }
    
    // Helper class
    private static class EdgeInfo {
        Edge edge;
        long start;
        long end;
        EdgeInfo(Edge edge, long start, long end) {
            this.edge = edge;
            this.start = start;
            this.end = end;
        }
    }
}
