package com.temporal.gremlin.executor;

import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import javax.script.Bindings;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

/**
 * Takes bytecode strings (like "g.V().has('name', 'value')")
 * Uses GremlinGroovyScriptEngine to execute bytecode strings.
 */
public class GremlinExecutor {

    private final GraphTraversalSource g;
    private final GremlinGroovyScriptEngine engine;

    /**
     * Creates executor with graph traversal source
     * 
     * @param g The GraphTraversalSource to execute queries against
     */
    public GremlinExecutor(GraphTraversalSource g) {
        this.g = g;
        this.engine = new GremlinGroovyScriptEngine();
    }

    /**
     * Execute bytecode string and return results.
     *
     * @param bytecode The Gremlin bytecode string to execute
     *                 Example: "g.V().has('name', 'value')"
     * @return List of objects matching the query
     */
    public List<Object> execute(String bytecode) {
        try {
            Bindings bindings = engine.createBindings();
            bindings.put("g", g);
            bindings.put("Scope", org.apache.tinkerpop.gremlin.process.traversal.Scope.class);

            Object result = engine.eval(bytecode, bindings);
            List<Object> results = new ArrayList<>();

            if (result instanceof Iterable) {
                for (Object item : (Iterable<?>) result) {
                    results.add(item);
                }
            } else if (result instanceof Iterator) {
                Iterator<?> it = (Iterator<?>) result;
                while (it.hasNext()) {
                    results.add(it.next());
                }
            } else {
                // Single result
                results.add(result);
            }

            return results;
        } catch (ScriptException e) {
            System.err.println("Execution error: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * Execute bytecode and return iterator without materializing results.
     *
     * @param bytecode The Gremlin bytecode string to execute
     * @return Iterator over results (not materialized into memory)
     */
    public Iterator<?> executeIterator(String bytecode) {
        try {
            Bindings bindings = engine.createBindings();
            bindings.put("g", g);
            bindings.put("Scope", org.apache.tinkerpop.gremlin.process.traversal.Scope.class);

            Object result = engine.eval(bytecode, bindings);

            if (result instanceof Iterator) {
                return (Iterator<?>) result;
            } else if (result instanceof Iterable) {
                return ((Iterable<?>) result).iterator();
            } else {
                // Single result - wrap in single-element iterator
                return List.of(result).iterator();
            }
        } catch (ScriptException e) {
            System.err.println("Execution error: " + e.getMessage());
            e.printStackTrace();
            return List.of().iterator();
        }
    }

    /**
     * Execute bytecode without materializing results (similar to .iterate() in Gremlin).
     * Used for benchmarking to avoid the overhead of collecting results into a list.
     *
     * @param bytecode The Gremlin bytecode string to execute
     */
    public void executeIterate(String bytecode) {
        try {
            Bindings bindings = engine.createBindings();
            bindings.put("g", g);
            bindings.put("Scope", org.apache.tinkerpop.gremlin.process.traversal.Scope.class);

            Object result = engine.eval(bytecode, bindings);

            // Consume the iterator without materializing into a list
            if (result instanceof Iterator) {
                Iterator<?> it = (Iterator<?>) result;
                while (it.hasNext()) {
                    it.next();
                }
            } else if (result instanceof Iterable) {
                for (Object item : (Iterable<?>) result) {
                }
            }
        } catch (ScriptException e) {
            System.err.println("Execution error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Execute bytecode with detailed metrics and error tracking.
     *
     * @param bytecode The Gremlin bytecode string to execute
     * @return ExecutionResult containing results, success flag, timing, and errors
     */
    public ExecutionResult executeWithMetrics(String bytecode) {
        long startTime = System.nanoTime();

        try {
            List<Object> results = execute(bytecode);

            long endTime = System.nanoTime();
            long executionTimeMs = (endTime - startTime) / 1_000_000;

            return new ExecutionResult(true, results, executionTimeMs, null);
        } catch (Exception e) {
            long endTime = System.nanoTime();
            long executionTimeMs = (endTime - startTime) / 1_000_000;

            return new ExecutionResult(false, List.of(), executionTimeMs, e.getMessage());
        }
    }

    /**
     * Container for execution results with metrics.
     *
     * Provides success status, result objects, execution time, and error messages.
     */
    public static class ExecutionResult {
        public final boolean success;
        public final List<Object> results;
        public final long executionTimeMs;
        public final String errorMessage;

        /**
         * Creates execution result
         */
        public ExecutionResult(boolean success, List<Object> results, long executionTimeMs, String errorMessage) {
            this.success = success;
            this.results = results;
            this.executionTimeMs = executionTimeMs;
            this.errorMessage = errorMessage;
        }

        /**
         * Pretty print execution results
         */
        public void printReport() {
            if (success) {
                System.out.println(String.format("Execution successful in %d ms", executionTimeMs));
                System.out.println(String.format("  Found %d results", results.size()));

                if (!results.isEmpty()) {
                    System.out.println("  Results:");
                    for (Object res : results) {
                        if (res instanceof Vertex) {
                            printVertex((Vertex) res, "    - Vertex: ");
                        } else if (res instanceof java.util.Map) {
                            java.util.Map<?, ?> map = (java.util.Map<?, ?>) res;
                            System.out.print("    - Match: { ");
                            boolean first = true;
                            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                                if (!first)
                                    System.out.print(", ");
                                System.out.print(entry.getKey() + "=");
                                if (entry.getValue() instanceof Vertex) {
                                    printVertex((Vertex) entry.getValue(), "");
                                } else {
                                    System.out.print(entry.getValue());
                                }
                                first = false;
                            }
                            System.out.println(" }");
                        } else {
                            System.out.println(String.format("    - %s", res.toString()));
                        }
                    }
                }
            } else {
                System.out.println("Execution failed in " + executionTimeMs + "ms");
                System.out.println("  Error: " + errorMessage);
            }
        }

        private void printVertex(Vertex v, String prefix) {
            String name = v.property("name").isPresent() ? v.property("name").value().toString() : "unknown";
            String startTime = v.property("startTime").isPresent()
                    ? v.property("startTime").value().toString()
                    : "unknown";
            String endTime = v.property("endTime").isPresent() ? v.property("endTime").value().toString()
                    : "unknown";
            System.out.println(prefix + String.format("%s (%s, id=%s), startTime = %s, endTime = %s", name,
                    v.label(), v.id(), startTime, endTime));
        }
    }
}