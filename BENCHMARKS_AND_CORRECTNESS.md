# T-TinkerPop Journal Benchmarks & Cross-Engine Correctness Verification

This document provides the complete reproducibility guide, empirical results, and **column-by-column cross-engine correctness/parity verification** for all benchmarks reported in the T-TinkerPop journal paper (*Tables V–XI*).

---

## 1. Automated Correctness & Cross-Engine Parity Suite (`mvn test`)

Every benchmark table and column in the journal paper is backed by automated JUnit correctness tests in `src/test/java/com/temporal/gremlin/` that verify **exact result-set, match-count, and score parity** across execution modes (and formally validate expected semantic differences where columns implement distinct mathematical definitions).

Run the entire verification suite with:

```bash
mvn test
```

### Test Classes Overview (17 Tests, 0 Failures, 0 Errors)

| Test Suite | Tests | Purpose |
| :--- | :---: | :--- |
| [`PaperBenchmarksCorrectnessTest.java`](src/test/java/com/temporal/gremlin/PaperBenchmarksCorrectnessTest.java) | 6 | End-to-end column-by-column parity verification for **every benchmark table in the paper** (*Tables V, VI, VIII, IX, X, and XI*). |
| [`ChronoGraphParityTest.java`](src/test/java/com/temporal/gremlin/ChronoGraphParityTest.java) | 5 | Validates all 13 Allen interval relations (`ANY` and `ALL`), multi-interval consolidation, OpenSky `temporalBefore` parity across all 5 execution modes, and `tbfs` parity against ChronoGraph (`TemporalBreadthFirstSearch`). |
| [`TemporalPageRankTest.java`](src/test/java/com/temporal/gremlin/TemporalPageRankTest.java) | 2 | Validates causal vs. anti-causal rank propagation and exact per-vertex score parity (`1e-12` tolerance) between T-TinkerPop (`g.V().temporalPageRank()`) and ChronoGraph event extraction (`ChronoEdge.getEvents()`). |
| [`MultiIntervalStressTest.java`](src/test/java/com/temporal/gremlin/MultiIntervalStressTest.java) | 2 | Validates $O(1)$ bounding-box fast-reject + $O(k \log k)$ binary plane-sweep (`ANY` and `ALL` quantifiers) against ChronoGraph (`CG NatRel`, `CG Loop`) and naive $O(k^2)$ Cartesian evaluation for $k \in \{1, 2, 5, 10\}$. |
| [`RepresentationAblationTest.java`](src/test/java/com/temporal/gremlin/RepresentationAblationTest.java) | 2 | Validates 100% match-count parity across Dynamic ISO-8601 String Parsing, ChronoGraph `TimePeriod` Event Trees, T-TinkerPop Gremlin Traversal, and T-TinkerPop Direct `long[]` Sweep. |

---

## 2. Column-by-Column Parity & Semantic Analysis by Paper Table

### Table V: Comparison with ChronoGraph on OpenSky (`ChronoGraphBenchmark.java`)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableV_ChronoGraphBenchmarkAllColumnsParity` & `ChronoGraphParityTest`
- **A. Primitive Operator Scan (`temporalBefore`, 5 Columns)**:
  - Columns: `CG Loop`, `CG NatRel`, `TPop Direct`, `TPop Unindexed` (`TemporalGraphStepStrategy`), `TPop Indexed` (`TemporalIndexOptimizationStrategy` + `TemporalTinkerIndex`).
  - **Verification**: All **5 columns** return the **exact same edge match count** (`cgLoopCount == cgNatRelCount == tpopDirectCount == tpopUnindexedCount == tpopIndexedCount`) on the OpenSky flight network.
- **B. Macro-Benchmark (`tbfs` Earliest-Departure Reachability, 2 Columns)**:
  - Columns: `ChronoGraph` (`TemporalBreadthFirstSearch`) vs. `T-TinkerPop` (`g.V(s).tbfs(EARLIEST_DEPARTURE, t0)`).
  - **Expected Semantic Difference & Exact Rule Parity**:
    1. **Boundary & Multi-Metric Semantics**: Inspection of ChronoGraph's bytecode (`TemporalBreadthFirstSearch.class` and `TimePeriod.class`) reveals two restrictions in ChronoGraph:
       - ChronoGraph invokes `edge.getEvents(gamma[u], TemporalRelation.isAfter, cotemporal)` where `isAfter` enforces a **strict inequality** ($s_{\text{next}} > f_{\text{prev}}$), excluding zero-delay `meets` transfers ($s_{\text{next}} = f_{\text{prev}}$).
       - ChronoGraph stores a single `TimePeriod(s, f)` per vertex in `gamma[v]` with `orderByStart = true`, updating `gamma[v]` only when a flight has an earlier departure ($s_{\text{new}} < s_{\text{old}}$). If a faster flight departs slightly later ($s_2 > s_1$) but arrives earlier ($f_2 < f_1$), ChronoGraph discards $(s_2, f_2)$ and misses downstream connections at $v$ in $[f_2, f_1)$.
       - By contrast, T-TinkerPop's `TemporalBfsStep` permits zero-delay `meets` connections ($s_{\text{next}} \ge f_{\text{prev}}$) and tracks `causalTime[v] = min(bestEnd)` separately from `gamma[v] = min(bestStart)`.
    2. **Verified Superset & Timestamp Dominance on OpenSky**: Every airport reached by ChronoGraph is reached by T-TinkerPop (`tpopReachedCodes.containsAll(cgReachedCodes)`), and for every reached airport $v$, T-TinkerPop's earliest departure satisfies $\text{tpop}(v) \le \text{cg}(v)$ (strictly earlier when a 2-hop fast/meets path beats a 1-hop flight).
    3. **100% Vertex & Timestamp Equality under ChronoGraph's Restricted Rule**: When T-TinkerPop's `TinkerGraph` is evaluated using ChronoGraph's exact single-metric strict `isAfter` rule (`runTinkerPopWithChronoGraphStrictSingleMetricRule`), both engines produce **100% identical vertex sets and timestamps** across all hops (`assertEquals(cgTimestampMap, tpopUnderCgRule)`). Moreover, on causal DAGs without `meets` boundary ties or overtaking flights (`ChronoGraphParityTest#testTemporalBfsParityWithChronoGraphOnCausalChainAndDAG`), both engines produce 100% identical maps out-of-the-box.

---

### Table VI: Index Selectivity Crossover & Write-Amplification Trade-off (`IndexSelectivityTradeoffBenchmark.java`)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableVI_IndexSelectivityTradeoffAllColumnsParity`
- **Columns Validated (5 Columns across 8 Selectivities: `0.01%`, `0.1%`, `0.5%`, `1%`, `5%`, `10%`, `25%`, `75%`)**:
  - `TPop Indexed`, `TPop Unindexed`, `TPop Direct`, `CG NatRel`, `CG Loop`.
- **Verification**: Across all 8 query selectivities on multi-interval edges ($k=3$), all **5 columns** return the **exact same match count**, and match counts are monotonically non-decreasing with window selectivity.

---

### Table VIII: Representation Ablation (`RepresentationAblationBenchmark.java`)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableVIII_RepresentationAblationAllColumnsParity` & `RepresentationAblationTest`
- **Columns Validated (4 Columns across 3 Operators: `temporalBefore`, `temporalOverlaps`, `during`)**:
  - `String (ms)` (Dynamic ISO-8601 String Parsing), `CG (ms)` (ChronoGraph `TreeSet<ChronoEdgeEvent>`), `TPop Trav. (ms)` (Gremlin Traversal over normalized `long[]`), `TPop Direct (ms)` (Direct sweep over normalized `long[]`).
- **Verification**: All **4 columns** return **100% identical match counts** for every operator (`temporalBefore`, `temporalOverlaps`, and `during`).

---

### Table IX: Multi-Interval Traversal Scalability (`MultiIntervalStressBenchmark.java`)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableIX_MultiIntervalStressAllColumnsParity` & `MultiIntervalStressTest`
- **Columns Validated (6 Columns across $k \in \{1, 2, 5, 10\}$ Disjoint Sub-Intervals per Edge)**:
  - Existential (`ANY`) columns (5): `TPop ANY`, `TPop Sweep`, `CG NatRel`, `CG Loop`, `Naive O(k^2)`.
  - Holistic (`ALL`) column (1): `TPop ALL`.
- **Verification & Expected `ALL` vs. `ANY` Relationship**:
  1. **100% Parity Across All 5 `ANY` Columns**: For every $k \in \{1, 2, 5, 10\}$, `TPop ANY`, `TPop Sweep`, `CG NatRel`, `CG Loop`, and `Naive O(k^2)` return the **exact same match count**.
  2. **100% Parity for `TPop ALL` vs. Direct Holistic Evaluation**: `TPop ALL` matches direct holistic evaluation (`Temporal.overlaps.test(lt, refLt, Quantifier.ALL)`) 100%.
  3. **Expected Difference Between `TPop ALL` and `TPop ANY` for $k > 1$**:
     - When $k = 1$, a lifetime has a single interval, so holistic (`ALL`) and existential (`ANY`) semantics coincide (`tpopAll == tpopAny`).
     - When $k > 1$, `ANY` checks whether *there exists* a sub-interval pair $(x_i \in X, y_j \in Y)$ satisfying `overlaps(x_i, y_j)` ($s_{x_i} < s_{y_j} < e_{x_i} < e_{y_j}$), whereas `ALL` evaluates holistic whole-lifetime bounds ($\min(X) < \min(Y) \land \max(X) < \max(Y) \land \tau(X) \cap \tau(Y) \neq \emptyset$). Consequently, for $k > 1$, `TPop ALL` and `TPop ANY` intentionally return different match counts.

---

### Table X: Temporal PageRank Benchmark (`TemporalPageRankBenchmark.java`)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableX_TemporalPageRankAllColumnsParity` & `TemporalPageRankTest`
- **Columns Validated (3 Columns)**:
  - `TPop TPR` (`g.V().temporalPageRank()`), `CG Extract+TPR` (`ChronoEdge.getEvents()` + Rozenshtein & Gionis streaming TPR), `Static PR` (20-iteration `PageRankVertexProgram` on `GraphComputer`).
- **Verification & Expected Score Difference**:
  1. **100% Vertex Count Parity across All 3 Columns**: `TPop TPR`, `CG Extract+TPR`, and `Static PR` all rank the exact same set of $|V|$ airports.
  2. **100% Per-Vertex Score Parity (`1e-12` tolerance) between `TPop TPR` and `CG Extract+TPR`**: For every airport $v \in V$, `TPop TPR` and `CG Extract+TPR` produce identical floating-point PageRank scores (`assertEquals(cgScore, tpopScore, 1e-12)`).
  3. **Expected Score Difference with `Static PR`**: Static PageRank collapses the temporal flight stream into a time-aggregated graph and iterates 20 synchronous matrix-vector multiplications, ignoring causal time ordering among flights; thus its per-vertex scores intentionally differ from causal Temporal PageRank while ranking the same $|V|$ vertices.

---

### Table XI: Scaling of T-TinkerPop-Unique Capabilities (`TTinkerPopCapabilitiesScalingBenchmark.java`)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableXI_TTinkerPopUniqueCapabilitiesAllColumnsParity`
- **Suite A — Ligozat Generalized $(p, q)$-Calculus (4 Columns)**:
  - `Convex [precedes, overlaps]` (`TemporalP.between(precedes, overlaps, ref).any()`) returns the **exact same count** as `Multi-Branch .or(__.temporalBefore(ref), __.temporalMeets(ref), __.temporalOverlaps(ref))`.
  - `Atomic` (`temporalOverlaps`) is verified to be a subset of `Convex [precedes, overlaps]`.
  - `Pi(4,4)` (`TemporalP.generalized(refMulti2, rel44)`) returns the **exact same count** as direct `GeneralizedRelation.between(e, refMulti2)` evaluation.
- **Suite B — Historical Subgraph Views (4 Columns)**:
  - `window(t1, t2).out("event")` returns the **exact same hop count** as the 3-step `Manual` filter chain (`g.V().temporalIntersects(w).outE("event").temporalIntersects(w).inV().temporalIntersects(w)`).
  - `atTime(t).out("event")` returns the **exact same hop count** as the manual instant filter chain, and satisfies `atTime(t) <= window(t1, t2)` for $t \in [t_1, t_2)$.
  - `tProp` (`g.V().window(t1, t2).values("status")`) returns all active property versions in $[t_1, t_2)$ (`100%` of `"status"`) while filtering out future property versions (`0` for `"alert"`).
- **Suite C — Temporal Path Optimality Criteria (5 Columns)**:
  - Forward reachability targets `EARLIEST_ARRIVAL` (`EA`) and `EARLIEST_DEPARTURE` (`ED`) reach the **exact same vertex set**, with $\text{ED}(v) < \text{EA}(v)$ for every reachable vertex $v$.
  - Backward deadline targets `LATEST_DEPARTURE` (`LD`) and `LATEST_ARRIVAL` (`LA`) reach the **exact same vertex set**, with $\text{LD}(v) < \text{LA}(v)$ for every backward-reachable vertex $v$.
  - Bounded-delay 2-hop `sequentialPath(0L, 86_400_000L, "event")` returns the **exact same path count** as a direct 2-hop adjacency loop verifying $0 \le s_2 - e_1 \le 86{,}400{,}000\text{ ms}$.

---

## 3. Running All Paper Benchmarks

Each benchmark class in `src/main/java/com/temporal/gremlin/bench/` can be executed directly via Maven:

```bash
# 1. Table V: OpenSky ChronoGraph vs. T-TinkerPop (tBFS & temporalBefore across 5 execution modes)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.ChronoGraphBenchmark"

# 2. Table VI: Index Selectivity Crossover (0.01% to 75% selectivity + Ingestion Write Overhead)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.IndexSelectivityTradeoffBenchmark"

# 3. Table VIII: Representation Ablation (Dynamic String Parsing vs. ChronoGraph vs. Normalized long[])
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.RepresentationAblationBenchmark"

# 4. Table IX: Multi-Interval Stress Benchmark (k = 1, 2, 5, 10 across 50K to 1M edges)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.MultiIntervalStressBenchmark"

# 5. Table X: Temporal PageRank Benchmark (TPop TPR vs. ChronoGraph Extract+TPR vs. Static PR)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalPageRankBenchmark"

# 6. Table XI: T-TinkerPop-Unique Capabilities Scaling (Ligozat Calculus, Historical Views, Path Targets)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TTinkerPopCapabilitiesScalingBenchmark"
```
