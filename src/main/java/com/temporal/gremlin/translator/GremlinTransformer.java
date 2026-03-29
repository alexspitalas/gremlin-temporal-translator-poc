package com.temporal.gremlin.translator;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase 2: Transforms AST into Gremlin traversal steps.
 * Maps temporal semantics to TinkerPop traversal operations.
 */
public class GremlinTransformer {

        private TranslationMetrics metrics;
        private GraphTraversalSource g;

        public GremlinTransformer(TranslationMetrics metrics, GraphTraversalSource g) {
                this.metrics = metrics;
                this.g = g;
        }

        /**
         * Transforms TemporalQuerySpec into Gremlin steps.
         */
        public List<GremlinStep> transform(com.temporal.gremlin.query.TemporalQuerySpec spec) {
                long startTime = System.nanoTime();

                List<GremlinStep> steps = new ArrayList<>();

                String v1 = spec.getV1();
                String v2 = spec.getV2();

                String startTraversal = spec.getElementType() == com.temporal.gremlin.query.TemporalQuerySpec.ElementType.EDGE ? "g.E()" : "g.V()";
                String subTraversal = spec.getElementType() == com.temporal.gremlin.query.TemporalQuerySpec.ElementType.EDGE ? "E()" : "V()";

                if (spec.getReferenceVertexId() != null) {
                    String refStart = spec.getElementType() == com.temporal.gremlin.query.TemporalQuerySpec.ElementType.EDGE ? "g.E(" : "g.V(";
                    steps.add(new GremlinStep(
                            refStart + formatValue(spec.getReferenceVertexId()) + ")",
                            "Start with reference: " + spec.getReferenceVertexId()));
                    steps.add(new GremlinStep(
                            "as('v2')",
                            "Label reference V2 as 'v2'"));

                    if (v1 != null) {
                        steps.add(new GremlinStep(
                                subTraversal,
                                "Start new traversal for V1"));
                        steps.add(new GremlinStep(
                                "hasLabel('" + v1 + "')",
                                "Filter for V1: " + v1));
                        steps.add(new GremlinStep(
                                "as('v1')",
                                "Label V1 as 'v1'"));
                    } else {
                        steps.add(new GremlinStep(
                                subTraversal,
                                "Start new traversal for V1"));
                        steps.add(new GremlinStep(
                                "as('v1')",
                                "Label V1 as 'v1'"));
                    }
                } else {
                    if (v1 == null || v2 == null) {
                        throw new RuntimeException("No vertices/edges specified in query");
                    }
                    steps.add(new GremlinStep(startTraversal, "Initialize traversal"));
                    steps.add(new GremlinStep(
                            "hasLabel('" + v1 + "')",
                            "Filter for V1: " + v1));
                    steps.add(new GremlinStep(
                            "as('v1')",
                            "Label V1 as 'v1'"));

                    steps.add(new GremlinStep(
                            subTraversal,
                            "Start new traversal for V2"));
                    steps.add(new GremlinStep(
                            "hasLabel('" + v2 + "')",
                            "Filter for V2: " + v2));
                    steps.add(new GremlinStep(
                            "as('v2')",
                            "Label V2 as 'v2'"));
                }

                // Transform temporal constraint
                com.temporal.gremlin.query.TemporalQuerySpec.AllenOperator operator = spec.getOperator();
                if (operator != null) {
                        List<GremlinStep> temporalSteps = transformTemporalConstraint(operator);
                        steps.addAll(temporalSteps);
                }

                // Add custom filters
                steps.add(new GremlinStep(
                                "select('v1', 'v2')",
                                "Return both V1 and V2"));

                if (spec.getConstraints() != null && !spec.getConstraints().isEmpty()) {
                        steps.add(new GremlinStep(
                                        "filter { " + spec.getConstraints() + " }",
                                        "Apply custom constraint"));
                }

                long endTime = System.nanoTime();
                metrics.recordTransformTime((endTime - startTime) / 1_000_000.0);

                return steps;
        }

        /**
         * Transforms temporal constraints to Gremlin steps.
         * Each Allen operator decomposes into filter steps.
         */
        private List<GremlinStep> transformTemporalConstraint(com.temporal.gremlin.query.TemporalQuerySpec.AllenOperator operator) {
                List<GremlinStep> steps = new ArrayList<>();

                switch (operator) {
                        case temporalBefore:
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('endTime').by('startTime')",
                                                "temporalBefore: v1 ends before v2 starts"));
                                steps.add(new GremlinStep(
                                                "order(Scope.global).by('endTime')",
                                                "Order by end time"));
                                break;

                        case temporalAfter:
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('startTime').by('endTime')",
                                                "temporalAfter: v1 starts after v2 ends"));
                                steps.add(new GremlinStep(
                                                "order(Scope.global).by('startTime')",
                                                "Order by start time"));
                                break;

                        case temporalOverlaps:
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('startTime').by('startTime')",
                                                "temporalOverlaps: v1.start < v2.start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('endTime').by('startTime')",
                                                "temporalOverlaps: v1.end > v2.start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('endTime').by('endTime')",
                                                "temporalOverlaps: v1.end < v2.end"));
                                break;

                        case temporalDuring:
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('startTime').by('startTime')",
                                                "temporalDuring: v1 start > v2 start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('endTime').by('endTime')",
                                                "temporalDuring: v1 end < v2 end"));
                                break;

                        case temporalStarts:
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('startTime').by('startTime')",
                                                "temporalStarts: same start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('endTime').by('endTime')",
                                                "temporalStarts: v1 ends before v2"));
                                break;

                        case temporalEnds:
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('startTime').by('startTime')",
                                                "temporalEnds: v1 starts after v2"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('endTime').by('endTime')",
                                                "temporalEnds: same end"));
                                break;

                        case temporalMeets:
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('endTime').by('startTime')",
                                                "temporalMeets: v1 immediately before v2"));
                                steps.add(new GremlinStep(
                                                "order(Scope.global).by('endTime')",
                                                "Order by end time"));
                                break;

                        case temporalStartedBy:
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('startTime').by('startTime')",
                                                "temporalStartedBy: same start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('endTime').by('endTime')",
                                                "temporalStartedBy: v1 ends after v2"));
                                break;

                        case temporalEndedBy:
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('endTime').by('endTime')",
                                                "temporalEndedBy: same end"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('startTime').by('startTime')",
                                                "temporalEndedBy: v1 starts before v2"));
                                break;

                        case temporalContains:
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('startTime').by('startTime')",
                                                "temporalContains: v1 start < v2 start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('endTime').by('endTime')",
                                                "temporalContains: v1 end > v2 end"));
                                break;

                        case temporalOverlappedBy:
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('startTime').by('startTime')",
                                                "temporalOverlappedBy: v1 start > v2 start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.lt('v2')).by('startTime').by('endTime')",
                                                "temporalOverlappedBy: v1 start < v2 end"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.gt('v2')).by('endTime').by('endTime')",
                                                "temporalOverlappedBy: v1 end > v2 end"));
                                break;

                        case temporalMetBy:
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('startTime').by('endTime')",
                                                "temporalMetBy: v2 immediately before v1"));
                                break;

                        case temporalEqual:
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('startTime').by('startTime')",
                                                "temporalEqual: same start"));
                                steps.add(new GremlinStep(
                                                "where('v1', P.eq('v2')).by('endTime').by('endTime')",
                                                "temporalEqual: same end"));
                                break;

                        default:
                                steps.add(new GremlinStep(
                                                "identity()",
                                                "Unknown operator: " + operator));
                }

                return steps;
        }

        /**
         * Transforms TemporalTransaction into Gremlin mutation steps.
         */
        public List<GremlinStep> transform(com.temporal.gremlin.query.TemporalTransaction transaction) {
                long startTime = System.nanoTime();
                List<GremlinStep> steps = new ArrayList<>();

                for (com.temporal.gremlin.query.TemporalTransaction.Mutation mutation : transaction.getMutations()) {
                        mutation.accept(new com.temporal.gremlin.query.TemporalTransaction.MutationVisitor() {
                                @Override
                                public void visit(com.temporal.gremlin.query.TemporalTransaction.VertexRequest vertex) {
                                        steps.add(new GremlinStep(
                                                        "addV('" + vertex.getLabel() + "')",
                                                        "Add vertex: " + vertex.getLabel()));
                                        if (vertex.getLifetimeStart() != null && vertex.getLifetimeEnd() != null) {
                                                String start = formatInstant(vertex.getLifetimeStart());
                                                String end = formatInstant(vertex.getLifetimeEnd());
                                                steps.add(new GremlinStep(
                                                                "property('startTime', '" + start + "')",
                                                                "Set lifetime start"));
                                                steps.add(new GremlinStep(
                                                                "property('endTime', '" + end + "')",
                                                                "Set lifetime end"));
                                        } else if (vertex.getLifetimeStart() != null) {
                                                String start = formatInstant(vertex.getLifetimeStart());
                                                steps.add(new GremlinStep(
                                                                "property('startTime', '" + start + "')",
                                                                "Set lifetime start"));
                                        } else if (vertex.getLifetimeEnd() != null) {
                                                String end = formatInstant(vertex.getLifetimeEnd());
                                                steps.add(new GremlinStep(
                                                                "property('endTime', '" + end + "')",
                                                                "Set lifetime end"));
                                        }
                                        for (Map.Entry<String, com.temporal.gremlin.query.TemporalTransaction.PropertySpec> prop : vertex
                                                        .getProperties().entrySet()) {
                                                com.temporal.gremlin.query.TemporalTransaction.PropertySpec spec = prop
                                                                .getValue();
                                                StringBuilder sb = new StringBuilder();
                                                sb.append("property('").append(prop.getKey()).append("', ")
                                                                .append(formatValue(spec.getValue()));

                                                // Add meta-properties if lifetime exists (Vertex only)
                                                if (spec.getLifetimeStart() != null && spec.getLifetimeEnd() != null) {
                                                        String start = formatInstant(spec.getLifetimeStart());
                                                        String end = formatInstant(spec.getLifetimeEnd());
                                                        sb.append(", 'startTime', '").append(start).append("'")
                                                          .append(", 'endTime', '").append(end).append("'");
                                                } else if (spec.getLifetimeStart() != null) {
                                                        String start = formatInstant(spec.getLifetimeStart());
                                                        sb.append(", 'startTime', '").append(start).append("'");
                                                } else if (spec.getLifetimeEnd() != null) {
                                                        String end = formatInstant(spec.getLifetimeEnd());
                                                        sb.append(", 'endTime', '").append(end).append("'");
                                                }
                                                sb.append(")");

                                                steps.add(new GremlinStep(
                                                                sb.toString(),
                                                                "Set property: " + prop.getKey()
                                                                                + (spec.getLifetimeStart() != null
                                                                                                ? " (with lifetime)"
                                                                                                : "")));
                                        }
                                }

                                @Override
                                public void visit(com.temporal.gremlin.query.TemporalTransaction.EdgeRequest edge) {
                                        steps.add(new GremlinStep(
                                                        "addE('" + edge.getLabel() + "')",
                                                        "Add edge: " + edge.getLabel()));
                                        steps.add(new GremlinStep(
                                                        "from(V(" + formatValue(edge.getFromVertexId()) + "))",
                                                        "From vertex"));
                                        steps.add(new GremlinStep(
                                                        "to(V(" + formatValue(edge.getToVertexId()) + "))",
                                                        "To vertex"));
                                        if (edge.getLifetimeStart() != null && edge.getLifetimeEnd() != null) {
                                                String start = formatInstant(edge.getLifetimeStart());
                                                String end = formatInstant(edge.getLifetimeEnd());
                                                steps.add(new GremlinStep(
                                                                "property('startTime', '" + start + "')",
                                                                "Set lifetime start"));
                                                steps.add(new GremlinStep(
                                                                "property('endTime', '" + end + "')",
                                                                "Set lifetime end"));
                                        } else if (edge.getLifetimeStart() != null) {
                                                String start = formatInstant(edge.getLifetimeStart());
                                                steps.add(new GremlinStep(
                                                                "property('startTime', '" + start + "')",
                                                                "Set lifetime start"));
                                        } else if (edge.getLifetimeEnd() != null) {
                                                String end = formatInstant(edge.getLifetimeEnd());
                                                steps.add(new GremlinStep(
                                                                "property('endTime', '" + end + "')",
                                                                "Set lifetime end"));
                                        }
                                        for (Map.Entry<String, com.temporal.gremlin.query.TemporalTransaction.PropertySpec> prop : edge
                                                        .getProperties().entrySet()) {
                                                // Edge properties do not support meta-properties in standard Gremlin
                                                steps.add(new GremlinStep(
                                                                "property('" + prop.getKey() + "', "
                                                                                + formatValue(prop.getValue()
                                                                                                .getValue())
                                                                                + ")",
                                                                "Set property: " + prop.getKey()));
                                        }
                                }
                        });
                }

                // Ensure the first step starts with "g." to create a valid traversal
                if (!steps.isEmpty()) {
                        GremlinStep first = steps.get(0);
                        if (!first.code.startsWith("g.")) {
                                steps.set(0, new GremlinStep("g." + first.code, first.description));
                        }
                }

                long endTime = System.nanoTime();
                metrics.recordTransformTime((endTime - startTime) / 1_000_000.0);
                return steps;
	}

	/**
	 * Formats an Instant to a string with millisecond precision. 3 decimal places
	 * The format is yyyy-MM-dd'T'HH:mm:ss.SSS which is compatible with t-TinkerPop's expected formats.
	 */
	private String formatInstant(Instant instant) {
		if (instant == null) {
			return null;
		}
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
				.withZone(ZoneOffset.UTC);
		return formatter.format(instant);
	}

	private String formatValue(Object value) {
                if (value instanceof String) {
                        return "'" + value + "'";
                }
                if (value instanceof Long) {
                        return value.toString() + "L";
                }
                return value.toString();
        }

        private String formatVertexList(List<String> vertices) {
                return "[" + String.join(", ", vertices.stream()
                                .map(v -> "\"" + v + "\"")
                                .toArray(String[]::new)) + "]";
        }

        /**
         * Represents a single Gremlin traversal step with metadata.
         */
        public static class GremlinStep {
                public final String code;
                public final String description;

                public GremlinStep(String code, String description) {
                        this.code = code;
                        this.description = description;
                }

                @Override
                public String toString() {
                        return code + " // " + description;
                }
        }
}
