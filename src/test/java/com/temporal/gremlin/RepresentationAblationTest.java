package com.temporal.gremlin;

import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit test verifying semantic equivalence between dynamic string parsing
 * and normalized epochMillis representation.
 */
public class RepresentationAblationTest {

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
    public void testDynamicParseMatchesNormalizedEpochMillis() {
        Vertex v1 = graph.addVertex(T.label, "entity", "id", 1);
        Vertex v2 = graph.addVertex(T.label, "entity", "id", 2);

        String startStr = "2024-01-10T08:00:00Z";
        String endStr   = "2024-01-10T18:00:00Z";

        long startEpoch = Instant.parse(startStr).toEpochMilli();
        long endEpoch   = Instant.parse(endStr).toEpochMilli();

        // Add edge with both normalized lifetime and raw string properties
        Edge e = g.addE("event").from(v1).to(v2)
                .property("startTimeStr", startStr)
                .property("endTimeStr", endStr)
                .lifetime(startEpoch, endEpoch)
                .next();

        // 1. Dynamic parsing result
        Date parsedStart = DatetimeHelper.parse((String) e.property("startTimeStr").value());
        Date parsedEnd   = DatetimeHelper.parse((String) e.property("endTimeStr").value());

        // 2. Normalized epoch millis
        Object sVal = e.property("startTime").value();
        Object eVal = e.property("endTime").value();
        long normStart = (sVal instanceof Date) ? ((Date) sVal).getTime() : ((Number) sVal).longValue();
        long normEnd   = (eVal instanceof Date) ? ((Date) eVal).getTime() : ((Number) eVal).longValue();

        assertEquals("Parsed start time must match normalized epoch milliseconds",
                startEpoch, parsedStart.getTime());
        assertEquals("Parsed end time must match normalized epoch milliseconds",
                endEpoch, parsedEnd.getTime());
        assertEquals(startEpoch, normStart);
        assertEquals(endEpoch, normEnd);

        // Verification of temporalBefore
        Vertex vRefOut = graph.addVertex(T.label, "entity", "id", 3);
        Vertex vRefIn  = graph.addVertex(T.label, "entity", "id", 4);
        Edge refEdge = g.addE("event").from(vRefOut).to(vRefIn)
                .lifetime(endEpoch + 1000L, endEpoch + 5000L)
                .next();

        long beforeCount = g.E(e).temporalBefore(refEdge).count().next();
        assertEquals(1L, beforeCount);
    }

    @Test
    public void testDynamicParseMatchesNormalizedEpochMillisWithMultiInterval() {
        Vertex v1 = graph.addVertex(T.label, "entity", "id", 10);
        Vertex v2 = graph.addVertex(T.label, "entity", "id", 20);

        long s1 = 1000L, e1 = 2000L;
        long s2 = 4000L, e2 = 5000L;

        String intervalStr = "1000/2000,4000/5000";

        // Add edge with multi-interval lifetime
        Lifetime lt = Lifetime.from(s1, e1).addInterval(s2, e2);
        Edge edge = g.addE("event").from(v1).to(v2)
                .property("intervalsStr", intervalStr)
                .next();
        lt.attachTo(edge);

        assertEquals(2, Lifetime.fromProperties(edge).getIntervals().size());

        // Verify temporalOverlaps with reference [1500, 2500] under ANY quantifier
        Edge ref = g.addE("ref").from(v1).to(v2).lifetime(1500L, 2500L).next();
        long count = g.E(edge).temporalOverlaps(ref).with(Temporal.ANY).count().next();
        assertEquals(1L, count);
    }
}
