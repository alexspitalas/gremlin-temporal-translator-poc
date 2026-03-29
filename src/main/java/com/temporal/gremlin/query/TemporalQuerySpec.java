package com.temporal.gremlin.query;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a temporal query specification before translation to Gremlin.
 * Encapsulates the domain-specific query semantics.
 */
public class TemporalQuerySpec {

    private String vertex1, vertex2;
    private AllenOperator operator;
    private Instant lifetimeStart;
    private Instant lifetimeEnd;
    private String constraints;
    private QueryType queryType;
    private ElementType elementType = ElementType.VERTEX;
    private Map<String, Object> metadata;
    private Object referenceVertexId;

    public enum ElementType {
        VERTEX,
        EDGE
    }

    public enum AllenOperator {
        temporalBefore, // i1 before i2: i1.end < i2.start
        temporalAfter, // i1 after i2: i1.start > i2.end
        temporalOverlaps, // i1 overlaps i2: i1.start < i2.end && i1.end > i2.start
        temporalDuring, // i1 during i2: i1.start >= i2.start && i1.end <= i2.end
        temporalStarts, // i1 starts i2: i1.start == i2.start && i1.end < i2.end
        temporalEnds, // i1 ends i2: i1.start > i2.start && i1.end == i2.end
        temporalMeets, // i1 meets i2: i1.end == i2.start
        temporalStartedBy, // i1 started by i2: i2.start == i1.start && i2.end < i1.end
        temporalEndedBy, // i1 ended by i2: i2.start > i1.start && i2.end == i1.end
        temporalContains, // i1 contains i2: i2.start >= i1.start && i2.end <= i1.end
        temporalOverlappedBy, // i1 overlapped by i2: i2.start < i1.end && i2.end > i1.start
        temporalMetBy, // i1 met by i2: i2.end == i1.start
        temporalEqual // i1 equal i2: i1.start == i2.start && i1.end == i2.end
    }

    public enum QueryType {
        PATH,
        RELATIONSHIP,
        SLICE,
        INTERVAL
    }

    public TemporalQuerySpec(String v1, String v2, AllenOperator operator) {
        this.vertex1 = v1;
        this.vertex2 = v2;
        this.operator = operator;
        this.queryType = QueryType.RELATIONSHIP;
        this.metadata = new HashMap<>();
    }

    public TemporalQuerySpec withLifetime(Instant start, Instant end) {
        this.lifetimeStart = start;
        this.lifetimeEnd = end;
        return this;
    }

    public TemporalQuerySpec withConstraints(String constraints) {
        this.constraints = constraints;
        return this;
    }

    public TemporalQuerySpec withQueryType(QueryType type) {
        this.queryType = type;
        return this;
    }

    public TemporalQuerySpec withMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public TemporalQuerySpec withElementType(ElementType type) {
        this.elementType = type;
        return this;
    }

    public String getV1() {
        return vertex1;
    }

    public String getV2() {
        return vertex2;
    }

    public AllenOperator getOperator() {
        return operator;
    }

    public Instant getLifetimeStart() {
        return lifetimeStart;
    }

    public Instant getLifetimeEnd() {
        return lifetimeEnd;
    }

    public String getConstraints() {
        return constraints;
    }

    public QueryType getQueryType() {
        return queryType;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public ElementType getElementType() {
        return elementType;
    }

    public TemporalQuerySpec withReferenceVertex(Object id) {
        this.referenceVertexId = id;
        return this;
    }

    public Object getReferenceVertexId() {
        return referenceVertexId;
    }

    @Override
    public String toString() {
        return "TemporalQuerySpec{" +
                "vertices=" + vertex1 + " " + vertex2 +
                ", operator=" + operator +
                ", lifetime=[" + lifetimeStart + ", " + lifetimeEnd + "]" +
                ", constraints='" + constraints + '\'' +
                ", queryType=" + queryType +
                '}';
    }
}
