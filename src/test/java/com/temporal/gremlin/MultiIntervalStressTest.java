package com.temporal.gremlin;

import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit test validating multi-interval evaluation with disjoint sub-intervals (k in {1, 2, 5, 10})
 * under both ANY and ALL regimes.
 */
public class MultiIntervalStressTest {

    private Graph graph;
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
    public void testMultiIntervalOverlapsAnyAndAll() {
        Vertex v1 = graph.addVertex(T.label, "item", "name", "v1");
        Vertex v2 = graph.addVertex(T.label, "item", "name", "v2");
        Vertex v3 = graph.addVertex(T.label, "item", "name", "v3");

        // Target edge e1 with 3 disjoint intervals: [100, 200), [400, 500), [700, 800)
        List<Lifetime.Interval> intervals1 = new ArrayList<>();
        intervals1.add(new Lifetime.Interval(new Date(100), new Date(200)));
        intervals1.add(new Lifetime.Interval(new Date(400), new Date(500)));
        intervals1.add(new Lifetime.Interval(new Date(700), new Date(800)));
        Lifetime lt1 = Lifetime.from(intervals1);
        Edge e1 = g.addE("event").from(v1).to(v2).property("name", "e1").next();
        lt1.attachTo(e1);

        // Reference edge eRef overlapping with the second interval only: [450, 600)
        List<Lifetime.Interval> intervalsRef = new ArrayList<>();
        intervalsRef.add(new Lifetime.Interval(new Date(450), new Date(600)));
        Lifetime ltRef = Lifetime.from(intervalsRef);
        Edge eRef = g.addE("event").from(v2).to(v3).property("name", "ref").next();
        ltRef.attachTo(eRef);

        // Under ANY regime: e1 overlaps eRef because [400, 500) overlaps [450, 600)
        long countAny = g.E(e1).temporalOverlaps(eRef).with(Temporal.ANY).count().next();
        assertEquals("e1 must overlap eRef under ANY regime", 1L, countAny);

        // Under ALL regime: e1 does NOT satisfy holistic overlap because [100, 200) precedes eRef
        long countAll = g.E(e1).temporalOverlaps(eRef).with(Temporal.ALL).count().next();
        assertEquals("e1 must not satisfy holistic overlap under ALL regime", 0L, countAll);
    }

    @Test
    public void testScalingAcrossSubIntervalCounts() {
        int[] kValues = {1, 2, 5, 10};
        Vertex u = graph.addVertex(T.label, "node", "id", 0);
        Vertex v = graph.addVertex(T.label, "node", "id", 1);

        for (int k : kValues) {
            List<Lifetime.Interval> intervals = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                intervals.add(new Lifetime.Interval(new Date(i * 1000L), new Date(i * 1000L + 500L)));
            }
            Lifetime lt = Lifetime.from(intervals);
            Edge e = g.addE("testEdge").from(u).to(v).property("k", k).next();
            lt.attachTo(e);

            assertEquals("Lifetime must have exact sub-interval count k", k, lt.getIntervals().size());
        }

        long totalEdges = g.E().hasLabel("testEdge").count().next();
        assertEquals(4L, totalEdges);
    }
}
