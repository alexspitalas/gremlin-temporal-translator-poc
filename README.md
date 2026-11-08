# Gremlin Temporal Translator POC - Java Project

A comprehensive proof-of-concept demonstrating translation overhead measurement for temporal graph queries. This Java Maven project implements a 4-phase translator on top of Apache TinkerPop Gremlin, with both programmatic and string-based APIs for temporal query translation.

## Table of Contents

- [Features](#features)
- [Project Structure](#project-structure)
- [Quick Start](#quick-start)
  - [Unified Benchmark Suite & CLI Menu (`./bench.sh`)](#unified-benchmark-suite--cli-menu-benchsh)
- [String-Based Transformer](#string-based-transformer)
- [Programmatic API](#programmatic-api)
- [Allen Temporal Algebra](#allen-temporal-algebra)
- [Translation Pipeline](#translation-pipeline)
- [Performance & Optimization](#performance--optimization)
- [Examples](#examples)
- [Benchmarks (Journal Paper Tables V–XI)](#benchmarks)
- [Correctness & Cross-Engine Parity Verification (`mvn test`)](#testing--cross-engine-parity-verification)

## Features

### 🎯 Dual Interface
- **String-Based Transformer**: Parse temporal query strings directly
- **Programmatic API**: Build queries using fluent Java API

### ⏱️ Complete Temporal Support
- All 13 Allen temporal operators
- Vertex and edge lifetime constraints
- Property-level temporal metadata
- Reference vertex queries

### 🚀 4-Phase Translation Pipeline
1. **Parser** - Query string/spec → AST (~0.5ms)
2. **Transformer** - AST → Gremlin steps (~0.3ms)
3. **Optimizer** - Step optimization (~0.4ms)
4. **Codegen** - Bytecode generation (~0.2ms)

### 📊 Performance Metrics
- Microsecond-precision timing
- Phase-by-phase breakdown
- Overhead ratio analysis
- Bytecode size tracking

## Project Structure

```
gremlin-temporal-translator-poc/
├── pom.xml                              # Maven build configuration
├── bench.sh                             # Unified benchmark suite & CLI runner (or ./bench)
├── README.md                            # This file
├── BENCHMARKS_AND_CORRECTNESS.md        # Comprehensive benchmark & parity verification guide
│
└── src/main/java/com/temporal/gremlin/
    ├── benchmark/
    │   ├── BenchmarkSuite.java          # Main orchestrator (Interactive Menu & CLI flags)
    │   ├── CliMenu.java                 # Interactive terminal menu with defaults
    │   ├── CliArgsParser.java           # Command-line argument parser
    │   ├── BenchmarkRegistry.java       # Central catalog of all benchmarks & suites
    │   ├── BenchmarkContext.java        # Runtime execution context & scale controller
    │   ├── BenchmarkOutput.java         # Dual logger (Console + Markdown + Text reports)
    │   ├── BenchmarkTask.java           # Benchmark task interface
    │   ├── BenchmarkGroup.java          # Grouping categories (PAPER, MACRO, MICRO, etc.)
    │   ├── ScaleMode.java               # Workload scale modes (FULL, QUICK, CUSTOM)
    │   ├── TestRunnerBridge.java        # Programmatic JUnit test executor & reporter
    │   └── DemoRunner.java              # Interactive demo runner
    │
    ├── util/
    │   ├── DatasetResolver.java         # Intelligent dataset locator & validator
    │   ├── GraphDataLoader.java         # Ingestion utilities
    │   └── TemporalDataHelper.java      # Temporal generation helpers
    │
    ├── query/
    │   ├── TemporalQuerySpec.java      # Query specification + Allen operators
    │   └── TemporalTransaction.java    # Mutation specification (add vertex/edge)
    │
    ├── translator/
    │   ├── QueryParser.java             # Phase 1: Parse → AST
    │   ├── QueryAST.java                # Abstract Syntax Tree representation
    │   ├── GremlinTransformer.java      # Phase 2: Transform → Gremlin steps
    │   ├── StepOptimizer.java           # Phase 3: Optimize step sequences
    │   ├── BytecodeGenerator.java       # Phase 4: Codegen → bytecode
    │   ├── TranslationMetrics.java      # Metrics collection & reporting
    │   ├── TemporalGremlinTranslator.java # Main coordinator
    │   ├── TemporalQueryParser.java     # String query parser
    │   └── TemporalQueryStringTransformer.java # String-based transformer
    │
    ├── demo/
    │   ├── TranslatorDemo.java          # Interactive demo with examples
    │   ├── CorrectnessDemo.java         # Native vs Translator comparison
    │   ├── TemporalApplicationsDemo.java # Temporal algorithms demo
    │   ├── TestContinuousPath.java      # Continuous paths demo
    │   └── StringTransformerDemo.java   # String transformer demo
    │
    ├── executor/
    │   └── GremlinExecutor.java         # Query execution engine
    │
    └── bench/
        ├── ChronoGraphBenchmark.java                   # Table V: OpenSky tBFS & temporalBefore (5 modes)
        ├── IndexSelectivityTradeoffBenchmark.java      # Table VI: Index selectivity crossover (0.01%–75%) & write cost
        ├── RepresentationAblationBenchmark.java        # Table VIII: Dynamic String vs ChronoGraph vs Normalized long[]
        ├── MultiIntervalStressBenchmark.java           # Table IX: Multi-interval scaling (k=1,2,5,10 up to 1M edges)
        ├── TemporalPageRankBenchmark.java              # Table X: Temporal PageRank vs ChronoGraph Extract+TPR vs Static PR
        ├── TTinkerPopCapabilitiesScalingBenchmark.java # Table XI: Ligozat (p,q)-calculus, Historical Views, Path Targets
        ├── TemporalLifetimeStorageBenchmark.java       # lifetime() storage read/write comparison
        └── TranslatorVsNativeBenchmark.java            # Translation overhead & execution comparison
└── src/test/java/com/temporal/gremlin/
    ├── PaperBenchmarksCorrectnessTest.java # Column-by-column parity suite for all paper tables (Tables V–XI)
    ├── ChronoGraphParityTest.java          # Allen 13 relations, multi-interval & tBFS parity with ChronoGraph
    ├── TemporalPageRankTest.java           # Causal rank propagation & exact 1e-12 score parity with ChronoGraph
    ├── MultiIntervalStressTest.java        # O(1) bounding-box + O(k log k) sweep parity across ANY and ALL
    └── RepresentationAblationTest.java     # Parity across String, ChronoGraph, Traversal, and Direct modes
```

## Quick Start

### Prerequisites

- Java 11+
- Maven 3.6+
- Apache TinkerPop 3.6.4
- **chronograph-practice** (Must be installed locally before building)
- **T-tinkerpop** (Must be installed locally before building)

### Installing Core Dependencies

Before building this project, install the required research dependencies locally into your Maven repository:

1. **chronograph-practice**:
```bash
git clone https://github.com/dfpl/chronograph-practice
cd chronograph-practice/chronograph
mvn clean install
```

2. **t-tinkerpop**:
```bash
git clone https://github.com/alexspitalas/T-tinkerpop
cd T-tinkerpop
mvn clean install
```

### Build

```bash
mvn clean package
```

### Run Demos

```bash
# Programmatic API demo
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.demo.TranslatorDemo"

# String transformer demo
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.demo.StringTransformerDemo"
```

### Unified Benchmark Suite & CLI Menu (`./bench.sh`)

The repository features a centralized, easy-to-use benchmark suite runner with an interactive CLI menu, logical suite groupings, custom dataset support, scale controls (Full vs. Quick smoke-test), and dual output formats (terminal + timestamped Markdown/TXT reports saved to `results/`).

#### Option 1: Interactive Menu Mode (Recommended for Exploration)
Run `./bench.sh` (or `./bench`) without arguments. Hitting `<Enter>` selects sensible defaults (Full Paper Suite with OpenSky dataset saving to `results/`):
```bash
./bench.sh
```

#### Option 2: Command-Line Flags (Recommended for Automation & Scripts)
Run any test or benchmark non-interactively with CLI arguments:
```bash
# 1. Fast smoke-test evaluation across all paper benchmarks (~30 seconds)
./bench.sh --mode=bench --suite=paper --scale=quick

# 2. Full Journal Paper reproduction suite (Tables V–XI)
./bench.sh --mode=bench --suite=paper

# 3. Automated correctness and cross-engine parity suite (17 tests)
./bench.sh --mode=test --test-suite=all

# 4. Run both correctness verification and paper benchmarks sequentially
./bench.sh --mode=both --suite=paper

# 5. Run a specific benchmark with a custom dataset and custom output path
./bench.sh --target=chronograph --dataset=/path/to/flightlist.csv --output=results/my_run.txt

# 6. List all available benchmark groups, individual tasks, tests, and demos
./bench.sh --list
```

## String-Based Transformer

### Overview

The string-based transformer allows you to write temporal queries as strings and automatically translates them to Gremlin bytecode. It supports both queries and mutations.

### Supported Query Formats

#### 1. Label-Based Queries
Find relationships between two vertex types:
```java
String query = "g.V().hasLabel('Conference').temporalBefore('Meeting')";
TranslationResult result = transformer.transform(query);
```

#### 2. Reference Vertex Queries
Find vertices related to a specific vertex:
```java
String query = "g.V(123).temporalOverlaps('Meeting')";  // Find Meetings overlapping with vertex 123
String query = "g.V(456).temporalBefore()";             // Find any vertices before vertex 456
```

#### 3. Lifetime Constraints
Add temporal bounds to queries:
```java
String query = "g.V().hasLabel('Event').lifetime(1234567890000, 1234567900000).temporalOverlaps('Meeting')";
```

#### 4. Add Vertex Mutations
Create vertices with properties and lifetime:
```java
String mutation = "g.addV('Person').property('name', 'John').property('age', 30).lifetime(start, end)";
```

#### 5. Add Edge Mutations
Create edges with properties and lifetime:
```java
String mutation = "g.addE('knows').from(v1).to(v2).property('since', 2020).lifetime(start, end)";
```

### Usage Example

```java
import com.temporal.gremlin.translator.TemporalQueryStringTransformer;

// Create transformer
GraphTraversalSource g = graph.traversal();
TemporalQueryStringTransformer transformer = new TemporalQueryStringTransformer(g);

// Transform a query
String query = "g.V().hasLabel('Conference').temporalBefore('Meeting')";
TranslationResult result = transformer.transform(query);

// Access results
System.out.println("Bytecode: " + result.bytecode);
System.out.println("Translation time: " + result.metrics.getTotalTime() + " ms");
System.out.println("Steps: " + result.optimizedSteps.size());

// Transform a mutation
String mutation = "g.addV('Person').property('name', 'Alice').lifetime(1234567890000, 1234567900000)";
TranslationResult mutationResult = transformer.transform(mutation);
```

### Command-Line Interface

The transformer is robust against shell quote-stripping, so you can pass queries directly via Maven:

```bash
# Standard execution (works even if shell strips quotes)
mvn exec:java \
  -Dexec.mainClass="com.temporal.gremlin.translator.TemporalQueryStringTransformer" \
  -Dexec.args="g.V().hasLabel('Conference').temporalBefore('Meeting')"
```

> **Note**: If you encounter issues with complex characters, you can run the demo which has built-in test queries:
> `mvn exec:java -Dexec.mainClass="com.temporal.gremlin.demo.StringTransformerDemo"`

## Programmatic API

### Basic Temporal Query

```java
import com.temporal.gremlin.query.TemporalQuerySpec;
import com.temporal.gremlin.translator.TemporalGremlinTranslator;

// Initialize
Graph graph = TinkerGraph.open();
GraphTraversalSource g = graph.traversal();
TemporalGremlinTranslator translator = new TemporalGremlinTranslator(g);

// Create query
TemporalQuerySpec query = new TemporalQuerySpec(
    "Conference", 
    "Meeting",
    TemporalQuerySpec.AllenOperator.temporalBefore
);

// Translate
TranslationResult result = translator.translate(query);

// Examine results
System.out.println(result.metrics.printReport());
System.out.println(result.bytecode);
```

### Query with Lifetime Constraints

```java
TemporalQuerySpec query = new TemporalQuerySpec(
    "Person", 
    "Event",
    TemporalQuerySpec.AllenOperator.temporalDuring
)
.withLifetime(
    Instant.parse("2024-01-01T00:00:00Z"),
    Instant.parse("2024-12-31T23:59:59Z")
)
.withConstraints("it.get().get('v1').value('status') == 'active'");

TranslationResult result = translator.translate(query);
```

### Reference Vertex Query

```java
// Find all vertices that overlap with a specific vertex
Object vertexId = g.V().has("name", "Planning").id().next();

TemporalQuerySpec query = new TemporalQuerySpec(
    null,  // Any type
    null,
    TemporalQuerySpec.AllenOperator.temporalOverlaps
).withReferenceVertex(vertexId);

TranslationResult result = translator.translate(query);
```

### Temporal Mutations

```java
import com.temporal.gremlin.query.TemporalTransaction;

// Create transaction
TemporalTransaction tx = new TemporalTransaction();

// Add vertex with lifetime
tx.addVertex("Person")
  .property("name", "John")
  .property("age", 30)
  .lifetime(Instant.now(), Instant.now().plus(365, ChronoUnit.DAYS));

// Add edge with lifetime
tx.addEdge("knows", "v1", "v2")
  .property("since", 2020)
  .lifetime(Instant.now(), Instant.now().plus(365, ChronoUnit.DAYS));

// Translate and execute
TranslationResult result = translator.translate(tx);
```

## Allen Temporal Algebra

All 13 Allen temporal relationships are fully supported:

| Operator | Description | Example |
|----------|-------------|---------|
| `temporalBefore` | i1 ends before i2 starts | Meeting before Conference |
| `temporalAfter` | i1 starts after i2 ends | Conference after Meeting |
| `temporalOverlaps` | i1 partially overlaps with i2 | Event overlaps Meeting |
| `temporalOverlappedBy` | i2 partially overlaps with i1 | Meeting overlapped by Event |
| `temporalDuring` | i1 completely contained within i2 | Meeting during Workshop |
| `temporalContains` | i1 completely contains i2 | Workshop contains Meeting |
| `temporalStarts` | i1 starts same as i2 but ends first | Task starts Project |
| `temporalStartedBy` | i2 starts same as i1 but ends first | Project started by Task |
| `temporalEnds` | i1 ends same as i2 but starts later | Task ends Project |
| `temporalEndedBy` | i2 ends same as i1 but starts later | Project ended by Task |
| `temporalMeets` | i1 ends exactly when i2 starts | Task A meets Task B |
| `temporalMetBy` | i2 ends exactly when i1 starts | Task B met by Task A |
| `temporalEqual` | i1 and i2 have identical lifetimes | Event equals Meeting |

### Temporal Constraint Examples

```java
// temporalBefore: Conference ends before Meeting starts
"g.V().hasLabel('Conference').temporalBefore('Meeting')"

// temporalOverlaps: Event overlaps with Meeting
"g.V().hasLabel('Event').temporalOverlaps('Meeting')"

// temporalDuring: Meeting occurs during Workshop
"g.V().hasLabel('Meeting').temporalDuring('Workshop')"

// temporalMeets: Task A immediately precedes Task B
"g.V().hasLabel('A').temporalMeets('B')"
```

## Translation Pipeline

### Phase 1: Parser
Converts query strings or specs to Abstract Syntax Tree (AST)
- Validates syntax
- Extracts temporal semantics
- **Overhead**: ~0.5ms

### Phase 2: Transformer
Maps temporal constraints to Gremlin traversal steps
- Decomposes Allen operators
- Generates filter steps
- **Overhead**: ~0.3ms

### Phase 3: Optimizer
Optimizes step sequences
- Fuses adjacent filters
- Eliminates redundancy
- Reorders for efficiency
- **Overhead**: ~0.4ms

### Phase 4: Codegen
Generates final Gremlin bytecode
- Multiple output strategies
- Bytecode formatting
- **Overhead**: ~0.2ms

**Total Translator Overhead**: ~1.4ms vs **Native T-Gremlin**: ~0.15ms (9.3x)

## Performance & Optimization

### Optimization Levels

Three configurable optimization strategies:

```java
// No optimization - fastest translation
translator.setOptimizationLevel(StepOptimizer.OptimizationLevel.NONE);

// Basic optimization - balanced approach (default)
translator.setOptimizationLevel(StepOptimizer.OptimizationLevel.BASIC);

// Advanced optimization - maximum performance
translator.setOptimizationLevel(StepOptimizer.OptimizationLevel.ADVANCED);
```

| Level | Translation Time | Bytecode Reduction | Use Case |
|-------|-----------------|-------------------|----------|
| NONE | ~1.8ms | 0% | Development, debugging |
| BASIC | ~1.4ms | 20-30% | Production (default) |
| ADVANCED | ~1.2ms | 30-50% | Performance-critical |

### Translation Strategies

Three bytecode generation strategies:

```java
// Literal - step-by-step mapping
translator.setStrategy(BytecodeGenerator.TranslationStrategy.LITERAL);

// Fusion - combine related steps (default)
translator.setStrategy(BytecodeGenerator.TranslationStrategy.FUSION);

// Direct - bypass AST for maximum speed
translator.setStrategy(BytecodeGenerator.TranslationStrategy.BYTECODE_DIRECT);
```

| Strategy | Bytecode Size | Speed | Use Case |
|----------|--------------|-------|----------|
| LITERAL | 100-150 bytes | Slower | Debugging, readability |
| FUSION | 60-100 bytes | Fast | Production (default) |
| BYTECODE_DIRECT | 40-60 bytes | Fastest | High-frequency queries |

### Metrics Collection

Each translation produces detailed metrics:

```java
TranslationMetrics metrics = result.metrics;

System.out.printf("Parsing:      %.3f ms\n", metrics.getParsingTime());
System.out.printf("Transform:    %.3f ms\n", metrics.getTransformTime());
System.out.printf("Optimization: %.3f ms\n", metrics.getOptimizationTime());
System.out.printf("Codegen:      %.3f ms\n", metrics.getCodegenTime());
System.out.printf("Total:        %.3f ms\n", metrics.getTotalTime());
System.out.printf("Bytecode:     %d bytes\n", metrics.getBytecodeSize());
System.out.printf("Steps:        %d\n", metrics.getStepCount());
```

## Examples

### Example 1: Find Conferences Before Meetings

```java
// String-based
String query = "g.V().hasLabel('Conference').temporalBefore('Meeting')";
TranslationResult result = transformer.transform(query);

// Programmatic
TemporalQuerySpec spec = new TemporalQuerySpec(
    "Conference", "Meeting", AllenOperator.temporalBefore
);
TranslationResult result = translator.translate(spec);
```

**Generated Bytecode**:
```gremlin
g.V().hasLabel('Conference').as('v1')
  .V().hasLabel('Meeting').as('v2')
  .where('v1', P.lt('v2')).by('lifetime_end').by('lifetime_start')
  .order(Scope.global).by('lifetime_end')
  .select('v1', 'v2')
```

### Example 2: Events Overlapping with Specific Meeting

```java
// Get meeting ID
Object meetingId = g.V().has("name", "Planning").id().next();

// String-based
String query = "g.V(" + meetingId + ").temporalOverlaps('Event')";
TranslationResult result = transformer.transform(query);

// Programmatic
TemporalQuerySpec spec = new TemporalQuerySpec(
    "Event", null, AllenOperator.temporalOverlaps
).withReferenceVertex(meetingId);
TranslationResult result = translator.translate(spec);
```

### Example 3: Add Person with Temporal Properties

```java
Instant now = Instant.now();
Instant end = now.plus(365, ChronoUnit.DAYS);

// String-based
String mutation = "g.addV('Person')" +
    ".property('name', 'Alice')" +
    ".property('age', 30)" +
    ".lifetime(" + now.toEpochMilli() + ", " + end.toEpochMilli() + ")";
TranslationResult result = transformer.transform(mutation);

// Programmatic
TemporalTransaction tx = new TemporalTransaction();
tx.addVertex("Person")
  .property("name", "Alice")
  .property("age", 30)
  .lifetime(now, end);
TranslationResult result = translator.translate(tx);
```

## Benchmarks

> [!TIP]
> **Recommended Runner**: Use `./bench.sh` (or `./bench`) to run benchmarks interactively or via CLI flags (e.g. `./bench.sh --suite=paper --scale=quick`). All individual benchmark classes also remain directly runnable via `mvn exec:java` as detailed below for 100% backward compatibility.

See [**BENCHMARKS_AND_CORRECTNESS.md**](BENCHMARKS_AND_CORRECTNESS.md) for the complete reproducibility guide and column-by-column parity analysis corresponding to **Tables V–XI** of the T-TinkerPop journal paper.

### 1. OpenSky Scalability & Primitive Operator Benchmark (`ChronoGraphBenchmark` — Paper Table V)
Compares T-TinkerPop against ChronoGraph on the OpenSky flight dataset (consolidated multi-interval route edges) for both macro-benchmark (`tbfs` earliest-departure reachability) and micro-benchmark (`temporalBefore` across 5 execution modes: `CG Loop`, `CG NatRel`, `TPop Direct`, `TPop Unindexed`, `TPop Indexed`):
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.ChronoGraphBenchmark"
```

| Dataset Size | CG `tBFS` (ms) | TPop `tBFS` (ms) | `tBFS` Speedup | CG Loop (ms) | CG NatRel (ms) | TPop Direct (ms) | TPop Unidx. (ms) | TPop Idx. (ms) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **50k** (18,673 routes) | 16.57 | **2.52** | **6.6x** | 1.99 | 1.77 | **0.88** | 1.38 | 3.70 |
| **100k** (29,150 routes) | 38.68 | **4.65** | **8.3x** | 4.65 | 3.80 | **1.54** | 2.66 | 5.29 |
| **250k** (53,901 routes) | 96.32 | **11.16** | **8.6x** | 14.37 | 9.57 | **4.32** | 6.84 | 11.49 |
| **500k** (82,999 routes) | 277.01 | **21.62** | **12.8x** | 28.11 | 19.65 | **8.38** | 13.79 | 16.01 |
| **1.1M** (136,233 routes) | 650.90 | **42.64** | **15.3x** | 62.50 | 47.07 | **24.30** | 39.04 | 52.45 |

### 2. Index Selectivity Crossover & Write-Amplification Trade-off (`IndexSelectivityTradeoffBenchmark` — Paper Table VI)
Evaluates `temporalOverlaps` across query selectivities from `0.01%` to `75%` on 500,000 multi-interval edges ($k=3$, 1.5M intervals), alongside ingestion write overhead (`198.25 ms` unindexed vs. `481.27 ms` indexed, **2.43x write overhead**):
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.IndexSelectivityTradeoffBenchmark"
```
- **Selective queries (`0.01%`–`1%`)**: `TemporalIndexOptimizationStrategy` achieves up to **623.7x speedup** over unindexed scans (`0.15 ms` vs. `93.55 ms` at `0.01%`) and **1000.7x speedup** over ChronoGraph (`150.10 ms`).
- **High-selectivity crossover (`>15%`)**: Above ~15% selectivity, sequential primitive scanning (`TPop Unindexed` and `TPop Direct`) outperforms interval-tree probing (`82.34 ms` and `57.76 ms` vs. `167.21 ms` at `50%` selectivity) by avoiding tree pointer chasing and `HashSet` deduplication of multi-interval edges.

### 3. Representation Ablation (`RepresentationAblationBenchmark` — Paper Table VIII)
Isolates the performance impact of T-TinkerPop's normalized primitive `long[]` representation against dynamic ISO-8601 string parsing and ChronoGraph's `TreeSet<ChronoEdgeEvent>` objects across 50K to 1M events:
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.RepresentationAblationBenchmark"
```
- Delivers a **54.0x to 96.1x speedup** over dynamic ISO-8601 string parsing (`19.17–19.44 ms` Direct and `29.74–31.83 ms` Traversal vs. `1,697.81–1,743.54 ms` String at 1M events) and outperforms ChronoGraph (`42.34–52.34 ms`).

### 4. Multi-Interval Stress Benchmark (`MultiIntervalStressBenchmark` — Paper Table IX)
Evaluates `temporalOverlaps` scaling across sub-interval multiplicity $k \in \{1, 2, 5, 10\}$ on up to 1,000,000 distinct edges (10,000,000 intervals):
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.MultiIntervalStressBenchmark"
```
- At 1M edges, as $k$ increases $10\times$ ($1 \to 10$), T-TinkerPop's $O(1)$ outer bounding-box fast-reject + $O(k \log k)$ binary sweep keeps latency nearly constant (`TPop Sweep`: `130.01 ms -> 136.95 ms`; `TPop ANY`: `222.27 ms -> 225.09 ms`; `TPop ALL`: `218.72 ms -> 223.47 ms`), outperforming `CG NatRel` (`855.07 ms`) by **3.80x–6.24x** and `Naive O(k^2)` (`438.44 ms`) by **1.95x–3.20x**.

### 5. Temporal PageRank Benchmark (`TemporalPageRankBenchmark` — Paper Table X)
Compares native streaming `g.V().temporalPageRank()` (Rozenshtein & Gionis) against ChronoGraph event extraction + TPR and 20-iteration Static PageRank on OpenSky (50K to 1M flights):
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalPageRankBenchmark"
```
- At 1M flights (128,748 route edges, 10,310 airports), `TPop TPR` completes in **244.04 ms**, outperforming `CG Extract+TPR` (`367.15 ms`, **1.50x speedup**) and 20-iteration `Static PR` (`446.59 ms` on compressed routes, **1.8x speedup**; `3,851.05 ms` on uncompressed multigraph, **15.8x speedup**).

### 6. T-TinkerPop-Unique Capabilities Scaling (`TTinkerPopCapabilitiesScalingBenchmark` — Paper Table XI)
Benchmarks capabilities unique to T-TinkerPop (not supported in ChronoGraph) from 10K to 1M multi-interval edges:
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TTinkerPopCapabilitiesScalingBenchmark"
```
- **Suite A (Ligozat $(p,q)$-Calculus)**: Single-pass `TemporalP.between(precedes, overlaps, ref)` (`294.39 ms` at 1M edges) is **2.60x–3.94x faster** than multi-branch `.or(before, meets, overlaps)` (`767.39 ms`), and $\Pi(4,4)$ generalized zone-vector evaluation runs in `228.53 ms`.
- **Suite B (Historical Subgraph Views)**: Decorator-propagated `window(t1, t2).out("event")` (`206.42 ms` at 1M edges) is **1.51x faster** than manual 3-step `temporalIntersects` chains (`311.53 ms`), while `atTime(t)` runs in `127.50 ms` and `lifetimeProperty` lookup (`tProp`) in `24.60 ms`.
- **Suite C (Temporal Path Targets)**: All 4 `tbfs` targets (`EARLIEST_ARRIVAL`, `EARLIEST_DEPARTURE`, `LATEST_DEPARTURE`, `LATEST_ARRIVAL`) scale symmetrically (`707.14–782.62 ms` at 1M edges), and 2-hop bounded-delay `sequentialPath` runs in `1.70–2.94 ms`.

### 7. Translator vs. Native & Lifetime Storage Benchmarks
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TranslatorVsNativeBenchmark"
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalLifetimeStorageBenchmark"
```

---

## Testing & Cross-Engine Parity Verification

Run the complete JUnit correctness and cross-engine parity verification suite (**17 tests across 5 test classes**):

```bash
mvn test
```

### Summary of Verified Parity & Expected Semantic Differences

Full details are documented in [**BENCHMARKS_AND_CORRECTNESS.md**](BENCHMARKS_AND_CORRECTNESS.md) and verified by [`PaperBenchmarksCorrectnessTest.java`](src/test/java/com/temporal/gremlin/PaperBenchmarksCorrectnessTest.java):

| Paper Table | Benchmark Suite | Verified Parity & Expected Semantic Differences |
| :--- | :--- | :--- |
| **Table V** | `ChronoGraphBenchmark` | **`temporalBefore` (5 cols)**: 100% identical match counts across `CG Loop`, `CG NatRel`, `TPop Direct`, `TPop Unindexed`, and `TPop Indexed`.<br>**`tbfs` (2 cols)**: ChronoGraph's `TemporalBreadthFirstSearch` enforces strict `isAfter` ($s_{\text{next}} > f_{\text{prev}}$, excluding zero-delay `meets` transfers) and stores a single `TimePeriod(s, f)` per vertex ordered by $s$ (discarding faster-arriving flights with $s_2 > s_1, f_2 < f_1$). T-TinkerPop allows $s_{\text{next}} \ge f_{\text{prev}}$ and tracks `causalTime[v] = min(bestEnd)` separately from `gamma[v] = min(bestStart)`. Consequently, T-TinkerPop reaches a superset of ChronoGraph's vertices (`tpopReachedCodes.containsAll(cgReachedCodes)`) with $\le$ departure timestamps. When T-TinkerPop is evaluated under ChronoGraph's exact single-metric strict `isAfter` rule, both engines match **100% vertex-for-vertex and timestamp-for-timestamp**. |
| **Table VI** | `IndexSelectivityTradeoffBenchmark` | **100% Exact Parity**: All 5 columns (`TPop Indexed`, `TPop Unindexed`, `TPop Direct`, `CG NatRel`, `CG Loop`) return identical match counts across all 8 selectivities (`0.01%` to `75%`). |
| **Table VIII** | `RepresentationAblationBenchmark` | **100% Exact Parity**: All 4 columns (`String`, `CG`, `TPop Traversal`, `TPop Direct`) return identical match counts across `temporalBefore`, `temporalOverlaps`, and `during`. |
| **Table IX** | `MultiIntervalStressBenchmark` | **100% Exact Parity on `ANY` (5 cols)**: `TPop ANY`, `TPop Sweep`, `CG NatRel`, `CG Loop`, and `Naive O(k^2)` return identical counts for all $k \in \{1, 2, 5, 10\}$.<br>**Expected `ALL` vs. `ANY` Difference**: `TPop ALL` matches direct holistic evaluation 100%, equals `TPop ANY` when $k=1$, and intentionally differs when $k>1$ because `ALL` evaluates whole-lifetime bounds whereas `ANY` evaluates pairwise sub-interval overlaps. |
| **Table X** | `TemporalPageRankBenchmark` | **100% Score & Vertex Parity**: `TPop TPR` and `CG Extract+TPR` rank the exact same $|V|$ airports and produce **identical per-vertex PageRank scores within `1e-12`**. `Static PR` ranks the same $|V|$ airports with different scores because static PageRank ignores causal time ordering. |
| **Table XI** | `TTinkerPopCapabilitiesScalingBenchmark` | **100% Exact Parity Across All Sub-Suites**:<br>- **Ligozat**: `Convex [precedes, overlaps]` matches `.or(before, meets, overlaps)` 100%; `Pi(4,4)` matches direct `GeneralizedRelation.between` 100%.<br>- **Views**: `window(t1, t2)` and `atTime(t)` match manual 3-step `temporalIntersects` filter chains 100%; `tProp` returns all active properties in window and filters out future properties.<br>- **Paths**: Forward (`EA`, `ED`) and backward (`LD`, `LA`) target pairs reach identical vertex sets with $\text{ED}(v) < \text{EA}(v)$ and $\text{LD}(v) < \text{LA}(v)$; 2-hop bounded-delay `sequentialPath` matches direct 2-hop delay check 100%. |

## Performance Insights

### Phase Contribution to Overhead

- **Parser**: ~36% (AST construction)
- **Transform**: ~21% (step generation)
- **Optimizer**: ~29% (refinement)
- **Codegen**: ~14% (bytecode formatting)

### When to Use Each Approach

| Approach | Latency | Use Case |
|----------|---------|----------|
| **String Transformer** | ~1.4ms | Development, ad-hoc queries, CLI tools |
| **Programmatic API** | ~1.4ms | Application integration, type safety |
| **Native T-Gremlin** | ~0.15ms | High-frequency, latency-critical queries |

### Optimization Impact

- NONE → BASIC: **22% reduction** in translation time
- BASIC → ADVANCED: **14% additional reduction**
- Total: **36% reduction** from NONE baseline

## API Reference

### TemporalQueryStringTransformer

```java
// Constructors
TemporalQueryStringTransformer()  // Creates with internal TinkerGraph
TemporalQueryStringTransformer(GraphTraversalSource g)  // Uses provided graph

// Methods
TranslationResult transform(String temporalQuery)  // Transform query/mutation
String transformToString(String temporalQuery)     // Returns bytecode string
void close()                                       // Close internal graph
```

### TemporalGremlinTranslator

```java
// Constructors
TemporalGremlinTranslator(GraphTraversalSource g)

// Methods
TranslationResult translate(TemporalQuerySpec spec)
TranslationResult translate(TemporalTransaction tx)
void setOptimizationLevel(OptimizationLevel level)
void setStrategy(TranslationStrategy strategy)
```

### TemporalQuerySpec

```java
// Constructor
TemporalQuerySpec(String v1, String v2, AllenOperator operator)

// Fluent API
withLifetime(Instant start, Instant end)
withConstraints(String constraints)
withReferenceVertex(Object id)
withQueryType(QueryType type)
withMetadata(String key, Object value)
```

### TemporalTransaction

```java
// Methods
VertexRequest addVertex(String label)
EdgeRequest addEdge(String label, String fromId, String toId)

// VertexRequest methods
property(String key, Object value)
property(String key, Object value, Instant start, Instant end)
lifetime(Instant start, Instant end)

// EdgeRequest methods
property(String key, Object value)
property(String key, Object value, Instant start, Instant end)
lifetime(Instant start, Instant end)
```

## References

- [Allen Temporal Algebra](https://en.wikipedia.org/wiki/Allen's_interval_algebra)
- [Apache TinkerPop](https://tinkerpop.apache.org/)
- [Gremlin Query Language](https://tinkerpop.apache.org/gremlin.html)

## License

MIT License - See LICENSE file

## Contributing

Contributions welcome! Areas for improvement:

- Additional optimization passes
- Extended temporal semantics
- Performance micro-tuning
- Better error messages
- Documentation improvements

---

**Created for overhead analysis of temporal graph query translation approaches.**  
**Demonstrates trade-offs between translator-based flexibility and native implementation performance.**
