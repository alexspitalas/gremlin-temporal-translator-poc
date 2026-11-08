#!/usr/bin/env bash
# ==============================================================================
# T-TinkerPop Benchmarking & Correctness Suite CLI Runner
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

# Ensure datasets directory has symlink if file exists in root
if [ -f "flightlist_20190101_20190131.csv" ] && [ ! -e "datasets/flightlist_20190101_20190131.csv" ]; then
    mkdir -p datasets
    ln -sf ../flightlist_20190101_20190131.csv datasets/flightlist_20190101_20190131.csv 2>/dev/null || true
fi

# JVM Memory configuration (sufficient for up to 1M elements graph benchmarks)
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g -XX:+UseG1GC}"

# Fast compilation check if target does not exist
if [ ! -d "target/classes" ] || [ ! -d "target/test-classes" ]; then
    echo "[bench] Compiling project modules..."
    mvn test-compile -q -DskipTests
fi

# Run BenchmarkSuite via Maven exec
if [ $# -eq 0 ]; then
    # Interactive mode (no args provided)
    mvn exec:java -q -Dexec.mainClass="com.temporal.gremlin.benchmark.BenchmarkSuite"
else
    # Non-interactive CLI argument mode
    mvn exec:java -q -Dexec.mainClass="com.temporal.gremlin.benchmark.BenchmarkSuite" -Dexec.args="$*"
fi
