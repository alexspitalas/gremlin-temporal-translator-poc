# T-TinkerPop Benchmark Suite & Cross-Engine Parity Verification

A unified, easy-to-use benchmarking and correctness verification suite for temporal graph queries on Apache TinkerPop Gremlin. This suite reproduces all empirical evaluations reported in Section VI of the **T-TinkerPop journal paper (`journal.tex`, *Tables VI–XI & Figures 1, 2, 4*)**, comparing T-TinkerPop against state-of-the-art baselines including ChronoGraph, unindexed scans, index-optimized interval trees, dynamic string parsing, and static graph algorithms.

The suite features an **interactive terminal CLI menu**, non-interactive command-line flags, configurable workload scales (from fast 30-second smoke-tests to 1M+ element paper-scale runs), flexible dataset targeting (default OpenSky flight network or custom CSVs), and automated report generation in both GitHub-flavored Markdown and plain text.

---

## Table of Contents

- [Quick Start](#quick-start)
  - [Interactive CLI Menu](#interactive-cli-menu)
  - [Common CLI Commands](#common-cli-commands)
  - [Prerequisites & Build](#prerequisites--build)
- [Benchmark Suites & Catalog](#benchmark-suites--catalog)
- [CLI Reference & Configuration](#cli-reference--configuration)
  - [Execution Modes](#execution-modes)
  - [Scale Modes](#scale-modes)
  - [Custom Datasets](#custom-datasets)
  - [Output & Reporting](#output--reporting)
- [Journal Paper Benchmarks (Tables VI–XI & Figures)](#journal-paper-benchmarks-tables-vixi--figures)
  - [Table 6: ChronoGraph Comparison on OpenSky (`ChronoGraphBenchmark`)](#table-6-chronograph-comparison-on-opensky)
  - [Table 7: Index Selectivity Crossover & Ingestion Overhead (`IndexSelectivityTradeoffBenchmark`)](#table-7-index-selectivity-crossover--ingestion-overhead)
  - [Figure 4: Temporal Path Semantics (`TemporalPathSemanticsBenchmark`)](#figure-4-temporal-path-semantics)
  - [Table 8: Representation Ablation (`RepresentationAblationBenchmark`)](#table-8-representation-ablation)
  - [Table 9: Multi-Interval Traversal Scalability (`MultiIntervalStressBenchmark`)](#table-9-multi-interval-traversal-scalability)
  - [Table 10: Temporal PageRank on OpenSky (`TemporalPageRankBenchmark`)](#table-10-temporal-pagerank-on-opensky)
  - [Table 11: T-TinkerPop-Unique Capabilities Scaling (`TTinkerPopCapabilitiesScalingBenchmark`)](#table-11-t-tinkerpop-unique-capabilities-scaling)
  - [Figures 1 & 2: Translation Overhead vs. Native Execution (`TranslatorVsNativeBenchmark`)](#figures-1--2-translation-overhead-vs-native-execution)
  - [Additional Benchmarks: Storage Representation](#additional-benchmarks-storage-representation)
- [Automated Cross-Engine Parity Verification (`mvn test`)](#automated-cross-engine-parity-verification-mvn-test)
- [Backward-Compatible Direct Invocations](#backward-compatible-direct-invocations)
- [Project Structure](#project-structure)

---

## Quick Start

### Interactive CLI Menu

The easiest way to explore and run the suite is through the interactive menu runner:

```bash
./bench.sh
# or via the root symlink:
./bench
```

Hitting `<Enter>` on each prompt selects the **sensible default** (Paper Reproduction Suite on the OpenSky dataset, saving dual Markdown and text reports to `results/`):

```text
==========================================================================================
  T-TinkerPop Interactive Benchmark & Correctness Suite
==========================================================================================
Select an execution mode:
  [1] Run Benchmark Suite (Default: Paper Suite Tables VI-XI & Figures)  [Default]
  [2] Run Correctness & Cross-Engine Parity Tests (JUnit 17 Tests)
  [3] Run Full Verification + Benchmarks (Both)
  [4] Run Interactive Demos (Translator, Apps, Continuous Path)
Enter choice [1]: 
```

### Common CLI Commands

Run non-interactively with command-line flags:

```bash
# 1. Fast smoke-test across all paper benchmarks (~30 seconds)
./bench.sh --mode=bench --suite=paper --scale=quick

# 2. Full paper reproduction suite (Tables VI–XI & Figures)
./bench.sh --mode=bench --suite=paper

# 3. Run all 17 automated cross-engine parity tests
./bench.sh --mode=test --test-suite=all

# 4. Run both correctness tests and paper benchmarks sequentially
./bench.sh --mode=both --suite=paper

# 5. Run a specific benchmark with custom dataset and custom output path
./bench.sh --target=chronograph --dataset=/path/to/my_flights.csv --output=results/custom_run.txt

# 6. Run Figure 4 Temporal Path Semantics benchmark
./bench.sh --target=path-semantics --scale=quick

# 7. List all available benchmark groups, individual tasks, tests, and demos
./bench.sh --list
```

### Prerequisites & Build

- **Java**: JDK 11 or higher
- **Maven**: 3.6 or higher
- **Local Research Dependencies**:
  1. [`chronograph-practice`](https://github.com/dfpl/chronograph-practice) (v0.1):
     ```bash
     git clone https://github.com/dfpl/chronograph-practice
     cd chronograph-practice/chronograph
     mvn clean install
     ```
  2. [`T-tinkerpop`](https://github.com/alexspitalas/T-tinkerpop) (`feature/temporal-multi-interval-engine`, v3.7.3-T):
     ```bash
     git clone https://github.com/alexspitalas/T-tinkerpop
     cd T-tinkerpop
     mvn clean install -DskipTests -Drat.skip=true -Dmaven.javadoc.skip=true
     ```

Build the benchmark suite:

```bash
mvn clean package -DskipTests
```

---

## Benchmark Suites & Catalog

Rather than navigating isolated benchmark scripts, tasks are grouped into logical, composable suites:

| Suite (`--suite`) | Name | Description | Included Tasks |
| :--- | :--- | :--- | :--- |
| **`paper`** *(Default)* | Paper Reproduction Suite | Complete reproduction of Tables VI–XI and Figures 1, 2, 4 from `journal.tex`. | `chronograph`, `index-selectivity`, `representation-ablation`, `multi-interval`, `pagerank`, `capabilities`, `path-semantics` |
| **`macro`** | Macro-Benchmarks | Complex end-to-end graph algorithms: temporal reachability, causal PageRank, multi-interval plane-sweep, and path semantics. | `chronograph`, `pagerank`, `multi-interval`, `path-semantics` |
| **`micro`** | Micro-Benchmarks | Primitive operator and component-level measurements: index crossover, representation ablation, and storage. | `index-selectivity`, `representation-ablation`, `lifetime-storage` |
| **`translator`** | Translation Overhead | Measures 4-phase translation pipeline latency and compares native vs. translated execution (Figures 1 & 2). | `translator-vs-native` |
| **`all`** | Complete Catalog | Sequentially executes all registered macro and micro benchmarks. | All 9 registered tasks |

### Registered Individual Tasks (`--target`)

1. **`chronograph`** ([`ChronoGraphBenchmark.java`](src/main/java/com/temporal/gremlin/bench/ChronoGraphBenchmark.java)) — Table 6 (`tab:chronograph-comparison`): OpenSky tBFS & Primitive Operators (5 modes).
2. **`index-selectivity`** ([`IndexSelectivityTradeoffBenchmark.java`](src/main/java/com/temporal/gremlin/bench/IndexSelectivityTradeoffBenchmark.java)) — Table 7 (`tab:index-selectivity-tradeoff`): Index Crossover (0.01%–75%) & Ingestion Overhead.
3. **`path-semantics`** ([`TemporalPathSemanticsBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPathSemanticsBenchmark.java)) — Figure 4 (`fig:path-semantics`): Temporal Path Semantics (Continuous, Pairwise, Sequential across depths 2–5).
4. **`representation-ablation`** ([`RepresentationAblationBenchmark.java`](src/main/java/com/temporal/gremlin/bench/RepresentationAblationBenchmark.java)) — Table 8 (`tab:representation-ablation-chronograph`): Dynamic String vs. ChronoGraph vs. Normalized `long[]`.
5. **`multi-interval`** ([`MultiIntervalStressBenchmark.java`](src/main/java/com/temporal/gremlin/bench/MultiIntervalStressBenchmark.java)) — Table 9 (`tab:multi-interval-chronograph`): Multi-Interval Traversal Scalability ($k=1, 2, 5, 10$ up to 1M edges).
6. **`pagerank`** ([`TemporalPageRankBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPageRankBenchmark.java)) — Table 10 (`tab:temporal-pagerank-chronograph`): Causal Temporal PageRank vs. ChronoGraph Extract+TPR vs. Static PR.
7. **`capabilities`** ([`TTinkerPopCapabilitiesScalingBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TTinkerPopCapabilitiesScalingBenchmark.java)) — Table 11 (`tab:ttinkerpop-unique-scaling`): Ligozat $(p,q)$-Calculus, Historical Subgraph Views, Path Optimality.
8. **`translator-vs-native`** ([`TranslatorVsNativeBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TranslatorVsNativeBenchmark.java)) — Figures 1 & 2 (`fig:translation-overhead`, `fig:native-vs-exec-combined`): 4-phase translation latency breakdown vs. native Java bytecode execution across SF10 and Flight datasets.
9. **`lifetime-storage`** ([`TemporalLifetimeStorageBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalLifetimeStorageBenchmark.java)) — Read/write latency across in-memory `lifetime()` storage representations.

---

## CLI Reference & Configuration

```text
Usage:
  ./bench.sh [OPTIONS]
  mvn exec:java -Dexec.args="[OPTIONS]"

Core Options:
  --menu, -i                  Launch interactive CLI Menu (Default when no args given)
  --mode, -m <mode>           Execution mode: bench | test | both | demo | list (default: bench)
  --suite, -s <suite>         Benchmark suite to run: paper | macro | micro | translator | all (default: paper)
  --target, -t <target>       Run single benchmark by ID:
                              chronograph | index-selectivity | path-semantics |
                              representation-ablation | multi-interval | pagerank |
                              capabilities | lifetime-storage | translator-vs-native
  --test-suite <suite>        Run correctness test suite: all | paper | chronograph | pagerank |
                              multi-interval | representation (default: all)
  --demo <demo-name>          Run interactive demo: translator | correctness | apps | continuous-path | string

Dataset & Scale Options:
  --dataset, -d <path>        Custom dataset CSV path (default: auto-detected OpenSky CSV)
  --scale, -sc <scale>        Workload scale: full | quick | custom (default: full)
  --sizes <s1,s2,...>         Custom sizes (e.g. --sizes=10000,50000,100000)

Output & Reporting Options:
  --output, -o <path>         Output directory or file path (default: results/)
  --format, -f <format>       Output format: both | txt | md | none (default: both)
  --no-save                   Disable saving results to file (console output only)

Informational Options:
  --list, -l                  List all available benchmarks, test suites, and demos
  --help, -h                  Display this help reference
```

### Scale Modes

- **`full`** *(Default)*: Full-scale journal paper reproduction up to 1,000,000+ flights/edges and 10,000,000+ intervals.
- **`quick`**: Fast smoke-test scale evaluating small representative subsets (~30 seconds total execution time). Ideal for CI/CD and quick environment verification.
- **`custom`**: User-defined element counts passed via `--sizes=5000,20000,50000`.

### Custom Datasets

The suite uses [`DatasetResolver.java`](src/main/java/com/temporal/gremlin/util/DatasetResolver.java) to automatically detect flight datasets:
1. Custom path provided via `--dataset=<path>` or menu prompt.
2. System property `-Dtemporal.dataset.path=<path>`.
3. Environment variable `TEMPORAL_DATASET_PATH`.
4. Workspace root `flightlist_20190101_20190131.csv` or `datasets/flightlist_20190101_20190131.csv`.

Any standard flight CSV containing `origin`, `destination`, `firstseen`, and `lastseen` columns is supported.

### Output & Reporting

By default, every run saves two mirrored artifacts in `results/`:
1. **Timestamped Plain-Text Log (`.txt`)**: Exact duplicate of console output.
2. **GitHub-Flavored Markdown Report (`.md`)**: Formatted document containing host hardware/OS specifications, JVM arguments, runtime configuration, summary tables, and cross-engine parity verification status.

---

## Journal Paper Benchmarks (Tables VI–XI & Figures)

### Table 6: ChronoGraph Comparison on OpenSky

- **Paper Reference**: Section VI-D, Table 6 (`tab:chronograph-comparison`) in `journal.tex`
- **Class**: [`ChronoGraphBenchmark.java`](src/main/java/com/temporal/gremlin/bench/ChronoGraphBenchmark.java)
- **CLI Target**: `./bench.sh --target=chronograph`
- **Evaluates**:
  1. **Macro-Benchmark (`tbfs`)**: Earliest-departure temporal reachability from hub airport across the OpenSky flight network.
  2. **Primitive Operator Scan (`temporalBefore`)**: Temporal filtering across 5 execution modes:
     - `CG Loop`: ChronoGraph manual event traversal loop.
     - `CG NatRel`: ChronoGraph native `edge.getEvents(t, TemporalRelation.isBefore)`.
     - `TPop Direct`: T-TinkerPop direct `long[]` interval sweep.
     - `TPop Unindexed`: T-TinkerPop Gremlin step (`TemporalGraphStepStrategy` step folding).
     - `TPop Indexed`: T-TinkerPop interval tree (`TemporalIndexOptimizationStrategy` + `TemporalTinkerIndex`).

#### Empirical Paper Results (`journal.tex` Table 6)

| Dataset (Flights / Routes $|E|$) | CG `tBFS` (ms) | TPop `tBFS` (ms) | `tBFS` Speedup | CG Loop (ms) | CG NatRel (ms) | TPop Direct (ms) | TPop Unindexed (ms) | TPop Indexed (ms) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **10k** (5,873 routes) | 1.44 | **0.18** | **8.1x** | 0.37 | 0.25 | **0.20** | 0.28 | 0.50 |
| **50k** (18,673 routes) | 12.16 | **1.85** | **6.6x** | 1.38 | 1.18 | **0.93** | 1.54 | 1.88 |
| **100k** (29,150 routes) | 30.89 | **3.50** | **8.8x** | 3.24 | 2.13 | **1.67** | 2.52 | 3.55 |
| **500k** (82,999 routes) | 277.01 | **21.62** | **12.8x** | 28.11 | 19.65 | **8.38** | 13.79 | 16.01 |
| **1.1M** (136,233 routes) | 650.90 | **42.64** | **15.3x** | 62.50 | 47.07 | **24.30** | 39.04 | 52.45 |

- **Cross-Engine Parity Status**:
  - `temporalBefore`: **100% exact match-count parity** across all 5 execution modes.
  - `tbfs`: ChronoGraph enforces strict $s_{\text{next}} > f_{\text{prev}}$ (excluding zero-delay transfers) and tracks a single start-ordered timestamp per vertex. T-TinkerPop permits $s_{\text{next}} \ge f_{\text{prev}}$ and tracks earliest arrival separately from earliest departure. T-TinkerPop reaches a **verified superset** of ChronoGraph's vertices with strictly earlier or equal timestamps; when evaluated under ChronoGraph's exact single-metric rule, both engines match **100% vertex-for-vertex and timestamp-for-timestamp**.

---

### Table 7: Index Selectivity Crossover & Ingestion Overhead

- **Paper Reference**: Section VI-D, Table 7 (`tab:index-selectivity-tradeoff`) in `journal.tex`
- **Class**: [`IndexSelectivityTradeoffBenchmark.java`](src/main/java/com/temporal/gremlin/bench/IndexSelectivityTradeoffBenchmark.java)
- **CLI Target**: `./bench.sh --target=index-selectivity`
- **Evaluates**: `temporalOverlaps` query latency across 8 selectivities from `0.01%` to `75%` on 500,000 multi-interval edges ($k=3$, 1,500,000 intervals), alongside ingestion write cost.
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

- **Key Insights**:
  - **Selective queries (`0.01%`–`1%`)**: `TemporalIndexOptimizationStrategy` transforms linear scans into $O(\log N + K)$ range searches, achieving up to **623.7x speedup** over unindexed scans (`0.15 ms` vs. `93.55 ms`) and **1000.7x speedup** over ChronoGraph (`150.10 ms`).
  - **Crossover point (~15% Selectivity)**: Above ~15% selectivity, sequential scanning (`TPop Unindexed` and `TPop Direct`) outperforms index traversal (`82.34 ms` vs. `167.21 ms` at `50%`) by avoiding tree pointer chasing and multi-interval `HashSet` deduplication.
  - **Cross-Engine Parity**: **100% exact match-count parity** across all 5 columns for all 8 selectivity tiers.

---

### Figure 4: Temporal Path Semantics

- **Paper Reference**: Section VI-E, Figure 4 (`fig:path-semantics`) in `journal.tex`
- **Class**: [`TemporalPathSemanticsBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPathSemanticsBenchmark.java)
- **CLI Target**: `./bench.sh --target=path-semantics`
- **Evaluates**: Scalability of Continuous, Pairwise-Continuous, and Sequential path traversal across depths 2 to 5 hops and limits 100, 1,000, 10,000 on Flight Single File (1.1M edges).

#### Empirical Paper Results (`journal.tex` Figure 4)

| Path Semantics | Depth (Hops) | Limit 100 (ms) | Limit 1,000 (ms) | Limit 10,000 (ms) | Scaling Characteristics |
| :--- | :---: | ---: | ---: | ---: | :--- |
| **Sequential** (`sequentialPath`) | 2 | 7 | 5 | 15 | Largely insensitive to result limit; less than $1.7\times$ slowdown from 2 to 5 hops ($15 \to 24$\,ms at 10k limit). |
| | 3 | 8 | 8 | 14 | |
| | 4 | 12 | 11 | 23 | |
| | 5 | 15 | 14 | 24 | |
| **Pairwise-Continuous** (`pairwiseContinuousPath`) | 2 | 40 | 49 | 141 | Modest overhead over sequential; interactive scaling with depth ($141 \to 382$\,ms at depth 5). |
| | 3 | 67 | 72 | 194 | |
| | 4 | 93 | 115 | 342 | |
| | 5 | 155 | 171 | 382 | |
| **Continuous** (`continuousPath`) | 2 | 486 | 2,024 | 57,142 | Dominated by depth due to global temporal intersection ($\bigcap \tau(e_r) \ne \emptyset$); valid window narrows, driving extensive engine backtracking. |
| | 3 | 14,353 | 17,527 | 89,473 | |
| | 4 | 25,884 | 32,043 | 134,257 | |
| | 5 | 45,594 | 52,841 | 196,269 | |

---

### Table 8: Representation Ablation

- **Paper Reference**: Section VI-F, Table 8 (`tab:representation-ablation-chronograph`) in `journal.tex`
- **Class**: [`RepresentationAblationBenchmark.java`](src/main/java/com/temporal/gremlin/bench/RepresentationAblationBenchmark.java)
- **CLI Target**: `./bench.sh --target=representation-ablation`
- **Evaluates**: Isolates performance of T-TinkerPop's normalized primitive `long[]` representation against dynamic ISO-8601 string parsing and ChronoGraph's `TreeSet<ChronoEdgeEvent>` objects across 50K to 1M events.

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

- **Cross-Engine Parity Status**: **100% exact match-count parity** across all 4 representations for `temporalBefore`, `temporalOverlaps`, and `during`.

---

### Table 9: Multi-Interval Traversal Scalability

- **Paper Reference**: Section VI-G, Table 9 (`tab:multi-interval-chronograph`) in `journal.tex`
- **Class**: [`MultiIntervalStressBenchmark.java`](src/main/java/com/temporal/gremlin/bench/MultiIntervalStressBenchmark.java)
- **CLI Target**: `./bench.sh --target=multi-interval`
- **Evaluates**: `temporalOverlaps` scaling with sub-interval multiplicity $k \in \{1, 2, 5, 10\}$ disjoint intervals per edge on up to 1,000,000 distinct edges (10,000,000 intervals).

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

- **Cross-Engine Parity Status**:
  - `ANY` quantifier: **100% exact parity** across all 5 existential columns (`TPop ANY`, `TPop Sweep`, `CG NatRel`, `CG Loop`, `Naive O(k^2)`).
  - `ALL` quantifier: Matches direct holistic evaluation 100%; matches `ANY` when $k=1$, and intentionally diverges when $k > 1$ due to holistic interval intersection semantics.

---

### Table 10: Temporal PageRank on OpenSky

- **Paper Reference**: Section VI-H, Table 10 (`tab:temporal-pagerank-chronograph`) in `journal.tex`
- **Class**: [`TemporalPageRankBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalPageRankBenchmark.java)
- **CLI Target**: `./bench.sh --target=pagerank`
- **Evaluates**: Native streaming `g.V().temporalPageRank()` (Rozenshtein & Gionis algorithm) against ChronoGraph event extraction + TPR and 20-iteration Static PageRank on OpenSky (50K to 1M flights).

#### Empirical Paper Results (`journal.tex` Table 10)

| Flights (Events) | Routes $|E|$ | Airports $|V|$ | TPop TPR (ms) | CG Extract+TPR (ms) | Static PR (ms) | Speedup vs Static |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **50,000** | 18,673 | 3,647 | **19.99** | 27.88 | 188.85 | **9.4x** |
| **100,000** | 29,150 | 5,184 | **24.93** | 36.76 | 133.53 | **5.4x** |
| **250,000** | 53,901 | 7,481 | **61.75** | 88.84 | 275.67 | **4.5x** |
| **500,000** | 82,999 | 8,906 | **121.10** | 178.92 | 307.57 | **2.5x** |
| **1,000,000** | 128,748 | 10,310 | **244.04** | 367.15 | 446.59 | **1.8x** |

- **Cross-Engine Parity Status**:
  - `TPop TPR` and `CG Extract+TPR` rank the exact same $|V|$ airports and produce **identical per-vertex PageRank scores within `1e-12` floating-point tolerance**.
  - Static PageRank ranks the same $|V|$ airports with different scores because it collapses the graph into a static projection and ignores temporal causality.

---

### Table 11: T-TinkerPop-Unique Capabilities Scaling

- **Paper Reference**: Section VI-I, Table 11 (`tab:ttinkerpop-unique-scaling`) in `journal.tex`
- **Class**: [`TTinkerPopCapabilitiesScalingBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TTinkerPopCapabilitiesScalingBenchmark.java)
- **CLI Target**: `./bench.sh --target=capabilities`
- **Evaluates**: Unique features of T-TinkerPop not supported in ChronoGraph from 10K to 1M multi-interval edges:
  1. **Suite A — Ligozat $(p,q)$-Calculus**: Single-pass `TemporalP.between(precedes, overlaps, ref)` (`294.39 ms` at 1M edges) is **2.60x–3.94x faster** than multi-branch `.or(...)` (`767.39 ms`). Zone-vector $\Pi(4,4)$ evaluation executes in `228.53 ms`.
  2. **Suite B — Historical Subgraph Views**: Decorator-propagated `window(t1, t2).out("event")` (`206.42 ms` at 1M edges) is **1.51x faster** than manual 3-step filter chains (`311.53 ms`). Point-in-time view `atTime(t)` executes in `127.50 ms`, and temporal property lookup (`tProp`) runs in `24.60 ms`.
  3. **Suite C — Temporal Path Targets**: All 4 optimality criteria (`EARLIEST_ARRIVAL`, `EARLIEST_DEPARTURE`, `LATEST_DEPARTURE`, `LATEST_ARRIVAL`) scale symmetrically (`707.14–782.62 ms` at 1M edges). Bounded-delay 2-hop `sequentialPath` runs in `1.70–2.94 ms`.

#### Empirical Paper Results (`journal.tex` Table 11)

| Edges $|E|$ | Atomic (ms) | Convex $[\alpha,\beta]$ (ms) | $\Pi(4,4)$ (ms) | `.or()` (ms) | Speedup vs `.or()` | `atTime` (ms) | `window` (ms) | Manual (ms) | `tProp` (ms) | EA (ms) | ED (ms) | LD (ms) | LA (ms) | `seqPath` (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **10,000** | 1.36 | **1.41** | 1.14 | 5.42 | **3.83x** | 1.31 | **1.12** | 1.63 | 0.31 | 2.04 | 2.20 | 2.51 | 2.52 | 2.94 |
| **50,000** | 7.46 | **7.86** | 5.92 | 30.93 | **3.94x** | 2.15 | **4.12** | 6.35 | 0.65 | 10.30 | 10.79 | 9.95 | 10.73 | 1.70 |
| **100,000** | 26.28 | **26.31** | 20.05 | 80.43 | **3.06x** | 9.09 | **13.57** | 21.66 | 1.64 | 28.68 | 31.67 | 39.57 | 41.70 | 1.97 |
| **500,000** | 131.54 | **147.22** | 114.32 | 382.74 | **2.60x** | 57.94 | **86.59** | 125.20 | 12.54 | 280.99 | 296.72 | 291.45 | 304.17 | 1.81 |
| **1,000,000** | 261.24 | **294.39** | 228.53 | 767.39 | **2.61x** | 127.50 | **206.42** | 311.53 | 24.60 | 745.03 | 782.62 | 707.14 | 718.62 | 1.82 |

- **Cross-Engine Parity Status**: **100% exact parity** across all sub-suites.

---

### Figures 1 & 2: Translation Overhead vs. Native Execution

- **Paper Reference**: Section VI-C, Figures 1 & 2 (`fig:translation-overhead`, `fig:native-vs-exec-combined`) and Footnote 2 in `journal.tex`
- **Class**: [`TranslatorVsNativeBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TranslatorVsNativeBenchmark.java)
- **CLI Target**: `./bench.sh --target=translator-vs-native`
- **Key Findings & Parity**:
  - Evaluates **all 13 Allen interval relations** (covering the 4 representative operators plotted in Figure 2 plus the remaining 9 operators referenced in Footnote 2 of `journal.tex`).
  - **100% Exact Parity**: `Native` (`g.E().temporalX(ref)`), `Execute` (translated `where(P.lt/gt/eq).by('startTime'/'endTime')` bytecode), and `Direct` (`has()` filters) return the **exact same edge match count** for all 13 Allen operators.
  - Translation phase overhead remains below $0.1$\,ms across all datasets, confirming the compilation step is negligible relative to query execution.
  - Native temporal execution maintains a consistent $1.5\times$ to $3.0\times$ speedup over translated traversals across SF10, Flight Single File, and Flight Multi File by eliminating traverser wrapper allocations, object boxing, and generic property lookups.

---

### Additional Benchmarks: Storage Representation

#### Lifetime Storage Benchmark (`--target=lifetime-storage`)
- **Class**: [`TemporalLifetimeStorageBenchmark.java`](src/main/java/com/temporal/gremlin/bench/TemporalLifetimeStorageBenchmark.java)
- Measures write normalization overhead and parse-on-read execution latency across varying `lifetime()` storage configurations.

---

## Automated Cross-Engine Parity Verification (`mvn test`)

The entire benchmark suite is backed by an automated JUnit verification suite (**17 tests across 5 test classes**) confirming exact column-by-column correctness and cross-engine parity:

```bash
# Via benchmark runner:
./bench.sh --mode=test --test-suite=all

# Or via standard Maven:
mvn test
```

### Test Suite Overview

| Test Class | Tests | Validated Paper Table | Primary Verification Objective |
| :--- | :---: | :---: | :--- |
| [`PaperBenchmarksCorrectnessTest.java`](src/test/java/com/temporal/gremlin/PaperBenchmarksCorrectnessTest.java) | 6 | Tables VI, VII, VIII, IX, X, XI | End-to-end column-by-column parity and expected semantic differences across every paper table. |
| [`ChronoGraphParityTest.java`](src/test/java/com/temporal/gremlin/ChronoGraphParityTest.java) | 5 | Table 6 & Allen 13 | All 13 Allen operators (`ANY`/`ALL`), multi-interval consolidation, 5-mode `temporalBefore` parity, and `tbfs` reachability parity against ChronoGraph. |
| [`TemporalPageRankTest.java`](src/test/java/com/temporal/gremlin/TemporalPageRankTest.java) | 2 | Table 10 | Causal vs. anti-causal rank propagation and exact `1e-12` per-vertex score parity between T-TinkerPop and ChronoGraph event extraction. |
| [`MultiIntervalStressTest.java`](src/test/java/com/temporal/gremlin/MultiIntervalStressTest.java) | 2 | Table 9 | $O(1)$ bounding-box + $O(k \log k)$ plane-sweep parity across $k \in \{1, 2, 5, 10\}$ against ChronoGraph and $O(k^2)$ Cartesian check. |
| [`RepresentationAblationTest.java`](src/test/java/com/temporal/gremlin/RepresentationAblationTest.java) | 2 | Table 8 | 100% match-count parity across dynamic ISO-8601 strings, ChronoGraph event trees, Gremlin traversals, and direct `long[]` sweeps. |

---

## Backward-Compatible Direct Invocations

Existing individual class invocations continue to work without modification:

```bash
# Table 6: ChronoGraph vs T-TinkerPop (tBFS & Operators)
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.ChronoGraphBenchmark"

# Table 7: Index Selectivity Crossover & Ingestion Overhead
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.IndexSelectivityTradeoffBenchmark"

# Figure 4: Temporal Path Semantics
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalPathSemanticsBenchmark"

# Table 8: Representation Ablation
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.RepresentationAblationBenchmark"

# Table 9: Multi-Interval Traversal Scalability
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.MultiIntervalStressBenchmark"

# Table 10: Temporal PageRank on OpenSky
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalPageRankBenchmark"

# Table 11: T-TinkerPop-Unique Capabilities Scaling
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TTinkerPopCapabilitiesScalingBenchmark"

# Storage & Translation Overhead
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalLifetimeStorageBenchmark"
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TranslatorVsNativeBenchmark"
```

---

## Project Structure

```text
gremlin-temporal-translator-poc/
├── pom.xml                                    # Maven dependencies & build configuration
├── bench.sh                                   # Central benchmark suite runner (or ./bench)
├── README.md                                  # Benchmark suite documentation
├── BENCHMARKS_AND_CORRECTNESS.md              # Detailed empirical results & parity analysis
├── journal.tex                                # T-TinkerPop IEEE journal paper source
├── flightlist_20190101_20190131.csv           # Default OpenSky flight dataset (~407 MB)
├── datasets/ -> .                             # Dataset symlink for path resolution
├── results/                                   # Generated benchmark Markdown & TXT reports
│
├── src/main/java/com/temporal/gremlin/
│   ├── benchmark/                             # Unified Benchmark Suite Architecture
│   │   ├── BenchmarkSuite.java                # Master CLI & menu entrypoint
│   │   ├── CliMenu.java                       # Interactive terminal menu with defaults
│   │   ├── CliArgsParser.java                 # Non-interactive CLI argument parser
│   │   ├── BenchmarkRegistry.java             # Catalog of benchmarks and suites
│   │   ├── BenchmarkContext.java              # Scale mode & dataset execution context
│   │   ├── BenchmarkOutput.java               # Dual logger (Terminal + Markdown + TXT)
│   │   ├── BenchmarkTask.java                 # Task interface implemented by benchmarks
│   │   ├── BenchmarkGroup.java                # Suite groupings (PAPER, MACRO, MICRO, etc.)
│   │   ├── ScaleMode.java                     # Workload scales (FULL, QUICK, CUSTOM)
│   │   ├── TestRunnerBridge.java              # Programmatic JUnit test executor
│   │   └── DemoRunner.java                    # Interactive demo runner
│   │
│   ├── bench/                                 # Individual Benchmark Implementations
│   │   ├── ChronoGraphBenchmark.java          # Table 6: OpenSky tBFS & temporalBefore
│   │   ├── IndexSelectivityTradeoffBenchmark.java # Table 7: Index selectivity crossover
│   │   ├── TemporalPathSemanticsBenchmark.java # Figure 4: Path semantics (Cont, Pair, Seq)
│   │   ├── RepresentationAblationBenchmark.java   # Table 8: Representation ablation
│   │   ├── MultiIntervalStressBenchmark.java  # Table 9: Multi-interval scaling (k=1..10)
│   │   ├── TemporalPageRankBenchmark.java     # Table 10: Temporal PageRank on OpenSky
│   │   ├── TTinkerPopCapabilitiesScalingBenchmark.java # Table 11: Ligozat, Views, Paths
│   │   ├── TemporalLifetimeStorageBenchmark.java # lifetime() storage read/write
│   │   └── TranslatorVsNativeBenchmark.java   # Figures 1 & 2: Translation overhead
│   │
│   ├── util/
│   │   ├── DatasetResolver.java               # Automatic dataset locator & validator
│   │   ├── GraphDataLoader.java               # Graph ingestion utilities
│   │   └── TemporalDataHelper.java            # Synthetic temporal data generators
│   │
│   ├── translator/                            # 4-phase translation pipeline
│   ├── query/                                 # Temporal query & mutation specifications
│   ├── executor/                              # Gremlin execution coordinator
│   └── demo/                                  # Runnable application & algorithm demos
│
└── src/test/java/com/temporal/gremlin/        # Automated Cross-Engine Parity Verification
    ├── PaperBenchmarksCorrectnessTest.java    # Paper Tables column-by-column parity suite
    ├── ChronoGraphParityTest.java             # Table 6 & Allen 13 relation parity
    ├── TemporalPageRankTest.java              # Table 10 1e-12 score parity suite
    ├── MultiIntervalStressTest.java           # Table 9 multi-interval quantifier parity
    └── RepresentationAblationTest.java        # Table 8 representation parity suite
```

---

## References

- **Paper Reproducibility Details**: See [**`BENCHMARKS_AND_CORRECTNESS.md`**](BENCHMARKS_AND_CORRECTNESS.md) for full mathematical analysis of semantic differences and detailed verification methodology.
- **T-TinkerPop Engine**: [github.com/alexspitalas/T-tinkerpop](https://github.com/alexspitalas/T-tinkerpop)
- **ChronoGraph Baseline**: [github.com/dfpl/chronograph-practice](https://github.com/dfpl/chronograph-practice)
