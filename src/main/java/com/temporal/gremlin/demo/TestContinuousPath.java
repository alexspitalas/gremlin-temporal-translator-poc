package com.temporal.gremlin.demo;

import com.temporal.gremlin.util.GraphDataLoader;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import java.util.List;

public class TestContinuousPath {
    public static void main(String[] args) {
        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        try {
            System.out.println("Loading Data...");
            GraphDataLoader loader = new GraphDataLoader(g);
            loader.load_dataset_flight_native("flightlist_20190101_20190131.csv", 5000);

            System.out.println("Testing ContinuousPath...");
            // Strict continuous: e1.end == e2.start
            List<Path> continuous = g.V().outE("flight").inV().continuousPath("flight").limit(5).path().toList();

            if (continuous.isEmpty()) {
                System.out.println("No STRICT continuous paths found. (Expected for flights)");
            } else {
                System.out.println("Found STRICT continuous paths!");
                continuous.forEach(System.out::println);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { graph.close(); } catch (Exception ignored) {}
        }
    }
}
