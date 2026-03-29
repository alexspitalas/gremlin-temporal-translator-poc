package com.temporal.gremlin.algo;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.*;

/**
 * Implementation of Temporal Breadth-First Search (BFS) algorithms.
 * Supports multiple traversal strategies.
 */
public class TemporalBFS {

    /**
     * Computes reachable vertices using "Earliest Arrival" strategy.
     * This optimizes for maximizing the window for subsequent connections (Standard BFS).
     * 
     * @param g GraphTraversalSource
     * @param sourceId ID of the source vertex
     * @param initialTime Starting time
     * @return Map of reachable Vertex ID -> Earliest Arrival Time
     */
    public Map<Object, Long> computeEarliestArrival(GraphTraversalSource g, Object sourceId, long initialTime) {
        // map<VertexID, EarliestArrivalTime>
        Map<Object, Long> gamma = new HashMap<>(); 
        Queue<Vertex> queue = new LinkedList<>();
        
        Vertex source = g.V(sourceId).next();
        gamma.put(sourceId, initialTime);
        queue.add(source);
        
        while (!queue.isEmpty()) {
            Vertex u = queue.poll();
            long currentTime = gamma.get(u.id());
            
            Iterator<Edge> edges = u.edges(Direction.OUT, "flight");
            while (edges.hasNext()) {
                Edge e = edges.next();
                long eStart = ((Number) e.property("startTime").value()).longValue();
                long eEnd = ((Number) e.property("endTime").value()).longValue();
                
                if (eStart >= currentTime) {
                    Vertex v = e.inVertex();
                    if (!gamma.containsKey(v.id()) || eEnd < gamma.get(v.id())) {
                        gamma.put(v.id(), eEnd);
                        queue.add(v);
                    }
                }
            }
        }
        return gamma;
    }

    /**
     * Computes reachable vertices using "Earliest Departure" strategy.
     * This mimics ChronoGraph's behavior, verifying edges based on start time greedy choice.
     * 
     * @param g GraphTraversalSource
     * @param sourceId ID of the source vertex
     * @param initialTime Starting time
     * @return Map of reachable Vertex ID -> {StartTime, EndTime} of the arrival edge
     */
    public Map<Object, long[]> computeEarliestDeparture(GraphTraversalSource g, Object sourceId, long initialTime) {
        // gamma: VertexID -> long[]{start, end}
        Map<Object, long[]> gamma = new HashMap<>(); 
        Queue<Vertex> queue = new LinkedList<>();
        
        Vertex source = g.V(sourceId).next();
        // Initial state
        gamma.put(sourceId, new long[]{initialTime, initialTime});
        queue.add(source);
        
        while (!queue.isEmpty()) {
            Vertex u = queue.poll();
            long[] bestU = gamma.get(u.id());
            long arrivalAtU = bestU[1]; // We can depart after we arrived
            
            Iterator<Edge> edges = u.edges(Direction.OUT, "flight");
            while (edges.hasNext()) {
                Edge e = edges.next();
                long eStart = ((Number) e.property("startTime").value()).longValue();
                long eEnd = ((Number) e.property("endTime").value()).longValue();
                
                // Validity check
                if (eStart >= arrivalAtU) {
                    Vertex v = e.inVertex();
                    
                    // Relaxation: Minimize (Start, End) - ChronoGraph Logic
                    boolean improve = false;
                    long[] bestV = gamma.get(v.id());
                    
                    if (bestV == null) {
                        improve = true;
                    } else {
                        // Priority 1: Earlier Start Time
                        if (eStart < bestV[0]) {
                            improve = true;
                        } 
                        // Priority 2: Same Start Time, Earlier End Time
                        else if (eStart == bestV[0] && eEnd < bestV[1]) {
                            improve = true;
                        }
                    }
                    
                    if (improve) {
                        gamma.put(v.id(), new long[]{eStart, eEnd});
                        queue.add(v);
                    }
                }
            }
        }
        return gamma;
    }
}
