package com.temporal.gremlin.demo;

import com.temporal.gremlin.translator.TemporalGremlinTranslator;
import com.temporal.gremlin.translator.TemporalQueryStringTransformer;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

/**
 * Demo class to test the TemporalQueryStringTransformer
 */
public class StringTransformerDemo {

    public static void main(String[] args) {
        Graph graph = TinkerGraph.open();
        GraphTraversalSource g = graph.traversal();

        TemporalQueryStringTransformer transformer = new TemporalQueryStringTransformer(g);

        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║   Temporal Query String Transformer - Demo             ║");
        System.out.println("╚════════════════════════════════════════════════════════╝\n");

        // Test queries and mutations
        String[] testQueries = {
                "g.V().hasLabel('Conference').temporalBefore('Meeting')",
                "g.V().hasLabel('Event').temporalOverlaps('Meeting')",
                "g.V().hasLabel('Meeting').temporalDuring('Workshop')",
                "g.V(123).temporalOverlaps('Meeting')",
                "g.V(456).temporalBefore()",
                "g.addV('Person').property('name', 'John').property('age', 30).lifetime(1234567890000, 1234567900000)",
                "g.addE('knows').from(v1).to(v2).property('since', 2020).lifetime(1234567890000, 1234567900000)"
        };

        for (String query : testQueries) {
            System.out.println("┌─ Test Query ──────────────────────────────────────────┐");
            System.out.println("Input: " + query);
            System.out.println();

            try {
                TemporalGremlinTranslator.TranslationResult result = transformer.transform(query);

                System.out.println("Translation Successful!");
                System.out.println();

                System.out.println("Generated Steps:");
                for (int i = 0; i < result.optimizedSteps.size(); i++) {
                    System.out.println("  " + (i + 1) + ". " + result.optimizedSteps.get(i));
                }
                System.out.println();

                System.out.println("Bytecode:");
                System.out.println("  " + result.bytecode);
                System.out.println();

                System.out.printf("Metrics: %.3f ms | %d steps | %d bytes%n",
                        result.metrics.getTotalTime(),
                        result.optimizedSteps.size(),
                        result.metrics.getBytecodeSize());

            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }

            System.out.println("└───────────────────────────────────────────────────────┘");
            System.out.println();
        }

        transformer.close();

        try {
            g.close();
            graph.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
