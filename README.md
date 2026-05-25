# Gremlin Temporal Translator POC - Java Project

A comprehensive proof-of-concept demonstrating translation overhead measurement for temporal graph queries. This Java Maven project implements a 4-phase translator on top of Apache TinkerPop Gremlin, with both programmatic and string-based APIs for temporal query translation.

## Table of Contents

- [Features](#features)
- [Project Structure](#project-structure)
- [Quick Start](#quick-start)
- [String-Based Transformer](#string-based-transformer)
- [Programmatic API](#programmatic-api)
- [Allen Temporal Algebra](#allen-temporal-algebra)
- [Translation Pipeline](#translation-pipeline)
- [Performance & Optimization](#performance--optimization)
- [Examples](#examples)
- [Benchmarks](#benchmarks)

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
├── README.md                            # This file
│
└── src/main/java/com/temporal/gremlin/
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
    │   └── StringTransformerDemo.java   # String transformer demo
    │
    ├── executor/
    │   └── GremlinExecutor.java         # Query execution engine
    │
    └── bench/
        ├── TranslatorVsNativeBenchmark.java # Translation overhead & execution comparison
        ├── ChronoGraphBenchmark.java    # Scalability & Operator comparison (ChronoGraph vs Native)
        └── TemporalLifetimeStorageBenchmark.java # lifetime() storage read/write comparison
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

### 1. Translator vs Native Benchmark
Run the comprehensive overhead analysis:
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TranslatorVsNativeBenchmark"
```

**Results (10k Flights)**:
- **Translation Overhead**: Negligible (0.035 ms), mainly optimization & codegen.
- **Execution Overhead**: Translated bytecode is ~2-4x slower than hand-tuned Native Java loops.
- **Direct Query Construction**: Manual `g.E().has(...)` is often slower than the optimized bytecode execution.

### 2. Scalability Benchmark (ChronoGraph vs Native)
Run the scalability and operator comparison suite:
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.ChronoGraphBenchmark"
```

**BFS Scalability Results (25k Flights, Depth 3)**:
| Implementation | Time (ms) | Reachability | Speedup |
| :--- | :--- | :--- | :--- |
| **ChronoGraph** | 55.6 ms | 1122 | - |
| **Native T-Gremlin** | 18.7 ms | 1158 | **2.98x** |

**Observation**: Native T-TinkerPop traversal consistently outperforms ChronoGraph's reference implementation by ~3x across dataset sizes (1k-25k), proving scalability.

**Operator Micro-Benchmark (Linear Scan)**:
| Operator | ChronoGraph (ms) | Native T-Gremlin (ms) | Speedup |
| :--- | :--- | :--- | :--- |
| **temporalAfter** | 6.2 ms | 11.7 ms | 0.53x |
| **temporalOverlaps** | 6.7 ms | 13.4 ms | 0.50x |

**Observation**: For pure linear scans without indices, ChronoGraph's raw edge iteration is faster (~2x) than Gremlin's traversal overhead. However, real-world Native T-Gremlin usage would employ indices (`g.E().has("startTime", P.gt(x))`) to achieve O(log N) performance.

### 3. Temporal Lifetime Storage Benchmark
Run the lifetime storage comparison benchmark:
```bash
mvn exec:java -Dexec.mainClass="com.temporal.gremlin.bench.TemporalLifetimeStorageBenchmark"
```

This benchmark uses synthetic temporal edges written through `lifetime()` to
compare read and write costs across lifetime storage implementations. It is
intended to be run on both the old string-backed storage implementation and the
new Date-backed storage implementation, then compared by average milliseconds.

**Output sections**:
- **CORRECTNESS**: Shows mixed date formats where raw string comparison differs from parsed date comparison.
- **MAIN**: Measures the `AllenFilterStep` parse-on-read path, which is the key read benchmark.
- **BASELINE**: Measures the public Allen DSL `has()+P` path, where predicates compare property values directly.
- **OTHER**: Measures another parse-on-read path through `temporalPageRank`.
- **WRITE**: Measures `lifetime()` write-time normalization cost.

## Testing

Run the test suite:

```bash
mvn test
```

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
