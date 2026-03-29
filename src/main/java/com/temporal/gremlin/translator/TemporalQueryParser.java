package com.temporal.gremlin.translator;

import com.temporal.gremlin.query.TemporalQuerySpec;
import com.temporal.gremlin.query.TemporalTransaction;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Parses temporal Gremlin query strings into TemporalQuerySpec or
 * TemporalTransaction objects.
 * Supports queries with lifetime() and Allen operators, as well as mutation
 * operations.
 * 
 * Example query formats:
 * - "g.V().hasLabel('Conference').lifetime(start, end).temporalBefore('Meeting')"
 * - "g.V().hasLabel('Event').temporalOverlaps('Meeting')"
 * - "g.V(123).temporalOverlaps()"
 * - "g.addV('Person').property('name', 'John').lifetime(start, end)"
 * - "g.addE('knows').from(v1).to(v2).lifetime(start, end)"
 */
public class TemporalQueryParser {

    // Pattern to match temporal queries with labels
    private static final Pattern LABEL_QUERY_PATTERN = Pattern.compile(
            "g\\.V\\(\\)\\.hasLabel\\(['\"]?([^'\"\\)]+)['\"]?\\)(?:\\.lifetime\\(([^,]+),\\s*([^)]+)\\))?\\.([a-zA-Z_]+)\\(['\"]?([^'\"\\)]+)['\"]?\\)");

    // Pattern to match reference vertex queries
    private static final Pattern REFERENCE_QUERY_PATTERN = Pattern.compile(
            "g\\.V\\(([^)]+)\\)(?:\\.lifetime\\(([^,]+),\\s*([^)]+)\\))?\\."
                    + "([a-zA-Z_]+)\\((?:['\"]?([^'\"\\)]+)['\"]?)?\\)");

    // Pattern to match addVertex operations
    private static final Pattern ADD_VERTEX_PATTERN = Pattern.compile(
            "g\\.addV\\(['\"]?([^'\"\\)]+)['\"]?\\)(.*)");

    // Pattern to match addEdge operations
    private static final Pattern ADD_EDGE_PATTERN = Pattern.compile(
            "g\\.addE\\(['\"]?([^'\"\\)]+)['\"]?\\)\\.from\\(([^)]+)\\)\\.to\\(([^)]+)\\)(.*)");

    // Pattern to match property definitions
    private static final Pattern PROPERTY_PATTERN = Pattern.compile(
            "\\.property\\(['\"]?([^'\",\\)]+)['\"]?,\\s*['\"]?([^'\",)]+)['\"]?(?:,\\s*([^,)]+),\\s*([^)]+))?\\)");

    // Pattern to match lifetime
    private static final Pattern LIFETIME_PATTERN = Pattern.compile(
            "\\.lifetime\\(([^,]+),\\s*([^)]+)\\)");

    /**
     * Parses a temporal query string into a TemporalQuerySpec.
     * 
     * @param queryString The temporal Gremlin query string
     * @return TemporalQuerySpec object representing the query
     * @throws IllegalArgumentException if the query string is invalid
     */
    public TemporalQuerySpec parse(String queryString) {
        Object result = parseQuery(queryString);
        if (result instanceof TemporalQuerySpec) {
            return (TemporalQuerySpec) result;
        }
        throw new IllegalArgumentException("Query is not a TemporalQuerySpec: " + queryString);
    }

    /**
     * Parses a temporal mutation string into a TemporalTransaction.
     * 
     * @param mutationString The temporal Gremlin mutation string
     * @return TemporalTransaction object representing the mutation
     * @throws IllegalArgumentException if the mutation string is invalid
     */
    public TemporalTransaction parseMutation(String mutationString) {
        Object result = parseQuery(mutationString);
        if (result instanceof TemporalTransaction) {
            return (TemporalTransaction) result;
        }
        throw new IllegalArgumentException("Query is not a TemporalTransaction: " + mutationString);
    }

    /**
     * Parses a query string and returns either TemporalQuerySpec or
     * TemporalTransaction.
     * 
     * @param queryString The query string to parse
     * @return Either TemporalQuerySpec or TemporalTransaction
     * @throws IllegalArgumentException if the query string is invalid
     */
    public Object parseQuery(String queryString) {
        if (queryString == null || queryString.trim().isEmpty()) {
            throw new IllegalArgumentException("Query string cannot be null or empty");
        }

        queryString = preprocess(queryString.trim());

        // Try to match addVertex pattern
        Matcher addVertexMatcher = ADD_VERTEX_PATTERN.matcher(queryString);
        if (addVertexMatcher.matches()) {
            return parseAddVertex(addVertexMatcher);
        }

        // Try to match addEdge pattern
        Matcher addEdgeMatcher = ADD_EDGE_PATTERN.matcher(queryString);
        if (addEdgeMatcher.matches()) {
            return parseAddEdge(addEdgeMatcher);
        }

        // Try to match reference vertex pattern
        Matcher refMatcher = REFERENCE_QUERY_PATTERN.matcher(queryString);
        if (refMatcher.matches()) {
            return parseReferenceQuery(refMatcher);
        }

        // Try to match label-based pattern
        Matcher labelMatcher = LABEL_QUERY_PATTERN.matcher(queryString);
        if (labelMatcher.matches()) {
            return parseLabelQuery(labelMatcher);
        }

        throw new IllegalArgumentException("Invalid temporal query format: " + queryString);
    }

    /**
     * Parses a label-based query (e.g., Conference BEFORE Meeting)
     */
    private TemporalQuerySpec parseLabelQuery(Matcher matcher) {
        String v1 = matcher.group(1);
        String lifetimeStart = matcher.group(2);
        String lifetimeEnd = matcher.group(3);
        String operatorStr = matcher.group(4);
        String v2 = matcher.group(5);

        TemporalQuerySpec.AllenOperator operator = parseOperator(operatorStr);
        TemporalQuerySpec spec = new TemporalQuerySpec(v1, v2, operator);

        if (lifetimeStart != null && lifetimeEnd != null) {
            Instant start = parseInstant(lifetimeStart);
            Instant end = parseInstant(lifetimeEnd);
            spec.withLifetime(start, end);
        }

        return spec;
    }

    /**
     * Parses a reference vertex query (e.g., g.V(123).temporalOverlaps('Meeting'))
     */
    private TemporalQuerySpec parseReferenceQuery(Matcher matcher) {
        String vertexId = matcher.group(1);
        String lifetimeStart = matcher.group(2);
        String lifetimeEnd = matcher.group(3);
        String operatorStr = matcher.group(4);
        String v1 = matcher.group(5); // Optional target vertex type

        TemporalQuerySpec.AllenOperator operator = parseOperator(operatorStr);

        Object id = parseVertexId(vertexId);

        TemporalQuerySpec spec = new TemporalQuerySpec(v1, null, operator)
                .withReferenceVertex(id);

        if (lifetimeStart != null && lifetimeEnd != null) {
            Instant start = parseInstant(lifetimeStart);
            Instant end = parseInstant(lifetimeEnd);
            spec.withLifetime(start, end);
        }

        return spec;
    }

    /**
     * Parses an Allen operator string into the enum value
     */
    private TemporalQuerySpec.AllenOperator parseOperator(String operatorStr) {
        try {
            return TemporalQuerySpec.AllenOperator.valueOf(operatorStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown Allen operator: " + operatorStr + 
                ". Expected format: temporalBefore, temporalAfter, temporalOverlaps, etc.");
        }
    }

    /**
     * Parses an Instant from various string formats
     */
    private Instant parseInstant(String instantStr) {
        instantStr = instantStr.trim();

        // Try parsing as epoch milliseconds
        try {
            long epochMilli = Long.parseLong(instantStr);
            return Instant.ofEpochMilli(epochMilli);
        } catch (NumberFormatException e) {
            // Try parsing as ISO-8601 format
            try {
                return Instant.parse(instantStr);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid instant format: " + instantStr);
            }
        }
    }

    /**
     * Parses a vertex ID (could be numeric or string)
     */
    private Object parseVertexId(String idStr) {
        idStr = idStr.trim();

        // Remove quotes if present
        if (idStr.startsWith("'") && idStr.endsWith("'")) {
            return idStr.substring(1, idStr.length() - 1);
        }

        // Try parsing as number
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return idStr;
        }
    }

    /**
     * Parses an addVertex operation
     */
    private TemporalTransaction parseAddVertex(Matcher matcher) {
        String label = matcher.group(1);
        String modifiers = matcher.group(2);

        TemporalTransaction tx = new TemporalTransaction();
        TemporalTransaction.VertexRequest vertex = tx.addVertex(label);

        // Parse properties and lifetime
        parseModifiers(modifiers, vertex, null);

        return tx;
    }

    /**
     * Parses an addEdge operation
     */
    private TemporalTransaction parseAddEdge(Matcher matcher) {
        String label = matcher.group(1);
        String fromId = matcher.group(2);
        String toId = matcher.group(3);
        String modifiers = matcher.group(4);

        TemporalTransaction tx = new TemporalTransaction();
        TemporalTransaction.EdgeRequest edge = tx.addEdge(label, fromId, toId);

        // Parse properties and lifetime
        parseModifiers(modifiers, null, edge);

        return tx;
    }

    /**
     * Parses modifiers (properties and lifetime) for vertex or edge
     */
    private void parseModifiers(String modifiers,
            TemporalTransaction.VertexRequest vertex,
            TemporalTransaction.EdgeRequest edge) {
        if (modifiers == null || modifiers.trim().isEmpty()) {
            return;
        }

        // Parse properties
        Matcher propMatcher = PROPERTY_PATTERN.matcher(modifiers);
        while (propMatcher.find()) {
            String key = propMatcher.group(1);
            String value = propMatcher.group(2);
            String lifetimeStart = propMatcher.group(3);
            String lifetimeEnd = propMatcher.group(4);

            // Remove quotes from value if present
            if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }

            if (lifetimeStart != null && lifetimeEnd != null) {
                Instant start = parseInstant(lifetimeStart);
                Instant end = parseInstant(lifetimeEnd);
                if (vertex != null) {
                    vertex.property(key, value, start, end);
                } else {
                    edge.property(key, value, start, end);
                }
            } else {
                if (vertex != null) {
                    vertex.property(key, value);
                } else {
                    edge.property(key, value);
                }
            }
        }

        // Parse lifetime
        Matcher lifetimeMatcher = LIFETIME_PATTERN.matcher(modifiers);
        if (lifetimeMatcher.find()) {
            String startStr = lifetimeMatcher.group(1);
            String endStr = lifetimeMatcher.group(2);
            Instant start = parseInstant(startStr);
            Instant end = parseInstant(endStr);

            if (vertex != null) {
                vertex.lifetime(start, end);
            } else {
                edge.lifetime(start, end);
            }
        }
    }

    /**
     * Preprocesses the query string to handle common shell-escaping mangling.
     */
    private String preprocess(String query) {
        // Handle strings like "hasLabel(\Conference\)"
        // Some shells translate escaped quotes or missing quotes into backslashes
        return query.replace("\\", "");
    }
}
