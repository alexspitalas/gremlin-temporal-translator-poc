package com.temporal.gremlin.benchmark;

/**
 * Standard interface for benchmark tasks executable in the suite.
 */
public interface BenchmarkTask {

    /**
     * Unique identifier used in CLI arguments (e.g. "chronograph", "pagerank").
     */
    String getId();

    /**
     * Human-readable display name.
     */
    String getName();

    /**
     * Description of what this benchmark measures.
     */
    String getDescription();

    /**
     * Primary logical group this benchmark belongs to.
     */
    BenchmarkGroup getGroup();

    /**
     * Execute the benchmark with the provided context.
     */
    void run(BenchmarkContext ctx) throws Exception;
}
