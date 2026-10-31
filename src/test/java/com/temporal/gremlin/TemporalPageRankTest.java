package com.temporal.gremlin;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit test validating Temporal PageRank execution and normalization.
 */
public class TemporalPageRankTest {

    private TinkerGraph graph;
    private GraphTraversalSource g;

    @Before
    public void setUp() {
        graph = TinkerGraph.open();
        g = graph.traversal();
    }

    @After
    public void tearDown() {
        try { g.close(); } catch (Exception ignored) {}
        try { graph.close(); } catch (Exception ignored) {}
    }

    @Test
    public void testTemporalPageRankExecutionAndRankConservation() {
        Vertex a = graph.addVertex(T.label, "Airport", "code", "A");
        Vertex b = graph.addVertex(T.label, "Airport", "code", "B");
        Vertex c = graph.addVertex(T.label, "Airport", "code", "C");

        // Chronological chain: A -> B at t=[100, 200), then B -> C at t=[250, 350)
        Edge e1 = g.addE("flight").from(a).to(b).lifetime(100L, 200L).next();
        e1.property("startTime", 100L);
        e1.property("endTime", 200L);
        Lifetime.from(100L, 200L).attachTo(e1);

        Edge e2 = g.addE("flight").from(b).to(c).lifetime(250L, 350L).next();
        e2.property("startTime", 250L);
        e2.property("endTime", 350L);
        Lifetime.from(250L, 350L).attachTo(e2);

        // Run temporalPageRank
        List<Vertex> vertices = g.V().temporalPageRank()
                .with(TemporalPageRank.propertyName, "rank")
                .with(TemporalPageRank.alpha, 0.85d)
                .with(TemporalPageRank.beta, 0.5d)
                .with(TemporalPageRank.normalize, true)
                .toList();

        assertEquals(3, vertices.size());

        double totalRank = 0.0;
        for (Vertex v : vertices) {
            assertTrue("Vertex must have assigned rank property", v.property("rank").isPresent());
            double rank = ((Number) v.property("rank").value()).doubleValue();
            assertTrue("Rank must be positive", rank > 0.0);
            totalRank += rank;
        }

        // When normalize is true, total rank sums to 1.0
        assertEquals("Normalized temporal PageRank must sum to 1.0", 1.0, totalRank, 0.001);
    }
}
