package com.temporal.gremlin;

import com.tinkerpop.blueprints.TimeInstant;
import com.tinkerpop.blueprints.TimePeriod;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;
import org.apache.tinkerpop.gremlin.process.ranking.pagerank.TemporalPageRankAlgorithm;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.dfpl.chronograph.algorithm.temporal.search.bfs.TemporalBreadthFirstSearch;
import org.dfpl.chronograph.common.TemporalRelation;
import org.dfpl.chronograph.crud.memory.ChronoEdge;
import org.dfpl.chronograph.crud.memory.ChronoGraph;
import org.dfpl.chronograph.crud.memory.ChronoVertex;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Exhaustive correctness and semantic parity test suite comparing T-TinkerPop
 * (Unindexed Traversal, Indexed Traversal via TemporalTinkerIndex, and Direct Primitive API)
 * against ChronoGraph across:
 *   1. All 13 Allen interval relations on synthetic and real-world OpenSky flight datasets
 *   2. Multi-interval lifetimes (k in {1, 2, 5, 10}) under ANY and ALL quantification
 *   3. Vertex-to-Edge step folding (g.V(u).outE().temporalX(ref))
 *   4. Temporal BFS reachability and earliest arrival/departure timestamps
 *   5. Temporal PageRank exact score conservation and parity
 */
public class ChronoGraphParityTest {

    private static final DateTimeFormatter CSV_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private static class OperatorPair {
        final String name;
        final Temporal tpopOp;
        final TemporalRelation cgOp;

        OperatorPair(String name, Temporal tpopOp, TemporalRelation cgOp) {
            this.name = name;
            this.tpopOp = tpopOp;
            this.cgOp = cgOp;
        }
    }

    private static final List<OperatorPair> ALL_13_ALLEN_OPERATORS = Arrays.asList(
            new OperatorPair("before",       Temporal.precedes,     TemporalRelation.isBefore),
            new OperatorPair("after",        Temporal.succeeds,     TemporalRelation.isAfter),
            new OperatorPair("meets",        Temporal.meets,        TemporalRelation.meets),
            new OperatorPair("metBy",        Temporal.metBy,        TemporalRelation.isMetBy),
            new OperatorPair("overlaps",     Temporal.overlaps,     TemporalRelation.overlapsWith),
            new OperatorPair("overlappedBy", Temporal.overlappedBy, TemporalRelation.isOverlappedBy),
            new OperatorPair("starts",       Temporal.starts,       TemporalRelation.starts),
            new OperatorPair("startedBy",    Temporal.startedBy,    TemporalRelation.isStartedBy),
            new OperatorPair("during",       Temporal.during,       TemporalRelation.during),
            new OperatorPair("contains",     Temporal.contains,     TemporalRelation.contains),
            new OperatorPair("finishes",     Temporal.finishes,     TemporalRelation.finishes),
            new OperatorPair("finishedBy",   Temporal.finishedBy,   TemporalRelation.isFinishedBy),
            new OperatorPair("equals",       Temporal.eq,           TemporalRelation.cotemporal)
    );

    @Test
    public void testAll13AllenRelationsParityWithChronoGraphUnindexedAndIndexed() {
        TinkerGraph unindexedGraph = TinkerGraph.open();
        TinkerGraph indexedGraph = TinkerGraph.open();
        indexedGraph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);
        ChronoGraph chronoGraph = new ChronoGraph();

        GraphTraversalSource gUnindexed = unindexedGraph.traversal();
        GraphTraversalSource gIndexed = indexedGraph.traversal();

        // Reference interval: [1000, 2000)
        long refStart = 1000L;
        long refEnd = 2000L;
        TimePeriod refPeriod = new TimePeriod(refStart, refEnd);

        Vertex uRef1 = unindexedGraph.addVertex(T.label, "refNode");
        Vertex vRef1 = unindexedGraph.addVertex(T.label, "refNode");
        Edge refEdgeUnindexed = gUnindexed.addE("refEdge").from(uRef1).to(vRef1).lifetime(refStart, refEnd).next();

        Vertex uRef2 = indexedGraph.addVertex(T.label, "refNode");
        Vertex vRef2 = indexedGraph.addVertex(T.label, "refNode");
        Edge refEdgeIndexed = gIndexed.addE("refEdge").from(uRef2).to(vRef2).lifetime(refStart, refEnd).next();

        // Candidate intervals covering all 13 Allen relations + boundary/off-by-one cases
        long[][] candidateIntervals = {
                {100, 500},    // before
                {800, 999},    // before (1ms gap)
                {500, 1000},   // meets
                {800, 1500},   // overlaps
                {999, 1001},   // overlaps (1ms overlap)
                {1000, 1500},  // starts
                {1200, 1800},  // during
                {1001, 1999},  // during (1ms inside)
                {1500, 2000},  // finishes
                {1000, 2000},  // equals
                {500, 2000},   // finishedBy
                {500, 2500},   // contains
                {999, 2001},   // contains (1ms outside)
                {1000, 2500},  // startedBy
                {1500, 2500},  // overlappedBy
                {1999, 2500},  // overlappedBy (1ms overlap)
                {2000, 2500},  // metBy
                {2001, 2500},  // after (1ms gap)
                {2500, 3000}   // after
        };

        Vertex u1 = unindexedGraph.addVertex(T.label, "entity");
        Vertex u2 = indexedGraph.addVertex(T.label, "entity");
        ChronoVertex cu = (ChronoVertex) chronoGraph.addVertex("u");

        for (int i = 0; i < candidateIntervals.length; i++) {
            long s = candidateIntervals[i][0];
            long e = candidateIntervals[i][1];

            Vertex v1 = unindexedGraph.addVertex(T.label, "entity", "idx", i);
            gUnindexed.addE("event").from(u1).to(v1).property("idx", i).lifetime(s, e).iterate();

            Vertex v2 = indexedGraph.addVertex(T.label, "entity", "idx", i);
            gIndexed.addE("event").from(u2).to(v2).property("idx", i).lifetime(s, e).iterate();

            ChronoVertex cv = (ChronoVertex) chronoGraph.addVertex("v" + i);
            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cu, cv, "event");
            ce.addEvent(new TimePeriod(s, e));
        }

        Lifetime refLt = Lifetime.from(refStart, refEnd);

        for (OperatorPair op : ALL_13_ALLEN_OPERATORS) {
            long cgNatCount = countChronoGraphNatRel(chronoGraph, refPeriod, op.cgOp);
            long cgLoopCount = countChronoGraphLoop(chronoGraph, refPeriod, op.cgOp);
            long directCount = countDirectTinkerPop(unindexedGraph, "event", refLt, op.tpopOp, Temporal.Quantifier.ANY);
            long unindexedTravCount = applyAllenTraversal(gUnindexed.E().hasLabel("event"), op.name, refEdgeUnindexed, Temporal.Quantifier.ANY).count().next();
            long indexedTravCount = applyAllenTraversal(gIndexed.E().hasLabel("event"), op.name, refEdgeIndexed, Temporal.Quantifier.ANY).count().next();
            long vertexStepCount = applyAllenTraversal(gUnindexed.V(u1.id()).outE("event"), op.name, refEdgeUnindexed, Temporal.Quantifier.ANY).count().next();

            assertTrue("Operator " + op.name + " should match at least 1 candidate", directCount >= 1);
            assertEquals("CG NatRel vs CG Loop mismatch on " + op.name, cgNatCount, cgLoopCount);

            // T-TinkerPop execution modes must always agree across all 13 Allen operators
            assertEquals("TPop Direct vs Unindexed Traversal mismatch on " + op.name, directCount, unindexedTravCount);
            assertEquals("TPop Direct vs Indexed Traversal mismatch on " + op.name, directCount, indexedTravCount);
            assertEquals("TPop Direct vs VertexStep Folded Traversal mismatch on " + op.name, directCount, vertexStepCount);

            if (!"overlappedBy".equals(op.name)) {
                // Note: ChronoGraph 0.1 has a bytecode typo in TimePeriod.checkTemporalRelation for isOverlappedBy
                // (checking this.f > other.s instead of this.s > other.s, which falsely includes contains & isStartedBy).
                // For the other 12 operators, ChronoGraph and T-TinkerPop match identically.
                assertEquals("CG vs TPop Direct mismatch on " + op.name, cgNatCount, directCount);
            } else {
                // Verify T-TinkerPop overlappedBy matches ChronoGraph's converse overlapsWith check (refPeriod overlapsWith candidate)
                long cgConverseOverlapsCount = countChronoGraphConverseOverlaps(chronoGraph, refPeriod);
                assertEquals("CG converse overlapsWith vs TPop overlappedBy mismatch", cgConverseOverlapsCount, directCount);
            }
        }

        unindexedGraph.close();
        indexedGraph.close();
    }

    @Test
    public void testMultiIntervalParityAcrossSubIntervalCountsAndAll13Operators() {
        int[] kValues = {1, 2, 5, 10};
        long refStart = 50_000L;
        long refEnd = 70_000L;
        TimePeriod refPeriod = new TimePeriod(refStart, refEnd);
        Lifetime refLt = Lifetime.from(refStart, refEnd);

        for (int k : kValues) {
            TinkerGraph unindexedGraph = TinkerGraph.open();
            TinkerGraph indexedGraph = TinkerGraph.open();
            indexedGraph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);
            ChronoGraph chronoGraph = new ChronoGraph();

            GraphTraversalSource gUnindexed = unindexedGraph.traversal();
            GraphTraversalSource gIndexed = indexedGraph.traversal();

            Vertex refU1 = unindexedGraph.addVertex("ref");
            Vertex refV1 = unindexedGraph.addVertex("ref");
            Edge refE1 = gUnindexed.addE("refEdge").from(refU1).to(refV1).lifetime(refStart, refEnd).next();

            Vertex refU2 = indexedGraph.addVertex("ref");
            Vertex refV2 = indexedGraph.addVertex("ref");
            Edge refE2 = gIndexed.addE("refEdge").from(refU2).to(refV2).lifetime(refStart, refEnd).next();

            int numEdges = 250;
            for (int i = 0; i < numEdges; i++) {
                Vertex u1 = unindexedGraph.addVertex("n");
                Vertex v1 = unindexedGraph.addVertex("n");
                Vertex u2 = indexedGraph.addVertex("n");
                Vertex v2 = indexedGraph.addVertex("n");
                ChronoVertex cu = (ChronoVertex) chronoGraph.addVertex("u" + i);
                ChronoVertex cv = (ChronoVertex) chronoGraph.addVertex("v" + i);

                List<Lifetime.Interval> ivs = new ArrayList<>(k);
                long cur = (i * 1500L) % 90_000L;
                for (int j = 0; j < k; j++) {
                    long span = (i % 7 == 0 && j == 0) ? (refEnd - refStart) : 10_000L;
                    long s = cur;
                    if (i % 13 == 0 && j == 0) s = refStart;
                    long e = s + span;
                    if (i % 17 == 0 && j == 0) {
                        s = refStart - 5_000L;
                        e = refStart; // meets
                    }
                    ivs.add(new Lifetime.Interval(s, e));
                    cur = e + 5_000L; // strictly disjoint so ChronoEdge does not merge across j
                }

                Lifetime lt = Lifetime.from(ivs);
                Edge e1 = u1.addEdge("event", v1);
                lt.attachTo(e1);

                Edge e2 = u2.addEdge("event", v2);
                lt.attachTo(e2);

                ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cu, cv, "event");
                for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                    ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
                }
            }

            for (OperatorPair op : ALL_13_ALLEN_OPERATORS) {
                long cgNat = countChronoGraphNatRel(chronoGraph, refPeriod, op.cgOp);
                long cgLoop = countChronoGraphLoop(chronoGraph, refPeriod, op.cgOp);
                long directAny = countDirectTinkerPop(unindexedGraph, "event", refLt, op.tpopOp, Temporal.Quantifier.ANY);
                long unindexedAny = applyAllenTraversal(gUnindexed.E().hasLabel("event"), op.name, refE1, Temporal.Quantifier.ANY).count().next();
                long indexedAny = applyAllenTraversal(gIndexed.E().hasLabel("event"), op.name, refE2, Temporal.Quantifier.ANY).count().next();

                assertEquals("k=" + k + " op=" + op.name + " CG NatRel vs Loop", cgNat, cgLoop);
                assertEquals("k=" + k + " op=" + op.name + " Direct ANY vs Unindexed ANY", directAny, unindexedAny);
                assertEquals("k=" + k + " op=" + op.name + " Direct ANY vs Indexed ANY", directAny, indexedAny);

                if (!"overlappedBy".equals(op.name)) {
                    assertEquals("k=" + k + " op=" + op.name + " CG vs TPop Direct ANY", cgNat, directAny);
                } else {
                    long cgConverse = countChronoGraphConverseOverlaps(chronoGraph, refPeriod);
                    assertEquals("k=" + k + " op=overlappedBy CG converse overlapsWith vs TPop Direct ANY", cgConverse, directAny);
                }

                // Also verify ALL quantifier parity between Direct, Unindexed, and Indexed
                long directAll = countDirectTinkerPop(unindexedGraph, "event", refLt, op.tpopOp, Temporal.Quantifier.ALL);
                long unindexedAll = applyAllenTraversal(gUnindexed.E().hasLabel("event"), op.name, refE1, Temporal.Quantifier.ALL).count().next();
                long indexedAll = applyAllenTraversal(gIndexed.E().hasLabel("event"), op.name, refE2, Temporal.Quantifier.ALL).count().next();

                assertEquals("k=" + k + " op=" + op.name + " Direct ALL vs Unindexed ALL", directAll, unindexedAll);
                assertEquals("k=" + k + " op=" + op.name + " Direct ALL vs Indexed ALL", directAll, indexedAll);
            }

            unindexedGraph.close();
            indexedGraph.close();
        }
    }

    @Test
    public void testOpenSkyDatasetCorrectnessParityAcrossOperatorsAndPageRank() throws Exception {
        File csv = new File("datasets/flightlist_20190101_20190131.csv");
        if (!csv.exists()) {
            return;
        }

        TinkerGraph unindexedGraph = TinkerGraph.open();
        TinkerGraph indexedGraph = TinkerGraph.open();
        indexedGraph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);
        ChronoGraph chronoGraph = new ChronoGraph();

        Map<String, Vertex> uAirports = new HashMap<>();
        Map<String, Vertex> iAirports = new HashMap<>();
        Map<String, ChronoVertex> cAirports = new HashMap<>();
        Map<String, List<Lifetime.Interval>> routeIntervals = new LinkedHashMap<>();
        Map<String, String> routeOrigins = new HashMap<>();
        Map<String, String> routeDests = new HashMap<>();

        int limit = 5000;
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null && count < limit) {
                String[] parts = line.split(",");
                if (parts.length < 9) continue;
                String origin = parts[5].trim();
                String dest = parts[6].trim();
                if (origin.isEmpty() || dest.isEmpty() || origin.equals(dest)) continue;
                long s, e;
                try {
                    s = OffsetDateTime.parse(parts[7].trim(), CSV_FMT).toInstant().toEpochMilli();
                    e = OffsetDateTime.parse(parts[8].trim(), CSV_FMT).toInstant().toEpochMilli();
                } catch (Exception ex) {
                    continue;
                }
                if (s >= e) continue;

                uAirports.computeIfAbsent(origin, c -> unindexedGraph.addVertex(T.label, "Airport", "code", c));
                uAirports.computeIfAbsent(dest, c -> unindexedGraph.addVertex(T.label, "Airport", "code", c));
                iAirports.computeIfAbsent(origin, c -> indexedGraph.addVertex(T.label, "Airport", "code", c));
                iAirports.computeIfAbsent(dest, c -> indexedGraph.addVertex(T.label, "Airport", "code", c));
                cAirports.computeIfAbsent(origin, c -> (ChronoVertex) chronoGraph.addVertex(c));
                cAirports.computeIfAbsent(dest, c -> (ChronoVertex) chronoGraph.addVertex(c));

                String routeKey = origin + "->" + dest;
                routeIntervals.computeIfAbsent(routeKey, k -> new ArrayList<>()).add(new Lifetime.Interval(s, e));
                routeOrigins.putIfAbsent(routeKey, origin);
                routeDests.putIfAbsent(routeKey, dest);
                count++;
            }
        }

        Map<String, Edge> routeEdgeMap = new HashMap<>();
        long sampleStart = 0, sampleEnd = 0;
        for (Map.Entry<String, List<Lifetime.Interval>> entry : routeIntervals.entrySet()) {
            String key = entry.getKey();
            String orig = routeOrigins.get(key);
            String dst = routeDests.get(key);
            Lifetime lt = Lifetime.from(entry.getValue());

            Edge eU = uAirports.get(orig).addEdge("flight", uAirports.get(dst), "route", key);
            lt.attachTo(eU);
            routeEdgeMap.put(key, eU);

            Edge eI = iAirports.get(orig).addEdge("flight", iAirports.get(dst), "route", key);
            lt.attachTo(eI);

            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cAirports.get(orig), cAirports.get(dst), "flight");
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }

            if (sampleStart == 0 && lt.getIntervalCount() > 0) {
                sampleStart = lt.getStartMillis(0);
                sampleEnd = lt.getEndMillis(0);
            }
        }

        // 1. Verify Allen operators on OpenSky flight graph
        TimePeriod refPeriod = new TimePeriod(sampleStart, sampleEnd);
        Lifetime refLt = Lifetime.from(sampleStart, sampleEnd);
        GraphTraversalSource gU = unindexedGraph.traversal();
        GraphTraversalSource gI = indexedGraph.traversal();

        Vertex refU1 = unindexedGraph.addVertex("ref");
        Vertex refV1 = unindexedGraph.addVertex("ref");
        Edge refE1 = gU.addE("refEdge").from(refU1).to(refV1).lifetime(sampleStart, sampleEnd).next();

        Vertex refU2 = indexedGraph.addVertex("ref");
        Vertex refV2 = indexedGraph.addVertex("ref");
        Edge refE2 = gI.addE("refEdge").from(refU2).to(refV2).lifetime(sampleStart, sampleEnd).next();

        for (OperatorPair op : ALL_13_ALLEN_OPERATORS) {
            long cgNat = countChronoGraphNatRel(chronoGraph, refPeriod, op.cgOp);
            long direct = countDirectTinkerPop(unindexedGraph, "flight", refLt, op.tpopOp, Temporal.Quantifier.ANY);
            long unidx = applyAllenTraversal(gU.E().hasLabel("flight"), op.name, refE1, Temporal.Quantifier.ANY).count().next();
            long idx = applyAllenTraversal(gI.E().hasLabel("flight"), op.name, refE2, Temporal.Quantifier.ANY).count().next();

            assertEquals("OpenSky " + op.name + " Direct vs Unindexed", direct, unidx);
            assertEquals("OpenSky " + op.name + " Direct vs Indexed", direct, idx);

            if (!"overlappedBy".equals(op.name)) {
                assertEquals("OpenSky " + op.name + " CG vs Direct", cgNat, direct);
            } else {
                long cgConverse = countChronoGraphConverseOverlaps(chronoGraph, refPeriod);
                assertEquals("OpenSky overlappedBy CG converse overlapsWith vs Direct", cgConverse, direct);
            }
        }

        // 2. Verify Temporal PageRank exact score parity between T-TinkerPop and ChronoGraph stream
        gU.V().hasLabel("Airport").temporalPageRank()
                .with(TemporalPageRank.propertyName, "tpr")
                .with(TemporalPageRank.alpha, 0.85d)
                .with(TemporalPageRank.beta, 0.5d)
                .with(TemporalPageRank.normalize, true)
                .iterate();

        TimePeriod allTime = new TimePeriod(0L, Long.MAX_VALUE);
        List<TemporalEdge> cgEdges = new ArrayList<>();
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            String outCode = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.OUT).getId();
            String inCode = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.IN).getId();
            Edge base = routeEdgeMap.get(outCode + "->" + inCode);
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                cgEdges.add(new TemporalEdge(base, Lifetime.from(tp.getS(), tp.getF())));
            }
        }
        Map<Object, Double> cgRanks = TemporalPageRankAlgorithm.execute(cgEdges, 0.85d, 0.5d, true);

        double sumTpop = 0.0;
        for (Vertex v : uAirports.values()) {
            double tpopScore = v.property("tpr").isPresent() ? ((Number) v.value("tpr")).doubleValue() : 0.0;
            double cgScore = cgRanks.getOrDefault(v.id(), 0.0);
            sumTpop += tpopScore;
            assertEquals("TemporalPageRank score mismatch on airport " + v.value("code"), cgScore, tpopScore, 1e-12);
        }
        assertEquals("Normalized TemporalPageRank scores must sum to 1.0", 1.0, sumTpop, 1e-9);

        unindexedGraph.close();
        indexedGraph.close();
    }

    @Test
    public void testTemporalBfsParityWithChronoGraphOnCausalChainAndDAG() throws Exception {
        TinkerGraph tGraph = TinkerGraph.open();
        GraphTraversalSource g = tGraph.traversal();
        ChronoGraph cGraph = new ChronoGraph();

        // Build a 6-vertex causal DAG where flight transitions are strictly separated in time
        String[] codes = {"A", "B", "C", "D", "E", "F"};
        Map<String, Vertex> tV = new HashMap<>();
        Map<String, ChronoVertex> cV = new HashMap<>();
        for (String code : codes) {
            tV.put(code, g.addV("Airport").property("code", code).next());
            cV.put(code, (ChronoVertex) cGraph.addVertex(code));
        }

        // Transitions:
        // A -> B: [100, 200)
        // A -> C: [150, 300)
        // B -> D: [250, 350)
        // C -> D: [320, 400)
        // D -> E: [400, 500) (meets [320, 400) and after [250, 350))
        // E -> F: [550, 650)
        Object[][] flights = {
                {"A", "B", 100L, 200L},
                {"A", "C", 150L, 300L},
                {"B", "D", 250L, 350L},
                {"C", "D", 320L, 400L},
                {"D", "E", 400L, 500L},
                {"E", "F", 550L, 650L}
        };

        for (Object[] fl : flights) {
            String u = (String) fl[0];
            String v = (String) fl[1];
            long s = (Long) fl[2];
            long e = (Long) fl[3];
            g.addE("flight").from(tV.get(u)).to(tV.get(v)).lifetime(s, e).iterate();
            ChronoEdge ce = (ChronoEdge) cGraph.addEdge(cV.get(u), cV.get(v), "flight");
            ce.addEvent(new TimePeriod(s, e));
        }

        // 1. T-TinkerPop native tbfs (EARLIEST_ARRIVAL and EARLIEST_DEPARTURE)
        Map<Vertex, Long> arrivalMap = g.V(tV.get("A")).tbfs(0L, TemporalPathTarget.EARLIEST_ARRIVAL, Direction.OUT, "flight").next();
        Map<Vertex, Long> departureMap = g.V(tV.get("A")).tbfs(0L, TemporalPathTarget.EARLIEST_DEPARTURE, Direction.OUT, "flight").next();

        // 2. ChronoGraph TemporalBreadthFirstSearch
        TemporalBreadthFirstSearch cgBfs = new TemporalBreadthFirstSearch();
        cgBfs.compute(cV.get("A"), new TimeInstant(0L), "flight");

        Field gammaField = TemporalBreadthFirstSearch.class.getDeclaredField("gamma");
        gammaField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<com.tinkerpop.blueprints.Vertex, com.tinkerpop.blueprints.Time> cgGamma =
                (Map<com.tinkerpop.blueprints.Vertex, com.tinkerpop.blueprints.Time>) gammaField.get(cgBfs);

        // Both reach B, C, D, E, F (5 destination vertices; cgGamma also includes source A)
        assertEquals(5, arrivalMap.size());
        assertEquals(5, departureMap.size());
        assertEquals(6, cgGamma.size());

        // Check exact arrival and departure times
        for (String code : new String[]{"B", "C", "D", "E", "F"}) {
            Vertex tv = tV.get(code);
            ChronoVertex cv = cV.get(code);
            TimePeriod cgPeriod = (TimePeriod) cgGamma.get(cv);
            assertNotNull("ChronoGraph should reach " + code, cgPeriod);
            assertEquals("Earliest departure mismatch on " + code, cgPeriod.getS(), departureMap.get(tv).longValue());
            assertEquals("Earliest arrival mismatch on " + code, cgPeriod.getF(), arrivalMap.get(tv).longValue());
        }

        tGraph.close();
    }

    @Test
    public void testAllenAxiomaticMutualExclusivityAndConverseSymmetry() {
        // Verify that for any pair of non-empty intervals (X, Y), T-TinkerPop's 13 Allen operators
        // are jointly exhaustive and mutually exclusive (exactly 1 relation holds), and that
        // converse pairs (e.g., overlaps <-> overlappedBy) are strictly symmetric.
        long[][] samples = {
                {100, 300}, {100, 500}, {200, 400}, {300, 500}, {300, 700},
                {400, 600}, {500, 700}, {500, 900}, {700, 900}, {800, 1000}
        };

        Temporal[][] conversePairs = {
                {Temporal.precedes, Temporal.succeeds},
                {Temporal.meets, Temporal.metBy},
                {Temporal.overlaps, Temporal.overlappedBy},
                {Temporal.starts, Temporal.startedBy},
                {Temporal.during, Temporal.contains},
                {Temporal.finishes, Temporal.finishedBy},
                {Temporal.eq, Temporal.eq}
        };

        for (long[] a : samples) {
            Lifetime ltA = Lifetime.from(a[0], a[1]);
            for (long[] b : samples) {
                Lifetime ltB = Lifetime.from(b[0], b[1]);
                int matchCount = 0;
                for (OperatorPair op : ALL_13_ALLEN_OPERATORS) {
                    if (op.tpopOp.test(ltA, ltB, Temporal.Quantifier.ANY)) {
                        matchCount++;
                    }
                }
                assertEquals("Allen 13 relations must be jointly exhaustive & mutually exclusive for ["
                        + a[0] + "," + a[1] + ") vs [" + b[0] + "," + b[1] + ")", 1, matchCount);

                for (Temporal[] pair : conversePairs) {
                    boolean ab = pair[0].test(ltA, ltB, Temporal.Quantifier.ANY);
                    boolean ba = pair[1].test(ltB, ltA, Temporal.Quantifier.ANY);
                    assertEquals("Converse symmetry violation for " + pair[0] + " vs " + pair[1], ab, ba);
                }
            }
        }
    }

    private long countChronoGraphNatRel(ChronoGraph cg, TimePeriod refPeriod, TemporalRelation rel) {
        long count = 0;
        for (com.tinkerpop.blueprints.Edge e : cg.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            NavigableSet<?> matches = ce.getEvents(refPeriod, rel);
            if (matches != null && !matches.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private long countChronoGraphLoop(ChronoGraph cg, TimePeriod refPeriod, TemporalRelation rel) {
        long count = 0;
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        for (com.tinkerpop.blueprints.Edge e : cg.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            boolean matched = false;
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                if (tp.checkTemporalRelation(refPeriod, rel)) {
                    matched = true;
                    break;
                }
            }
            if (matched) count++;
        }
        return count;
    }

    private long countChronoGraphConverseOverlaps(ChronoGraph cg, TimePeriod refPeriod) {
        long count = 0;
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        for (com.tinkerpop.blueprints.Edge e : cg.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            boolean matched = false;
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                if (refPeriod.checkTemporalRelation(tp, TemporalRelation.overlapsWith)) {
                    matched = true;
                    break;
                }
            }
            if (matched) count++;
        }
        return count;
    }

    private long countDirectTinkerPop(TinkerGraph graph, String label, Lifetime refLt, Temporal op, Temporal.Quantifier quantifier) {
        long count = 0;
        Iterator<Edge> it = graph.edges();
        while (it.hasNext()) {
            Edge e = it.next();
            if (!label.equals(e.label())) continue;
            Lifetime eLt = Lifetime.getLifetimeFromProperties(e);
            if (eLt != null && !eLt.isEmpty() && op.test(eLt, refLt, quantifier)) {
                count++;
            }
        }
        return count;
    }

    private GraphTraversal<?, Edge> applyAllenTraversal(GraphTraversal<?, Edge> t, String opName, Edge refEdge, Temporal.Quantifier q) {
        String qKey = (q == Temporal.Quantifier.ANY) ? Temporal.ANY : Temporal.ALL;
        switch (opName) {
            case "before":       return t.temporalBefore(refEdge).with(qKey);
            case "after":        return t.temporalAfter(refEdge).with(qKey);
            case "meets":        return t.temporalMeets(refEdge).with(qKey);
            case "metBy":        return t.temporalMetBy(refEdge).with(qKey);
            case "overlaps":     return t.temporalOverlaps(refEdge).with(qKey);
            case "overlappedBy": return t.temporalOverlappedBy(refEdge).with(qKey);
            case "starts":       return t.temporalStarts(refEdge).with(qKey);
            case "startedBy":    return t.temporalStartedBy(refEdge).with(qKey);
            case "during":       return t.temporalDuring(refEdge).with(qKey);
            case "contains":     return t.temporalContains(refEdge).with(qKey);
            case "finishes":     return t.temporalFinishes(refEdge).with(qKey);
            case "finishedBy":   return t.temporalFinishedBy(refEdge).with(qKey);
            case "equals":       return t.temporalEquals(refEdge).with(qKey);
            default: throw new IllegalArgumentException("Unknown operator: " + opName);
        }
    }
}
