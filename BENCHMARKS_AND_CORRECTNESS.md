# T-TinkerPop Journal Benchmarks & Cross-Engine Correctness Verification

This document provides the complete reproducibility guide, empirical results, and **column-by-column cross-engine correctness/parity verification** for all benchmarks and experimental figures reported in Section VI of the T-TinkerPop journal paper (`journal.tex`).

---

## 1. Paper Evaluation Overview & Artifact Mapping

The paper evaluates the T-TinkerPop execution engine across three dataset families (Table 5 / `tab:dataset-stats`):
- **SF10 (LDBC)**: Homogeneous `Person-knows-Person` social network (70,800 vertices; 10K to 2.3M edges).
- **Flight Single File (OpenSky)**: Real-world air traffic network over 1 month (1,338 to 10,464 airports; 10K to 1,114,885 flights).
- **Flight Multi File (OpenSky)**: Multi-year historical flight stream with high edge multiplicity (3,222 to 18,189 airports; 46K to 67,455,707 flights).

### Correspondence with `journal.tex` Experiments

| Paper Artifact | LaTeX Label | Evaluated Benchmark Class | Verification Test Suite |
| :--- | :--- | :--- | :--- |
| **Table 5** | `tab:dataset-stats` | Ingestion & Dataset Sizing | [`DatasetResolver.java`](src/main/java/com/temporal/gremlin/util/DatasetResolver.java) |
| **Figures 1 & 2** | `fig:translation-overhead`, `fig:native-vs-exec-combined` | [`TranslatorVsNativeBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TranslatorVsNativeBenchmark.java) | [`CorrectnessDemo.java`](src/main/java/com/temporal/gremlin/demo/CorrectnessDemo.java) |
| **Table 6** | `tab:chronograph-comparison` | [`ChronoGraphBenchmark.java`](src/main/java/com/temporal/gremlin/bench/ChronoGraphBenchmark.java) | [`ChronoGraphParityTest.java`](src/test/java/com/temporal/gremlin/ChronoGraphParityTest.java) |
| **Table 7** | `tab:index-selectivity-tradeoff` | [`IndexSelectivityTradeoffBenchmark.java`](src/main/java/com/temporal/gremlin/bench/IndexSelectivityTradeoffBenchmark.java) | [`PaperBenchmarksCorrectnessTest.java`](src/test/java/com/temporal/gremlin/PaperBenchmarksCorrectnessTest.java) |
| **Figure 4** | `fig:path-semantics` | [`TemporalPathSemanticsBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPathSemanticsBenchmark.java) | [`PaperBenchmarksCorrectnessTest.java`](src/test/java/com/temporal/gremlin/PaperBenchmarksCorrectnessTest.java) |
| **Table 8** | `tab:representation-ablation-chronograph` | [`RepresentationAblationBenchmark.java`](src/main/java/com/temporal/gremlin/bench/RepresentationAblationBenchmark.java) | [`RepresentationAblationTest.java`](src/test/java/com/temporal/gremlin/RepresentationAblationTest.java) |
| **Table 9** | `tab:multi-interval-chronograph` | [`MultiIntervalStressBenchmark.java`](src/main/java/com/temporal/gremlin/bench/MultiIntervalStressBenchmark.java) | [`MultiIntervalStressTest.java`](src/test/java/com/temporal/gremlin/MultiIntervalStressTest.java) |
| **Table 10** | `tab:temporal-pagerank-chronograph` | [`TemporalPageRankBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPageRankBenchmark.java) | [`TemporalPageRankTest.java`](src/test/java/com/temporal/gremlin/TemporalPageRankTest.java) |
| **Table 11** | `tab:ttinkerpop-unique-scaling` | [`TTinkerPopCapabilitiesScalingBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TTinkerPopCapabilitiesScalingBenchmark.java) | [`PaperBenchmarksCorrectnessTest.java`](src/test/java/com/temporal/gremlin/PaperBenchmarksCorrectnessTest.java) |

---

## 2. Automated Correctness & Cross-Engine Parity Suite (`mvn test`)

Every benchmark table and column in the journal paper is backed by automated JUnit correctness tests in `src/test/java/com/temporal/gremlin/` that verify **exact result-set, match-count, and score parity** across execution modes (and formally validate expected semantic differences where columns implement distinct mathematical definitions).

Run the entire verification suite with:

```bash
mvn test
# or via the unified runner:
./bench.sh --mode=test --test-suite=all
```

### Test Classes Overview (17 Tests, 0 Failures, 0 Errors)

| Test Suite | Tests | Purpose |
| :--- | :---: | :--- |
| [`PaperBenchmarksCorrectnessTest.java`](src/test/java/com/temporal/gremlin/PaperBenchmarksCorrectnessTest.java) | 6 | End-to-end column-by-column parity verification for **every benchmark table in the paper** (*Tables VI, VII, VIII, IX, X, and XI*). |
| [`ChronoGraphParityTest.java`](src/test/java/com/temporal/gremlin/ChronoGraphParityTest.java) | 5 | Validates all 13 Allen interval relations (`ANY` and `ALL`), multi-interval consolidation, OpenSky `temporalBefore` parity across all 5 execution modes, and `tbfs` parity against ChronoGraph (`TemporalBreadthFirstSearch`). |
| [`TemporalPageRankTest.java`](src/test/java/com/temporal/gremlin/TemporalPageRankTest.java) | 2 | Validates causal vs. anti-causal rank propagation and exact per-vertex score parity (`1e-12` tolerance) between T-TinkerPop (`g.V().temporalPageRank()`) and ChronoGraph event extraction (`ChronoEdge.getEvents()`). |
| [`MultiIntervalStressTest.java`](src/test/java/com/temporal/gremlin/MultiIntervalStressTest.java) | 2 | Validates $O(1)$ bounding-box fast-reject + $O(k \log k)$ binary plane-sweep (`ANY` and `ALL` quantifiers) against ChronoGraph (`CG NatRel`, `CG Loop`) and naive $O(k^2)$ Cartesian evaluation for $k \in \{1, 2, 5, 10\}$. |
| [`RepresentationAblationTest.java`](src/test/java/com/temporal/gremlin/RepresentationAblationTest.java) | 2 | Validates 100% match-count parity across Dynamic ISO-8601 String Parsing, ChronoGraph `TimePeriod` Event Trees, T-TinkerPop Gremlin Traversal, and T-TinkerPop Direct `long[]` Sweep. |

---

## 3. Empirical Results & Detailed Parity Analysis by Paper Artifact

### Table 6: Comparison with ChronoGraph on OpenSky (`tab:chronograph-comparison`)
- **Class**: [`ChronoGraphBenchmark.java`](src/main/java/com/temporal/gremlin/bench/ChronoGraphBenchmark.java)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableVI_ChronoGraphBenchmarkAllColumnsParity` & `ChronoGraphParityTest`

#### Empirical Paper Results (`journal.tex` Table 6)

| Dataset (Flights / Routes $|E|$) | CG `tBFS` (ms) | TPop `tBFS` (ms) | `tBFS` Speedup | CG Loop (ms) | CG NatRel (ms) | TPop Direct (ms) | TPop Unindexed (ms) | TPop Indexed (ms) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **10k** (5,873 routes) | 1.44 | **0.18** | **8.1x** | 0.37 | 0.25 | **0.20** | 0.28 | 0.50 |
| **50k** (18,673 routes) | 12.16 | **1.85** | **6.6x** | 1.38 | 1.18 | **0.93** | 1.54 | 1.88 |
| **100k** (29,150 routes) | 30.89 | **3.50** | **8.8x** | 3.24 | 2.13 | **1.67** | 2.52 | 3.55 |
| **500k** (82,999 routes) | 277.01 | **21.62** | **12.8x** | 28.11 | 19.65 | **8.38** | 13.79 | 16.01 |
| **1.1M** (136,233 routes) | 650.90 | **42.64** | **15.3x** | 62.50 | 47.07 | **24.30** | 39.04 | 52.45 |

- **A. Primitive Operator Scan (`temporalBefore`, 5 Columns)**:
  - Columns: `CG Loop`, `CG NatRel`, `TPop Direct`, `TPop Unindexed` (`TemporalGraphStepStrategy`), `TPop Indexed` (`TemporalIndexOptimizationStrategy` + `TemporalTinkerIndex`).
  - **Verification**: All **5 columns** return the **exact same edge match count** (`cgLoopCount == cgNatRelCount == tpopDirectCount == tpopUnindexedCount == tpopIndexedCount`) on the OpenSky flight network.
- **B. Macro-Benchmark (`tbfs` Earliest-Departure Reachability, 2 Columns)**:
  - Columns: `ChronoGraph` (`TemporalBreadthFirstSearch`) vs. `T-TinkerPop` (`g.V(s).tbfs(EARLIEST_DEPARTURE, t0)`).
  - **Expected Semantic Difference & Exact Rule Parity**:
    1. **Boundary & Multi-Metric Semantics**: Inspection of ChronoGraph's bytecode reveals two restrictions in ChronoGraph:
       - ChronoGraph invokes `edge.getEvents(gamma[u], TemporalRelation.isAfter, cotemporal)` where `isAfter` enforces a **strict inequality** ($s_{\text{next}} > f_{\text{prev}}$), excluding zero-delay `meets` transfers ($s_{\text{next}} = f_{\text{prev}}$).
       - ChronoGraph stores a single `TimePeriod(s, f)` per vertex in `gamma[v]` with `orderByStart = true`, updating `gamma[v]` only when a flight has an earlier departure ($s_{\text{new}} < s_{\text{old}}$). If a faster flight departs slightly later ($s_2 > s_1$) but arrives earlier ($f_2 < f_1$), ChronoGraph discards $(s_2, f_2)$ and misses downstream connections at $v$ in $[f_2, f_1)$.
       - By contrast, T-TinkerPop's `TemporalBfsStep` permits zero-delay `meets` connections ($s_{\text{next}} \ge f_{\text{prev}}$) and tracks `causalTime[v] = min(bestEnd)` separately from `gamma[v] = min(bestStart)`.
    2. **Verified Superset & Timestamp Dominance on OpenSky**: Every airport reached by ChronoGraph is reached by T-TinkerPop (`tpopReachedCodes.containsAll(cgReachedCodes)`), and for every reached airport $v$, T-TinkerPop's earliest departure satisfies $\text{tpop}(v) \le \text{cg}(v)$.
    3. **100% Vertex & Timestamp Equality under ChronoGraph's Restricted Rule**: When T-TinkerPop's `TinkerGraph` is evaluated using ChronoGraph's exact single-metric strict `isAfter` rule (`runTinkerPopWithChronoGraphStrictSingleMetricRule`), both engines produce **100% identical vertex sets and timestamps** across all hops (`assertEquals(cgTimestampMap, tpopUnderCgRule)`).

---

### Table 7: Index Selectivity Crossover & Ingestion Trade-off (`tab:index-selectivity-tradeoff`)
- **Class**: [`IndexSelectivityTradeoffBenchmark.java`](src/main/java/com/temporal/gremlin/bench/IndexSelectivityTradeoffBenchmark.java)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableVII_IndexSelectivityTradeoffAllColumnsParity`
- **Setup**: $500{,}000$ multi-interval edges ($k=3$, $1.5\text{M}$ intervals), evaluated on `temporalOverlaps`.
- **Ingestion Write Overhead**: Unindexed $= 198.25$\,ms vs. Indexed (`TemporalTinkerIndex`) $= 481.27$\,ms (**2.43x write overhead**).

#### Empirical Paper Results (`journal.tex` Table 7)

| Target Selectivity | Matched Edges | TPop Indexed (ms) | TPop Unindexed (ms) | TPop Direct (ms) | CG NatRel (ms) | CG Loop (ms) | Speedup (Idx vs Unidx) | Speedup (Idx vs CG) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **0.01%** | 70 | **0.15** | 93.55 | 50.25 | 150.10 | 171.75 | **623.7x** | **1000.7x** |
| **0.10%** | 519 | **2.25** | 95.88 | 54.68 | 155.34 | 164.08 | **42.6x** | **69.0x** |
| **1.00%** | 5,008 | **10.64** | 89.26 | 49.40 | 134.81 | 163.04 | **8.4x** | **12.7x** |
| **5.00%** | 24,873 | **46.58** | 94.78 | 49.50 | 135.43 | 168.40 | **2.0x** | **2.9x** |
| **10.00%** | 49,935 | **63.16** | 77.79 | **48.55** | 143.90 | 169.34 | **1.2x** | **2.3x** |
| **25.00%** | 125,164 | 98.71 | **77.26** | **51.99** | 150.32 | 180.58 | 0.78x | 1.5x |
| **50.00%** | 250,369 | 167.21 | **82.34** | **57.76** | 166.96 | 204.26 | 0.49x | 1.0x |
| **75.00%** | 375,313 | 227.64 | **89.56** | **63.26** | 192.69 | 226.44 | 0.39x | 0.85x |

- **Verification**: Across all 8 query selectivities on multi-interval edges ($k=3$), all **5 columns** return the **exact same match count**, and match counts are monotonically non-decreasing with window selectivity.

---

### Figure 4: Temporal Path Semantics (`fig:path-semantics`)
- **Class**: [`TemporalPathSemanticsBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPathSemanticsBenchmark.java)
- **Setup**: Evaluates Continuous, Pairwise-Continuous, and Sequential path traversal across depths 2 to 5 hops and limits 100, 1,000, 10,000 on Flight Single File (1.1M edges).

#### Empirical Paper Results (`journal.tex` Figure 4)

| Path Semantics | Depth (Hops) | Limit 100 (ms) | Limit 1,000 (ms) | Limit 10,000 (ms) | Scaling Characteristics |
| :--- | :---: | ---: | ---: | ---: | :--- |
| **Sequential** (`sequentialPath`) | 2 | 7 | 5 | 15 | Highly efficient, largely insensitive to limit; less than $1.7\times$ slowdown from 2 to 5 hops ($15 \to 24$\,ms at 10k limit). |
| | 3 | 8 | 8 | 14 | |
| | 4 | 12 | 11 | 23 | |
| | 5 | 15 | 14 | 24 | |
| **Pairwise-Continuous** (`pairwiseContinuousPath`) | 2 | 40 | 49 | 141 | Modest overhead over sequential; interactive scaling with depth ($141 \to 382$\,ms at depth 5). |
| | 3 | 67 | 72 | 194 | |
| | 4 | 93 | 115 | 342 | |
| | 5 | 155 | 171 | 382 | |
| **Continuous** (`continuousPath`) | 2 | 486 | 2,024 | 57,142 | Dominated by depth due to global temporal intersection; valid window narrows, driving extensive engine backtracking. |
| | 3 | 14,353 | 17,527 | 89,473 | |
| | 4 | 25,884 | 32,043 | 134,257 | |
| | 5 | 45,594 | 52,841 | 196,269 | |

---

### Table 8: Representation Ablation (`tab:representation-ablation-chronograph`)
- **Class**: [`RepresentationAblationBenchmark.java`](src/main/java/com/temporal/gremlin/bench/RepresentationAblationBenchmark.java)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableVIII_RepresentationAblationAllColumnsParity` & `RepresentationAblationTest`

#### Empirical Paper Results (`journal.tex` Table 8)

| Events | Operator | String (ms) | CG (ms) | TPop Trav. (ms) | TPop Direct (ms) | Speedup vs String |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| **50K** | `temporalBefore` | 86.24 | 1.48 | 0.90 | **0.57** | **96.1x** |
| | `temporalOverlaps` | 87.34 | 1.98 | 1.07 | **0.59** | **81.6x** |
| | `during` | 84.38 | 1.45 | 0.92 | **0.59** | **91.6x** |
| **100K** | `temporalBefore` | 171.75 | 2.57 | 2.02 | **1.15** | **85.0x** |
| | `temporalOverlaps` | 167.27 | 3.74 | 2.02 | **1.15** | **83.0x** |
| | `during` | 167.61 | 2.86 | 2.04 | **1.17** | **82.0x** |
| **500K** | `temporalBefore` | 863.48 | 21.84 | 15.27 | **8.70** | **56.6x** |
| | `temporalOverlaps` | 848.87 | 27.60 | 14.37 | **9.35** | **59.1x** |
| | `during` | 863.16 | 22.91 | 15.32 | **9.47** | **56.3x** |
| **1M** | `temporalBefore` | 1719.29 | 42.34 | 31.83 | **19.44** | **54.0x** |
| | `temporalOverlaps` | 1697.81 | 52.34 | 31.00 | **19.17** | **54.8x** |
| | `during` | 1743.54 | 42.73 | 29.74 | **19.43** | **58.6x** |

- **Verification**: All **4 columns** return **100% identical match counts** for every operator (`temporalBefore`, `temporalOverlaps`, and `during`).

---

### Table 9: Multi-Interval Traversal Scalability (`tab:multi-interval-chronograph`)
- **Class**: [`MultiIntervalStressBenchmark.java`](src/main/java/com/temporal/gremlin/bench/MultiIntervalStressBenchmark.java)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableIX_MultiIntervalStressAllColumnsParity` & `MultiIntervalStressTest`

#### Empirical Paper Results (`journal.tex` Table 9)

| Scale $|E|$ | $k$ | TPop ANY (ms) | TPop ALL (ms) | TPop Sweep (ms) | CG NatRel (ms) | CG Loop (ms) | Naive $O(k^2)$ (ms) |
| :--- | :---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **100K** | 1 | 18.19 | 21.56 | **10.51** | 9.83 | 13.93 | 13.79 |
| | 2 | 21.38 | 22.70 | **13.12** | 36.37 | 37.08 | 19.40 |
| | 5 | 30.96 | 37.95 | **14.14** | 57.84 | 58.89 | 30.95 |
| | 10 | 31.54 | 42.14 | **21.17** | 95.77 | 82.89 | 50.89 |
| **500K** | 1 | 110.22 | 110.15 | **70.69** | 51.15 | 78.36 | 78.36 |
| | 2 | 107.38 | 107.99 | **67.32** | 140.69 | 158.16 | 95.01 |
| | 5 | 111.05 | 111.29 | **72.43** | 263.25 | 247.43 | 141.44 |
| | 10 | 111.46 | 112.24 | **70.02** | 429.70 | 432.49 | 225.28 |
| **1M** | 1 | 222.27 | 218.72 | **130.01** | 129.80 | 207.86 | 141.43 |
| | 2 | 205.26 | 219.52 | **133.36** | 305.31 | 339.79 | 182.48 |
| | 5 | 236.48 | 219.45 | **136.83** | 533.01 | 556.74 | 266.96 |
| | 10 | 225.09 | 223.47 | **136.95** | 855.07 | 886.82 | 438.44 |

- **Verification & Expected Semantic Relationships**:
  1. **100% Parity Across All 5 `ANY` Columns**: For every $k \in \{1, 2, 5, 10\}$, `TPop ANY`, `TPop Sweep`, `CG NatRel`, `CG Loop`, and `Naive O(k^2)` return the **exact same match count**.
  2. **100% Parity for `TPop ALL` vs. Direct Holistic Evaluation**: `TPop ALL` matches direct holistic evaluation 100%.
  3. **Expected Difference Between `TPop ALL` and `TPop ANY` for $k > 1$**:
     - When $k = 1$, holistic (`ALL`) and existential (`ANY`) semantics coincide (`tpopAll == tpopAny`).
     - When $k > 1$, `ANY` checks whether *there exists* a sub-interval pair satisfying `overlaps`, whereas `ALL` evaluates holistic whole-lifetime bounds ($\min(X) < \min(Y) \land \max(X) < \max(Y) \land \tau(X) \cap \tau(Y) \neq \emptyset$).

---

### Table 10: Temporal PageRank on OpenSky (`tab:temporal-pagerank-chronograph`)
- **Class**: [`TemporalPageRankBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPageRankBenchmark.java)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableX_TemporalPageRankAllColumnsParity` & `TemporalPageRankTest`

#### Empirical Paper Results (`journal.tex` Table 10)

| Flights (Events) | Routes $|E|$ | Airports $|V|$ | TPop TPR (ms) | CG Extract+TPR (ms) | Static PR (ms) | Speedup vs Static |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **50,000** | 18,673 | 3,647 | **19.99** | 27.88 | 188.85 | **9.4x** |
| **100,000** | 29,150 | 5,184 | **24.93** | 36.76 | 133.53 | **5.4x** |
| **250,000** | 53,901 | 7,481 | **61.75** | 88.84 | 275.67 | **4.5x** |
| **500,000** | 82,999 | 8,906 | **121.10** | 178.92 | 307.57 | **2.5x** |
| **1,000,000** | 128,748 | 10,310 | **244.04** | 367.15 | 446.59 | **1.8x** |

- **Verification**:
  1. `TPop TPR`, `CG Extract+TPR`, and `Static PR` all rank the exact same set of $|V|$ airports.
  2. For every airport $v \in V$, `TPop TPR` and `CG Extract+TPR` produce identical floating-point PageRank scores within `1e-12` tolerance.
  3. Static PageRank collapses the temporal graph into a static projection and iterates 20 synchronous matrix multiplications; its scores intentionally differ from causal Temporal PageRank while ranking the same $|V|$ vertices.

---

### Table 11: Scaling of T-TinkerPop-Unique Capabilities (`tab:ttinkerpop-unique-scaling`)
- **Class**: [`TTinkerPopCapabilitiesScalingBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TTinkerPopCapabilitiesScalingBenchmark.java)
- **Validated by**: `PaperBenchmarksCorrectnessTest#testTableXI_TTinkerPopUniqueCapabilitiesAllColumnsParity`

#### Empirical Paper Results (`journal.tex` Table 11)

| Edges $|E|$ | Atomic (ms) | Convex $[\alpha,\beta]$ (ms) | $\Pi(4,4)$ (ms) | `.or()` (ms) | Speedup vs `.or()` | `atTime` (ms) | `window` (ms) | Manual (ms) | `tProp` (ms) | EA (ms) | ED (ms) | LD (ms) | LA (ms) | `seqPath` (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **10,000** | 1.36 | **1.41** | 1.14 | 5.42 | **3.83x** | 1.31 | **1.12** | 1.63 | 0.31 | 2.04 | 2.20 | 2.51 | 2.52 | 2.94 |
| **50,000** | 7.46 | **7.86** | 5.92 | 30.93 | **3.94x** | 2.15 | **4.12** | 6.35 | 0.65 | 10.30 | 10.79 | 9.95 | 10.73 | 1.70 |
| **100,000** | 26.28 | **26.31** | 20.05 | 80.43 | **3.06x** | 9.09 | **13.57** | 21.66 | 1.64 | 28.68 | 31.67 | 39.57 | 41.70 | 1.97 |
| **500,000** | 131.54 | **147.22** | 114.32 | 382.74 | **2.60x** | 57.94 | **86.59** | 125.20 | 12.54 | 280.99 | 296.72 | 291.45 | 304.17 | 1.81 |
| **1,000,000** | 261.24 | **294.39** | 228.53 | 767.39 | **2.61x** | 127.50 | **206.42** | 311.53 | 24.60 | 745.03 | 782.62 | 707.14 | 718.62 | 1.82 |

- **Verification Across Sub-Suites**:
  - **Ligozat**: `Convex [precedes, overlaps]` returns the exact same count as `.or(before, meets, overlaps)`; $\Pi(4,4)$ matches direct `GeneralizedRelation.between` evaluation 100%.
  - **Historical Views**: `window(t1, t2).out("event")` returns the exact same hop count as the 3-step `Manual` filter chain; `atTime(t)` returns the exact same hop count as the manual instant filter chain.
  - **Temporal Path Targets**: Forward reachability targets `EA` and `ED` reach the exact same vertex set, with $\text{ED}(v) < \text{EA}(v)$ for every reachable vertex $v$; backward deadline targets `LD` and `LA` reach the exact same vertex set, with $\text{LD}(v) < \text{LA}(v)$; 2-hop bounded-delay `sequentialPath` matches direct 2-hop delay check 100%.

---

### Figures 1 & 2: Translator vs. Native T-Gremlin (`fig:translation-overhead`, `fig:native-vs-exec-combined`)
- **Class**: [`TranslatorVsNativeBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TranslatorVsNativeBenchmark.java)
- **Validated by**: `TranslatorVsNativeBenchmark#verifyOperatorParity` & [`CorrectnessDemo.java`](src/main/java/com/temporal/gremlin/demo/CorrectnessDemo.java)
- **Key Findings & Parity**:
  - Evaluates **all 13 Allen interval relations** (covering the 4 representative operators plotted in Figure 2 plus the remaining 9 operators referenced in Footnote 2 of `journal.tex`).
  - **100% Exact Parity**: `Native` (`g.E().temporalX(ref)`), `Execute` (translated `where(P.lt/gt/eq).by('startTime'/'endTime')` bytecode), and `Direct` (`has()` filters) return the **exact same edge match count** for all 13 Allen operators.
  - Translation phase overhead remains below $0.1$\,ms across all datasets, confirming the compilation step is negligible relative to query execution.
  - Native temporal execution maintains a consistent $1.5\times$ to $3.0\times$ speedup over translated traversals across SF10, Flight Single File, and Flight Multi File by eliminating traverser wrapper allocations, object boxing, and generic property lookups.

---

## 4. Running Benchmarks: Unified Suite & CLI

You can run benchmarks using the **unified benchmark suite runner (`./bench.sh`)**, through an **interactive CLI menu**, or directly via Maven.

### A. Interactive CLI Menu (`./bench.sh`)

Simply run:

```bash
./bench.sh
```

This opens an interactive menu that lets you choose:
1. **What to run**: Paper Reproduction Suite (Default: Tables VI-XI & Figures), Grouped Suites (Macro/Micro/Translator), Single Benchmarks, or Correctness Tests.
2. **Dataset Configuration**: Default OpenSky flight dataset or any custom CSV path.
3. **Workload Scale**: Full Journal Paper Scale (Default) or Quick Smoke-Test Scale (~30s).
4. **Output Destination**: Console + Save to `results/` (`.txt` and `.md` reports), custom file path, or console only.

> **Tip:** Hitting `<Enter>` on each prompt selects the sensible default (Full Paper Reproduction Suite with OpenSky dataset saving to `results/`).

### B. Command-Line Arguments Mode

Run non-interactively with CLI arguments:

```bash
# 1. Run full paper benchmark suite (Tables VI–XI & Figures) with default settings
./bench.sh --mode=bench --suite=paper

# 2. Fast smoke-test evaluation across paper benchmarks (~30 seconds)
./bench.sh --mode=bench --suite=paper --scale=quick

# 3. Run all 17 automated cross-engine parity tests
./bench.sh --mode=test --test-suite=all

# 4. Run full correctness verification first, followed by benchmarks
./bench.sh --mode=both --suite=paper

# 5. Run a single specific benchmark with custom dataset and output path
./bench.sh --target=chronograph --dataset=/path/to/my_flights.csv --output=results/custom_run.txt

# 6. Run Figure 4 Temporal Path Semantics benchmark
./bench.sh --target=path-semantics --scale=quick

# 7. List all available benchmark groups, tasks, tests, and demos
./bench.sh --list
```

### C. Individual Maven Class Invocations (Backward Compatible)

Existing single-class Maven executions continue to work identically:

```bash
# Table 6: OpenSky ChronoGraph vs. T-TinkerPop (tBFS & Operators)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.ChronoGraphBenchmark"

# Table 7: Index Selectivity Crossover & Ingestion Overhead
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.IndexSelectivityTradeoffBenchmark"

# Figure 4: Temporal Path Semantics (Continuous, Pairwise, Sequential)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalPathSemanticsBenchmark"

# Table 8: Representation Ablation
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.RepresentationAblationBenchmark"

# Table 9: Multi-Interval Traversal Scalability
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.MultiIntervalStressBenchmark"

# Table 10: Temporal PageRank on OpenSky
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalPageRankBenchmark"

# Table 11: T-TinkerPop-Unique Capabilities Scaling
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TTinkerPopCapabilitiesScalingBenchmark"
```
