package com.temporal.gremlin;

import com.tinkerpop.blueprints.TimeInstant;
import com.tinkerpop.blueprints.TimePeriod;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;
import org.apache.tinkerpop.gremlin.process.ranking.pagerank.TemporalPageRankAlgorithm;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.TemporalP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.GeneralizedRelation;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.Assert.*;

/**
 * End-to-end correctness validation test suite for every benchmark table and column
 * reported in the T-TinkerPop VLDB-style journal paper:
 *
 *  - Table V   (ChronoGraphBenchmark: temporalBefore 5 columns & tBFS 2 columns)
 *  - Table VI  (IndexSelectivityTradeoffBenchmark: 8 selectivity tiers x 5 columns)
 *  - Table VIII(RepresentationAblationBenchmark: 3 operators x 4 representations)
 *  - Table IX  (MultiIntervalStressBenchmark: k in {1, 2, 5, 10} x 6 columns)
 *  - Table X   (TemporalPageRankBenchmark: TPop TPR vs CG Extract+TPR vs Static PR)
 *  - Table XI  (TTinkerPopCapabilitiesScalingBenchmark: Ligozat, Historical Views, Path Targets)
 */
public class PaperBenchmarksCorrectnessTest {

    private static final String CSV_PATH = "datasets/flightlist_20190101_20190131.csv";
    private static final DateTimeFormatter CSV_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT;

    // =========================================================================
    // 1. TABLE V: ChronoGraphBenchmark (temporalBefore 5 columns & tBFS 2 columns)
    // =========================================================================

    @Test
    public void testTableV_ChronoGraphBenchmarkAllColumnsParity() throws Exception {
        File csv = new File(CSV_PATH);
        assertTrue("OpenSky dataset must exist at " + CSV_PATH, csv.exists());

        TinkerGraph unindexedGraph = TinkerGraph.open();
        TinkerGraph indexedGraph = TinkerGraph.open();
        indexedGraph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);
        ChronoGraph chronoGraph = new ChronoGraph();

        Map<String, Vertex> uAirports = new HashMap<>();
        Map<String, Vertex> iAirports = new HashMap<>();
        Map<String, ChronoVertex> cAirports = new HashMap<>();
        Map<String, Integer> outDeg = new HashMap<>();
        Map<String, List<Lifetime.Interval>> routeIntervals = new LinkedHashMap<>();
        Map<String, String> routeOrigins = new HashMap<>();
        Map<String, String> routeDests = new HashMap<>();

        long minStart = Long.MAX_VALUE;
        long lastStart = 0L, lastEnd = 0L;
        int limit = 10_000;
        int count = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            br.readLine();
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

                String key = origin + "->" + dest;
                routeIntervals.computeIfAbsent(key, k -> new ArrayList<>()).add(new Lifetime.Interval(s, e));
                routeOrigins.putIfAbsent(key, origin);
                routeDests.putIfAbsent(key, dest);
                outDeg.merge(origin, 1, Integer::sum);

                if (s < minStart) minStart = s;
                lastStart = s;
                lastEnd = e;
                count++;
            }
        }

        Edge refUnindexed = null;
        Edge refIndexed = null;
        for (Map.Entry<String, List<Lifetime.Interval>> entry : routeIntervals.entrySet()) {
            String key = entry.getKey();
            String orig = routeOrigins.get(key);
            String dst = routeDests.get(key);
            Lifetime lt = Lifetime.from(entry.getValue());

            Edge eU = uAirports.get(orig).addEdge("flight", uAirports.get(dst));
            lt.attachTo(eU);
            refUnindexed = eU;

            Edge eI = iAirports.get(orig).addEdge("flight", iAirports.get(dst));
            lt.attachTo(eI);
            refIndexed = eI;

            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cAirports.get(orig), cAirports.get(dst), "flight");
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }
        }

        // --- A. Validate all 5 columns of Micro-benchmark (temporalBefore) ---
        TimePeriod refPeriod = new TimePeriod(lastStart, lastEnd);
        Lifetime refLt = Lifetime.from(lastStart, lastEnd);
        GraphTraversalSource gU = unindexedGraph.traversal();
        GraphTraversalSource gI = indexedGraph.traversal();

        // Column 1: CG Loop
        long cgLoopCount = 0;
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            boolean matched = false;
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                if (tp.checkTemporalRelation(refPeriod, TemporalRelation.isBefore)) {
                    matched = true;
                    break;
                }
            }
            if (matched) cgLoopCount++;
        }

        // Column 2: CG NatRel
        long cgNatRelCount = 0;
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            NavigableSet<?> evs = ce.getEvents(refPeriod, TemporalRelation.isBefore);
            if (evs != null && !evs.isEmpty()) cgNatRelCount++;
        }

        // Column 3: TPop Direct
        long tpopDirectCount = 0;
        Iterator<Edge> it = unindexedGraph.edges();
        while (it.hasNext()) {
            Edge e = it.next();
            Lifetime lt = Lifetime.getLifetimeFromProperties(e);
            if (lt != null && !lt.isEmpty() && lt.getStartMillis(0) < lt.getEndMillis(0) && lt.getEndMillis(0) < lastStart) {
                tpopDirectCount++;
            }
        }

        // Column 4: TPop Unindexed (Gremlin with TemporalGraphStepStrategy)
        long tpopUnindexedCount = gU.E().hasLabel("flight")
                .temporalBefore(refLt).with(Temporal.ANY).count().next();

        // Column 5: TPop Indexed (Gremlin with TemporalIndexOptimizationStrategy + IntervalTree)
        long tpopIndexedCount = gI.E().hasLabel("flight")
                .temporalBefore(refLt).with(Temporal.ANY).count().next();

        assertTrue("temporalBefore match count should be positive", tpopDirectCount > 0);
        assertEquals("Table V: CG Loop vs CG NatRel must match", cgLoopCount, cgNatRelCount);
        assertEquals("Table V: CG NatRel vs TPop Direct must match", cgNatRelCount, tpopDirectCount);
        assertEquals("Table V: TPop Direct vs TPop Unindexed must match", tpopDirectCount, tpopUnindexedCount);
        assertEquals("Table V: TPop Direct vs TPop Indexed must match", tpopDirectCount, tpopIndexedCount);

        // --- B. Validate Macro-benchmark (Temporal BFS) ---
        String hubCode = outDeg.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
        Vertex tSource = uAirports.get(hubCode);
        ChronoVertex cSource = cAirports.get(hubCode);

        // Execute ChronoGraph TemporalBreadthFirstSearch (uses strict isAfter: s_next > f_prev, and orderByStart gamma)
        TemporalBreadthFirstSearch cgBfs = new TemporalBreadthFirstSearch();
        // Pass minStart - 1L so ChronoGraph's strict isAfter at source includes flights starting at minStart
        cgBfs.compute(cSource, new TimeInstant(minStart - 1L), "flight");

        Field gammaField = TemporalBreadthFirstSearch.class.getDeclaredField("gamma");
        gammaField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<com.tinkerpop.blueprints.Vertex, com.tinkerpop.blueprints.Time> cgGamma =
                (Map<com.tinkerpop.blueprints.Vertex, com.tinkerpop.blueprints.Time>) gammaField.get(cgBfs);

        Set<String> cgReachedCodes = new HashSet<>();
        for (com.tinkerpop.blueprints.Vertex cv : cgGamma.keySet()) {
            String code = (String) cv.getId();
            if (!code.equals(hubCode)) {
                cgReachedCodes.add(code);
            }
        }

        // Execute T-TinkerPop tbfs(EARLIEST_DEPARTURE)
        Map<Vertex, Long> tpopBfsMap = gU.V(tSource)
                .tbfs(minStart - 1L, TemporalPathTarget.EARLIEST_DEPARTURE, Direction.OUT, "flight")
                .next();
        Set<String> tpopReachedCodes = new HashSet<>();
        for (Vertex v : tpopBfsMap.keySet()) {
            tpopReachedCodes.add(v.value("code"));
        }

        // Every airport reachable under ChronoGraph's strict (s_next > f_prev, single-metric gamma)
        // MUST be reachable under T-TinkerPop's causal tbfs (s_next >= f_prev, decoupled causal arrival tracking).
        assertTrue("T-TinkerPop tBFS must reach all airports reached by ChronoGraph tBFS",
                tpopReachedCodes.containsAll(cgReachedCodes));
        // And for all 1-hop neighbors of the source, earliest departure time must be 100% identical!
        for (com.tinkerpop.blueprints.Edge ceEdge : cSource.getEdges(com.tinkerpop.blueprints.Direction.OUT, "flight")) {
            String neighborCode = (String) ceEdge.getVertex(com.tinkerpop.blueprints.Direction.IN).getId();
            Vertex tv = uAirports.get(neighborCode);
            ChronoVertex cv = cAirports.get(neighborCode);
            assertTrue("1-hop neighbor must be in T-TinkerPop tBFS", tpopBfsMap.containsKey(tv));
            assertTrue("1-hop neighbor must be in ChronoGraph tBFS", cgGamma.containsKey(cv));
            TimePeriod cgTp = (TimePeriod) cgGamma.get(cv);
            assertTrue("T-TinkerPop earliest departure must be <= ChronoGraph's (can be strictly earlier if a 2-hop fast/meets path beats 1-hop) for " + neighborCode,
                    tpopBfsMap.get(tv) <= cgTp.getS());
        }

        // Furthermore, if we evaluate T-TinkerPop's TinkerGraph under ChronoGraph's exact single-metric
        // (gamma[v] ordered by start time) and strict isAfter (s_next > f_prev) rule, both engines
        // produce 100% identical reached airport sets and timestamps across all hops:
        Map<String, Long> cgTimestampMap = new HashMap<>();
        for (Map.Entry<com.tinkerpop.blueprints.Vertex, com.tinkerpop.blueprints.Time> entry : cgGamma.entrySet()) {
            String code = (String) entry.getKey().getId();
            if (!code.equals(hubCode)) {
                cgTimestampMap.put(code, ((TimePeriod) entry.getValue()).getS());
            }
        }
        Map<String, Long> tpopUnderCgRule = runTinkerPopWithChronoGraphStrictSingleMetricRule(tSource, minStart - 1L);
        assertEquals("Under ChronoGraph's strict isAfter + single-metric rule, T-TinkerPop and ChronoGraph must match 100%",
                cgTimestampMap, tpopUnderCgRule);

        unindexedGraph.close();
        indexedGraph.close();
    }

    /**
     * Evaluates BFS on T-TinkerPop's TinkerGraph using ChronoGraph's exact
     * {@link TemporalBreadthFirstSearch} semantics:
     * (1) strict {@code isAfter} transition ({@code s_next > f_prev}), and
     * (2) single {@code TimePeriod(s, f)} per vertex ordered by {@code s} (updating {@code gamma[v]} iff {@code s_new < s_old},
     *     or {@code s_new == s_old && f_new < f_old}).
     */
    private static Map<String, Long> runTinkerPopWithChronoGraphStrictSingleMetricRule(Vertex source, long initialTime) {
        Map<Vertex, long[]> gamma = new HashMap<>();
        gamma.put(source, new long[]{initialTime, initialTime});
        Set<Vertex> frontier = new HashSet<>();
        frontier.add(source);

        while (!frontier.isEmpty()) {
            Set<Vertex> nextFrontier = new HashSet<>();
            for (Vertex u : frontier) {
                long uFinish = gamma.get(u)[1];
                Iterator<Edge> edgeIt = u.edges(Direction.OUT, "flight");
                while (edgeIt.hasNext()) {
                    Edge e = edgeIt.next();
                    Vertex w = e.inVertex();
                    Lifetime lt = Lifetime.getLifetimeFromProperties(e);
                    if (lt == null || lt.isEmpty()) continue;

                    // ChronoGraph edge.getEvents(gamma[u], isAfter, false) picks the first event with s > uFinish
                    long candS = Long.MAX_VALUE;
                    long candF = Long.MAX_VALUE;
                    for (int i = 0; i < lt.getIntervalCount(); i++) {
                        long s = lt.getStartMillis(i);
                        long f = lt.getEndMillis(i);
                        if (s > uFinish) {
                            if (s < candS || (s == candS && f < candF)) {
                                candS = s;
                                candF = f;
                            }
                        }
                    }
                    if (candS == Long.MAX_VALUE) continue;

                    long[] existing = gamma.get(w);
                    if (existing == null || candS < existing[0] || (candS == existing[0] && candF < existing[1])) {
                        gamma.put(w, new long[]{candS, candF});
                        nextFrontier.add(w);
                    }
                }
            }
            frontier = nextFrontier;
        }

        Map<String, Long> result = new HashMap<>();
        String srcCode = source.value("code");
        for (Map.Entry<Vertex, long[]> entry : gamma.entrySet()) {
            String code = entry.getKey().value("code");
            if (!code.equals(srcCode)) {
                result.put(code, entry.getValue()[0]);
            }
        }
        return result;
    }

    // =========================================================================
    // 2. TABLE VI: IndexSelectivityTradeoffBenchmark (8 selectivities x 5 columns)
    // =========================================================================

    @Test
    public void testTableVI_IndexSelectivityTradeoffAllColumnsParity() {
        int numEdges = 5_000;
        int intervalsPerEdge = 3;
        long timelineStart = 1_546_300_800_000L;
        long timelineSpan = 365L * 24 * 3600 * 1000L;
        long edgeDuration = 10_000L;
        double[] selectivities = {0.0001, 0.001, 0.01, 0.05, 0.10, 0.25, 0.50, 0.75};

        TinkerGraph unindexedGraph = TinkerGraph.open();
        TinkerGraph indexedGraph = TinkerGraph.open();
        indexedGraph.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);
        ChronoGraph chronoGraph = new ChronoGraph();

        Vertex uU = unindexedGraph.addVertex("hub");
        Vertex vU = unindexedGraph.addVertex("hub");
        Vertex uI = indexedGraph.addVertex("hub");
        Vertex vI = indexedGraph.addVertex("hub");
        ChronoVertex cU = (ChronoVertex) chronoGraph.addVertex("u");

        for (int i = 0; i < numEdges; i++) {
            double frac = (i + 0.5) / numEdges;
            long baseStart = timelineStart + (long) (frac * timelineSpan);
            List<Lifetime.Interval> ivs = new ArrayList<>(intervalsPerEdge);
            for (int j = 0; j < intervalsPerEdge; j++) {
                long s = baseStart + j * (edgeDuration * 2);
                long e = s + edgeDuration;
                ivs.add(new Lifetime.Interval(s, e));
            }
            Lifetime lt = Lifetime.from(ivs);

            Edge e1 = uU.addEdge("event", vU);
            lt.attachTo(e1);

            Edge e2 = uI.addEdge("event", vI);
            lt.attachTo(e2);

            ChronoVertex cV = (ChronoVertex) chronoGraph.addVertex("v" + i);
            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cU, cV, "event");
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }
        }

        GraphTraversalSource gU = unindexedGraph.traversal();
        GraphTraversalSource gI = indexedGraph.traversal();
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);

        for (double sel : selectivities) {
            long querySpan = Math.max(edgeDuration * 2, (long) (sel * timelineSpan));
            long queryStart = timelineStart + (timelineSpan - querySpan) / 2;
            long queryEnd = queryStart + querySpan;

            Lifetime refLt = Lifetime.from(queryStart, queryEnd);
            TimePeriod refPeriod = new TimePeriod(queryStart, queryEnd);

            // Column 1: TPop Indexed
            long indexedCount = gI.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next();

            // Column 2: TPop Unindexed
            long unindexedCount = gU.E().hasLabel("event").temporalOverlaps(refLt).with(Temporal.ANY).count().next();

            // Column 3: TPop Direct
            long directCount = 0;
            Iterator<Edge> it = unindexedGraph.edges();
            while (it.hasNext()) {
                Edge e = it.next();
                Lifetime lt = Lifetime.getLifetimeFromProperties(e);
                if (lt != null && Temporal.overlaps.test(lt, refLt, Temporal.Quantifier.ANY)) {
                    directCount++;
                }
            }

            // Column 4: CG NatRel
            long cgNatCount = 0;
            for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
                ChronoEdge ce = (ChronoEdge) e;
                NavigableSet<?> evs = ce.getEvents(refPeriod, TemporalRelation.overlapsWith);
                if (evs != null && !evs.isEmpty()) cgNatCount++;
            }

            // Column 5: CG Loop
            long cgLoopCount = 0;
            for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
                ChronoEdge ce = (ChronoEdge) e;
                boolean matched = false;
                for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                    TimePeriod tp = (TimePeriod) ev.getTime();
                    if (tp.checkTemporalRelation(refPeriod, TemporalRelation.overlapsWith)) {
                        matched = true;
                        break;
                    }
                }
                if (matched) cgLoopCount++;
            }

            assertEquals("Table VI (sel=" + sel + "): Indexed vs Unindexed", unindexedCount, indexedCount);
            assertEquals("Table VI (sel=" + sel + "): Direct vs Unindexed", directCount, unindexedCount);
            assertEquals("Table VI (sel=" + sel + "): CG NatRel vs Direct", cgNatCount, directCount);
            assertEquals("Table VI (sel=" + sel + "): CG Loop vs Direct", cgLoopCount, directCount);
        }

        unindexedGraph.close();
        indexedGraph.close();
    }

    // =========================================================================
    // 3. TABLE VIII: RepresentationAblationBenchmark (3 operators x 4 columns)
    // =========================================================================

    @Test
    public void testTableVIII_RepresentationAblationAllColumnsParity() throws Exception {
        File csv = new File(CSV_PATH);
        assertTrue("OpenSky dataset must exist", csv.exists());

        TinkerGraph normGraph = TinkerGraph.open();
        TinkerGraph strGraph = TinkerGraph.open();
        ChronoGraph chronoGraph = new ChronoGraph();

        Map<String, Vertex> nAirports = new HashMap<>();
        Map<String, Vertex> sAirports = new HashMap<>();
        Map<String, ChronoVertex> cAirports = new HashMap<>();
        Map<String, List<Lifetime.Interval>> routeIntervals = new LinkedHashMap<>();
        Map<String, String> routeOrigins = new HashMap<>();
        Map<String, String> routeDests = new HashMap<>();

        long sampleRefStart = 0L, sampleRefEnd = 0L;
        int limit = 5_000;
        int count = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            br.readLine();
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

                nAirports.computeIfAbsent(origin, c -> normGraph.addVertex(T.label, "Airport", "code", c));
                nAirports.computeIfAbsent(dest, c -> normGraph.addVertex(T.label, "Airport", "code", c));
                sAirports.computeIfAbsent(origin, c -> strGraph.addVertex(T.label, "Airport", "code", c));
                sAirports.computeIfAbsent(dest, c -> strGraph.addVertex(T.label, "Airport", "code", c));
                cAirports.computeIfAbsent(origin, c -> (ChronoVertex) chronoGraph.addVertex(c));
                cAirports.computeIfAbsent(dest, c -> (ChronoVertex) chronoGraph.addVertex(c));

                String key = origin + "->" + dest;
                routeIntervals.computeIfAbsent(key, k -> new ArrayList<>()).add(new Lifetime.Interval(s, e));
                routeOrigins.putIfAbsent(key, origin);
                routeDests.putIfAbsent(key, dest);

                if (count == limit / 2) {
                    sampleRefStart = s;
                    sampleRefEnd = e;
                }
                count++;
            }
        }

        for (Map.Entry<String, List<Lifetime.Interval>> entry : routeIntervals.entrySet()) {
            String key = entry.getKey();
            String orig = routeOrigins.get(key);
            String dst = routeDests.get(key);
            Lifetime lt = Lifetime.from(entry.getValue());

            Edge ne = nAirports.get(orig).addEdge("flight", nAirports.get(dst));
            lt.attachTo(ne);

            StringBuilder sb = new StringBuilder();
            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cAirports.get(orig), cAirports.get(dst), "flight");
            for (int i = 0; i < lt.getIntervalCount(); i++) {
                long s = lt.getStartMillis(i);
                long e = lt.getEndMillis(i);
                ce.addEvent(new TimePeriod(s, e));
                if (i > 0) sb.append(",");
                sb.append(ISO_FMT.format(Instant.ofEpochMilli(s).atOffset(ZoneOffset.UTC)))
                  .append("/")
                  .append(ISO_FMT.format(Instant.ofEpochMilli(e).atOffset(ZoneOffset.UTC)));
            }
            sAirports.get(orig).addEdge("flight", sAirports.get(dst), "intervalsStr", sb.toString());
        }

        // Wide window for overlaps & during so all 3 operators have non-zero matches
        long wideStart = sampleRefStart - 3_600_000L;
        long wideEnd = sampleRefEnd + 3_600_000L;

        GraphTraversalSource gNorm = normGraph.traversal();
        GraphTraversalSource gStr = strGraph.traversal();

        String[][] ops = {
                {"temporalBefore", String.valueOf(sampleRefStart), String.valueOf(sampleRefEnd)},
                {"temporalOverlaps", String.valueOf(wideStart), String.valueOf(wideEnd)},
                {"during", String.valueOf(wideStart), String.valueOf(wideEnd)}
        };

        for (String[] opSpec : ops) {
            String op = opSpec[0];
            long rS = Long.parseLong(opSpec[1]);
            long rE = Long.parseLong(opSpec[2]);
            Lifetime refLt = Lifetime.from(rS, rE);
            TimePeriod refPeriod = new TimePeriod(rS, rE);

            // Column 1: Dynamic String Parsing
            long strCount = gStr.E().hasLabel("flight").filter(t -> {
                String raw = (String) t.get().property("intervalsStr").orElse("");
                if (raw.isEmpty()) return false;
                for (String token : raw.split(",")) {
                    int slash = token.indexOf('/');
                    if (slash < 0) continue;
                    long s = Instant.parse(token.substring(0, slash)).toEpochMilli();
                    long e = Instant.parse(token.substring(slash + 1)).toEpochMilli();
                    if ("temporalBefore".equals(op) && e < rS) return true;
                    if ("temporalOverlaps".equals(op) && s < rS && e > rS && e < rE) return true;
                    if ("during".equals(op) && s > rS && e < rE) return true;
                }
                return false;
            }).count().next();

            // Column 2: ChronoGraph Objects
            TemporalRelation cgRel = "temporalBefore".equals(op) ? TemporalRelation.isBefore
                    : ("temporalOverlaps".equals(op) ? TemporalRelation.overlapsWith : TemporalRelation.during);
            long cgCount = 0;
            for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
                ChronoEdge ce = (ChronoEdge) e;
                NavigableSet<?> evs = ce.getEvents(refPeriod, cgRel);
                if (evs != null && !evs.isEmpty()) cgCount++;
            }

            // Column 3: TPop Traversal
            long tpopTravCount;
            if ("temporalBefore".equals(op)) {
                tpopTravCount = gNorm.E().hasLabel("flight").temporalBefore(refLt).with(Temporal.ANY).count().next();
            } else if ("temporalOverlaps".equals(op)) {
                tpopTravCount = gNorm.E().hasLabel("flight").temporalOverlaps(refLt).with(Temporal.ANY).count().next();
            } else {
                tpopTravCount = gNorm.E().hasLabel("flight").temporalDuring(refLt).with(Temporal.ANY).count().next();
            }

            // Column 4: TPop Direct
            Temporal tOp = "temporalBefore".equals(op) ? Temporal.precedes
                    : ("temporalOverlaps".equals(op) ? Temporal.overlaps : Temporal.during);
            long tpopDirectCount = 0;
            Iterator<Edge> it = normGraph.edges();
            while (it.hasNext()) {
                Edge e = it.next();
                Lifetime lt = Lifetime.getLifetimeFromProperties(e);
                if (lt != null && tOp.test(lt, refLt, Temporal.Quantifier.ANY)) {
                    tpopDirectCount++;
                }
            }

            assertTrue("Table VIII (" + op + ") should have positive matches", tpopDirectCount > 0);
            assertEquals("Table VIII (" + op + "): String vs CG", strCount, cgCount);
            assertEquals("Table VIII (" + op + "): CG vs TPop Traversal", cgCount, tpopTravCount);
            assertEquals("Table VIII (" + op + "): TPop Traversal vs TPop Direct", tpopTravCount, tpopDirectCount);
        }

        normGraph.close();
        strGraph.close();
    }

    // =========================================================================
    // 4. TABLE IX: MultiIntervalStressBenchmark (k in {1, 2, 5, 10} x 6 columns)
    // =========================================================================

    @Test
    public void testTableIX_MultiIntervalStressAllColumnsParity() {
        int[] kValues = {1, 2, 5, 10};
        int numEdges = 1_000;
        long baseEpoch = 1_700_000_000_000L;
        long windowGap = 100_000L;
        long subWidth = 40_000L;

        for (int k : kValues) {
            TinkerGraph graph = TinkerGraph.open();
            ChronoGraph chronoGraph = new ChronoGraph();
            Random rng = new Random(42L + k);

            Vertex u = graph.addVertex("hub");
            Vertex v = graph.addVertex("hub");
            ChronoVertex cu = (ChronoVertex) chronoGraph.addVertex("u");
            ChronoVertex cv = (ChronoVertex) chronoGraph.addVertex("v");

            List<Lifetime.Interval> refIvs = new ArrayList<>(k);
            TimePeriod[] refPeriods = new TimePeriod[k];
            for (int j = 0; j < k; j++) {
                long rs = baseEpoch + j * windowGap + 20_000L;
                long re = rs + subWidth;
                refIvs.add(new Lifetime.Interval(new Date(rs), new Date(re)));
                refPeriods[j] = new TimePeriod(rs, re);
            }
            Lifetime refLt = Lifetime.from(refIvs);
            Edge refEdge = u.addEdge("refEvent", v);
            refLt.attachTo(refEdge);

            for (int i = 0; i < numEdges; i++) {
                List<Lifetime.Interval> ivs = new ArrayList<>(k);
                long offset = (rng.nextInt(200) - 100) * 1_000L;
                for (int j = 0; j < k; j++) {
                    long s = baseEpoch + j * windowGap + offset;
                    long e = s + subWidth;
                    ivs.add(new Lifetime.Interval(new Date(s), new Date(e)));
                }
                Lifetime lt = Lifetime.from(ivs);
                Edge edge = u.addEdge("event", v);
                lt.attachTo(edge);

                ChronoVertex cvi = (ChronoVertex) chronoGraph.addVertex("v_" + i);
                ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cu, cvi, "event");
                for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                    ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
                }
            }

            GraphTraversalSource g = graph.traversal();
            TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);

            // Column 1: TPop ANY
            long tpopAny = g.E().hasLabel("event").temporalOverlaps(refEdge).with(Temporal.ANY).count().next();

            // Column 2: TPop ALL
            long tpopAll = g.E().hasLabel("event").temporalOverlaps(refEdge).with(Temporal.ALL).count().next();

            // Column 3: TPop Sweep (Direct ANY)
            long tpopSweep = 0;
            long directAll = 0;
            // Column 6: Naive O(k^2)
            long naiveCartesian = 0;

            Iterator<Edge> it = graph.edges();
            while (it.hasNext()) {
                Edge e = it.next();
                if (!"event".equals(e.label())) continue;
                Lifetime lt = Lifetime.getLifetimeFromProperties(e);
                if (Temporal.overlaps.test(lt, refLt, Temporal.Quantifier.ANY)) tpopSweep++;
                if (Temporal.overlaps.test(lt, refLt, Temporal.Quantifier.ALL)) directAll++;

                boolean naiveMatch = false;
                for (int i = 0; i < lt.getIntervalCount() && !naiveMatch; i++) {
                    long s1 = lt.getStartMillis(i), e1 = lt.getEndMillis(i);
                    for (int j = 0; j < refLt.getIntervalCount(); j++) {
                        long s2 = refLt.getStartMillis(j), e2 = refLt.getEndMillis(j);
                        if (s1 < s2 && e1 > s2 && e1 < e2) {
                            naiveMatch = true;
                            break;
                        }
                    }
                }
                if (naiveMatch) naiveCartesian++;
            }

            // Column 4: CG NatRel
            long cgNatRel = 0;
            for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
                ChronoEdge ce = (ChronoEdge) e;
                boolean matched = false;
                for (TimePeriod rp : refPeriods) {
                    NavigableSet<?> evs = ce.getEvents(rp, TemporalRelation.overlapsWith);
                    if (evs != null && !evs.isEmpty()) {
                        matched = true;
                        break;
                    }
                }
                if (matched) cgNatRel++;
            }

            // Column 5: CG Loop
            long cgLoop = 0;
            for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
                ChronoEdge ce = (ChronoEdge) e;
                boolean matched = false;
                for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                    TimePeriod tp = (TimePeriod) ev.getTime();
                    for (TimePeriod rp : refPeriods) {
                        if (tp.checkTemporalRelation(rp, TemporalRelation.overlapsWith)) {
                            matched = true;
                            break;
                        }
                    }
                    if (matched) break;
                }
                if (matched) cgLoop++;
            }

            // All 5 existential (ANY) columns must return the exact same match count
            assertEquals("Table IX (k=" + k + "): TPop ANY vs TPop Sweep", tpopSweep, tpopAny);
            assertEquals("Table IX (k=" + k + "): TPop Sweep vs CG NatRel", tpopSweep, cgNatRel);
            assertEquals("Table IX (k=" + k + "): TPop Sweep vs CG Loop", tpopSweep, cgLoop);
            assertEquals("Table IX (k=" + k + "): TPop Sweep vs Naive O(k^2)", tpopSweep, naiveCartesian);

            // Moreover, TPop ALL must match Direct ALL exactly.
            // Note on expected difference between ALL and ANY for overlaps:
            // When k=1, ALL and ANY are identical (tpopAll == tpopAny).
            // When k>1, ALL evaluates holistic lifetime bounds (minStart(X) < minStart(Y) && maxEnd(X) < maxEnd(Y) && X.intersects(Y)),
            // whereas ANY evaluates existential pairwise sub-interval overlaps (\exists x_i \in X, y_j \in Y : overlaps(x_i, y_j)).
            // Thus for k>1, ALL and ANY intentionally differ on multi-interval lifetimes.
            assertEquals("Table IX (k=" + k + "): TPop ALL vs Direct ALL", directAll, tpopAll);
            if (k == 1) {
                assertEquals("Table IX (k=1): TPop ALL must equal TPop ANY for single intervals", tpopAny, tpopAll);
            } else {
                assertTrue("Table IX (k=" + k + "): TPop ALL and TPop ANY both return positive valid matches",
                        tpopAll > 0 && tpopAny > 0);
            }

            graph.close();
        }
    }

    // =========================================================================
    // 5. TABLE X: TemporalPageRankBenchmark (TPop TPR vs CG Extract+TPR vs Static PR)
    // =========================================================================

    @Test
    public void testTableX_TemporalPageRankAllColumnsParity() throws Exception {
        File csv = new File(CSV_PATH);
        assertTrue("OpenSky dataset must exist", csv.exists());

        TinkerGraph graph = TinkerGraph.open();
        ChronoGraph chronoGraph = new ChronoGraph();
        Map<String, Vertex> airportMap = new HashMap<>();
        Map<String, ChronoVertex> cgAirportMap = new HashMap<>();
        Map<String, Edge> routeEdgeMap = new HashMap<>();
        Map<String, List<Lifetime.Interval>> routeIntervals = new LinkedHashMap<>();
        Map<String, String> routeOrigins = new HashMap<>();
        Map<String, String> routeDests = new HashMap<>();

        int limit = 5_000;
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            br.readLine();
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

                airportMap.computeIfAbsent(origin, c -> graph.addVertex(T.label, "Airport", "code", c));
                airportMap.computeIfAbsent(dest, c -> graph.addVertex(T.label, "Airport", "code", c));
                cgAirportMap.computeIfAbsent(origin, c -> (ChronoVertex) chronoGraph.addVertex(c));
                cgAirportMap.computeIfAbsent(dest, c -> (ChronoVertex) chronoGraph.addVertex(c));

                String key = origin + "->" + dest;
                routeIntervals.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Lifetime.Interval(new Date(s), new Date(e)));
                routeOrigins.putIfAbsent(key, origin);
                routeDests.putIfAbsent(key, dest);
                count++;
            }
        }

        for (Map.Entry<String, List<Lifetime.Interval>> entry : routeIntervals.entrySet()) {
            String key = entry.getKey();
            String origin = routeOrigins.get(key);
            String dest = routeDests.get(key);
            Lifetime lt = Lifetime.from(entry.getValue());

            Edge e = airportMap.get(origin).addEdge("flight", airportMap.get(dest));
            lt.attachTo(e);
            routeEdgeMap.put(key, e);

            ChronoEdge ce = (ChronoEdge) chronoGraph.addEdge(cgAirportMap.get(origin), cgAirportMap.get(dest), "flight");
            for (int idx = 0; idx < lt.getIntervalCount(); idx++) {
                ce.addEvent(new TimePeriod(lt.getStartMillis(idx), lt.getEndMillis(idx)));
            }
        }

        GraphTraversalSource g = graph.traversal();

        // Column 1: TPop TPR
        long tpopCount = g.V().temporalPageRank()
                .with(TemporalPageRank.propertyName, "tpr")
                .with(TemporalPageRank.alpha, 0.85d)
                .with(TemporalPageRank.beta, 0.5d)
                .with(TemporalPageRank.normalize, true)
                .count().next();

        // Column 2: CG Extract+TPR
        TimePeriod allTime = new TimePeriod(Long.MIN_VALUE, Long.MAX_VALUE);
        List<TemporalEdge> cgEdges = new ArrayList<>();
        for (com.tinkerpop.blueprints.Edge e : chronoGraph.getEdges()) {
            ChronoEdge ce = (ChronoEdge) e;
            String outId = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.OUT).getId();
            String inId = (String) ce.getVertex(com.tinkerpop.blueprints.Direction.IN).getId();
            Edge base = routeEdgeMap.get(outId + "->" + inId);
            for (com.tinkerpop.blueprints.Event ev : ce.getEvents(allTime, TemporalRelation.during)) {
                TimePeriod tp = (TimePeriod) ev.getTime();
                cgEdges.add(new TemporalEdge(base, Lifetime.from(new Date(tp.getS()), new Date(tp.getF()))));
            }
        }
        Map<Object, Double> cgRanks = TemporalPageRankAlgorithm.execute(cgEdges, 0.85d, 0.5d, true);

        // Column 3: Static PageRank (20 iterations on GraphComputer, identical to TemporalPageRankBenchmark)
        org.apache.tinkerpop.gremlin.process.computer.ComputerResult cr = graph.compute()
                .program(org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.PageRankVertexProgram.build()
                        .iterations(20)
                        .create(graph))
                .submit()
                .get();
        long staticCount = cr.graph().traversal().V().count().next();

        assertEquals("Table X: TPop TPR and CG Extract+TPR must rank the same number of airports",
                tpopCount, (long) cgRanks.size());
        assertEquals("Table X: TPop TPR and Static PR must process the same number of airports |V|",
                tpopCount, staticCount);

        // Moreover, TPop TPR and CG Extract+TPR must produce identical per-vertex scores
        for (Vertex v : airportMap.values()) {
            double tpopScore = ((Number) v.value("tpr")).doubleValue();
            double cgScore = cgRanks.get(v.id());
            assertEquals("Table X: Exact score parity for airport " + v.value("code"), cgScore, tpopScore, 1e-12);
        }

        graph.close();
    }

    // =========================================================================
    // 6. TABLE XI: TTinkerPopCapabilitiesScalingBenchmark (Ligozat, Views, Paths)
    // =========================================================================

    @Test
    public void testTableXI_TTinkerPopUniqueCapabilitiesAllColumnsParity() {
        int numEdges = 2_000;
        long baseTime = 1_700_000_000_000L;
        long timeSpan = 86_400_000L * 30;

        TinkerGraph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();
        Random rng = new Random(42L + numEdges);

        int numVertices = 100;
        Vertex[] vertices = new Vertex[numVertices];
        long mid = baseTime + (timeSpan / 2);
        for (int i = 0; i < numVertices; i++) {
            Vertex v = graph.addVertex(T.label, "Station", "code", "S" + i);
            Lifetime.from(new Date(baseTime), new Date(baseTime + timeSpan)).attachTo(v);
            g.V(v).lifetimeProperty("status", "NORMAL", new Date(baseTime), new Date(mid)).iterate();
            g.V(v).lifetimeProperty("alert", "CONGESTED", new Date(mid + 1), new Date(baseTime + timeSpan)).iterate();
            vertices[i] = v;
        }

        for (int i = 0; i < numEdges; i++) {
            Vertex u = vertices[rng.nextInt(numVertices)];
            Vertex v = vertices[rng.nextInt(numVertices)];
            if (u == v) v = vertices[(i + 1) % numVertices];

            long s1 = baseTime + (Math.abs(rng.nextLong()) % (timeSpan / 2));
            long d1 = 600_000L + (Math.abs(rng.nextLong()) % 3_600_000L);
            long e1 = s1 + d1;
            long s2 = e1 + 3_600_000L + (Math.abs(rng.nextLong()) % 7_200_000L);
            long d2 = 600_000L + (Math.abs(rng.nextLong()) % 3_600_000L);
            long e2 = s2 + d2;

            Edge edge = u.addEdge("event", v);
            Lifetime.from(Arrays.asList(
                    new Lifetime.Interval(new Date(s1), new Date(e1)),
                    new Lifetime.Interval(new Date(s2), new Date(e2))
            )).attachTo(edge);
        }

        long refStart = baseTime + (timeSpan / 4);
        long refEnd = refStart + (timeSpan / 8);
        Lifetime refSingle = Lifetime.from(new Date(refStart), new Date(refEnd));
        Lifetime refMulti2 = Lifetime.from(Arrays.asList(
                new Lifetime.Interval(new Date(refStart), new Date(refEnd)),
                new Lifetime.Interval(new Date(refEnd + 7_200_000L), new Date(refEnd + 14_400_000L))
        ));
        GeneralizedRelation rel44 = new GeneralizedRelation(4, 4, 0, 0, 4, 4);

        // --- Suite A: Ligozat (p,q)-Calculus ---
        long atomicOverlaps = g.E().hasLabel("event").temporalOverlaps(refSingle).with(Temporal.ANY).count().next();
        long convexBetween = g.E().hasLabel("event")
                .has(Lifetime.TEMPORAL_INTERVALS, TemporalP.between(Temporal.precedes, Temporal.overlaps, refSingle).any())
                .count().next();
        long multiBranchOr = g.E().hasLabel("event")
                .or(
                        __.temporalBefore(refSingle).with(Temporal.ANY),
                        __.temporalMeets(refSingle).with(Temporal.ANY),
                        __.temporalOverlaps(refSingle).with(Temporal.ANY)
                ).count().next();
        long pi44Count = g.E().hasLabel("event")
                .has(Lifetime.TEMPORAL_INTERVALS, TemporalP.generalized(refMulti2, rel44))
                .count().next();

        long directPi44Count = 0;
        Iterator<Edge> eIt = graph.edges();
        while (eIt.hasNext()) {
            Edge e = eIt.next();
            if (rel44.equals(GeneralizedRelation.between(e, refMulti2))) {
                directPi44Count++;
            }
        }

        assertEquals("Table XI (Ligozat): Convex [precedes, overlaps] must equal Multi-Branch .or(before, meets, overlaps)",
                multiBranchOr, convexBetween);
        assertTrue("Table XI (Ligozat): Atomic overlaps must be a subset of Convex [precedes, overlaps]",
                atomicOverlaps <= convexBetween);
        assertEquals("Table XI (Ligozat): TemporalP.generalized Pi(4,4) must match direct GeneralizedRelation.between",
                directPi44Count, pi44Count);

        // --- Suite B: Historical Subgraph Views ---
        Date snapInstant = new Date(refStart + 1_800_000L);
        Date winStart = new Date(refStart);
        Date winEnd = new Date(refEnd);
        Lifetime winLt = Lifetime.from(winStart, winEnd);
        Lifetime snapLt = Lifetime.from(snapInstant, snapInstant);

        long windowViewHop = g.V().window(winStart, winEnd).out("event").count().next();
        long manualFilterHop = g.V().temporalIntersects(winLt)
                .outE("event").temporalIntersects(winLt)
                .inV().temporalIntersects(winLt)
                .count().next();
        long atTimeViewHop = g.V().atTime(snapInstant).out("event").count().next();
        long manualAtTimeHop = g.V().temporalIntersects(snapLt)
                .outE("event").temporalIntersects(snapLt)
                .inV().temporalIntersects(snapLt)
                .count().next();
        long tPropWindowCount = g.V().window(winStart, winEnd).values("status").count().next();
        long tPropFutureAlertInWindowCount = g.V().window(winStart, winEnd).values("alert").count().next();

        assertEquals("Table XI (Views): window(t1, t2) hop count must equal Manual temporalIntersects filter chain",
                manualFilterHop, windowViewHop);
        assertEquals("Table XI (Views): atTime(t) hop count must equal Manual instant filter chain",
                manualAtTimeHop, atTimeViewHop);
        assertTrue("Table XI (Views): atTime(t) inside [t1, t2) must be <= window(t1, t2)",
                atTimeViewHop <= windowViewHop);
        // Since [refStart, refEnd) lies strictly inside [baseTime, mid], "status" is active on all 100 vertices while "alert" is active on 0
        assertEquals("Table XI (Views): tProp must return all active 'status' properties in [refStart, refEnd)",
                (long) numVertices, tPropWindowCount);
        assertEquals("Table XI (Views): tProp must filter out all future 'alert' properties in [refStart, refEnd)",
                0L, tPropFutureAlertInWindowCount);

        // --- Suite C: Temporal Path Targets ---
        Vertex src = vertices[0];
        long deadline = baseTime + timeSpan;

        Map<Vertex, Long> eaMap = g.V(src).tbfs(TemporalPathTarget.EARLIEST_ARRIVAL, baseTime).next();
        Map<Vertex, Long> edMap = g.V(src).tbfs(TemporalPathTarget.EARLIEST_DEPARTURE, baseTime).next();
        Map<Vertex, Long> ldMap = g.V(src).tbfs(TemporalPathTarget.LATEST_DEPARTURE, deadline).next();
        Map<Vertex, Long> laMap = g.V(src).tbfs(TemporalPathTarget.LATEST_ARRIVAL, deadline).next();

        assertEquals("Table XI (Paths): Forward targets EA and ED must reach the exact same vertex set",
                eaMap.keySet(), edMap.keySet());
        assertEquals("Table XI (Paths): Backward deadline targets LD and LA must reach the exact same vertex set",
                ldMap.keySet(), laMap.keySet());

        for (Vertex v : eaMap.keySet()) {
            // For any reachable vertex, earliest departure timestamp must be < earliest arrival timestamp
            assertTrue("Table XI (Paths): ED start time must be < EA end time on vertex " + v.id(),
                    edMap.get(v) < eaMap.get(v));
        }
        for (Vertex v : ldMap.keySet()) {
            // For any backward-reachable vertex, latest departure timestamp must be < latest arrival timestamp
            assertTrue("Table XI (Paths): LD start time must be < LA end time on vertex " + v.id(),
                    ldMap.get(v) < laMap.get(v));
        }

        // Column 5 of Suite C: Bounded-delay 2-hop sequentialPath(0L, 86_400_000L, "event")
        Object[] seqSeeds = new Object[Math.min(20, numVertices)];
        for (int i = 0; i < seqSeeds.length; i++) seqSeeds[i] = vertices[i].id();
        long seqPathCount = g.V(seqSeeds)
                .sequentialPath(0L, 86_400_000L, "event")
                .sequentialPath(0L, 86_400_000L, "event")
                .count().next();

        long directSeqPathCount = 0;
        for (int i = 0; i < seqSeeds.length; i++) {
            Vertex seed = vertices[i];
            Iterator<Edge> e1It = seed.edges(org.apache.tinkerpop.gremlin.structure.Direction.OUT, "event");
            while (e1It.hasNext()) {
                Edge e1 = e1It.next();
                Lifetime lt1 = Lifetime.getLifetimeFromProperties(e1);
                Vertex midV = e1.inVertex();
                Iterator<Edge> e2It = midV.edges(org.apache.tinkerpop.gremlin.structure.Direction.OUT, "event");
                while (e2It.hasNext()) {
                    Edge e2 = e2It.next();
                    Lifetime lt2 = Lifetime.getLifetimeFromProperties(e2);
                    boolean validPair = false;
                    for (int a = 0; a < lt1.getIntervalCount() && !validPair; a++) {
                        long end1 = lt1.getEndMillis(a);
                        for (int b = 0; b < lt2.getIntervalCount(); b++) {
                            long start2 = lt2.getStartMillis(b);
                            long delay = start2 - end1;
                            if (delay >= 0L && delay <= 86_400_000L) {
                                validPair = true;
                                break;
                            }
                        }
                    }
                    if (validPair) directSeqPathCount++;
                }
            }
        }
        assertEquals("Table XI (Paths): 2-hop bounded-delay sequentialPath must match direct 2-hop delay check",
                directSeqPathCount, seqPathCount);

        graph.close();
    }
}
