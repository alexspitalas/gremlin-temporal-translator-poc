package com.temporal.gremlin.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a set of temporal graph mutations (additions of vertices and
 * edges).
 * Allows specifying lifetime for each element.
 */
public class TemporalTransaction {

    private List<Mutation> mutations = new ArrayList<>();

    public interface Mutation {
        void accept(MutationVisitor visitor);
    }

    public interface MutationVisitor {
        void visit(VertexRequest vertex);

        void visit(EdgeRequest edge);
    }

    public static class PropertySpec {
        private Object value;
        private Instant lifetimeStart;
        private Instant lifetimeEnd;

        public PropertySpec(Object value) {
            this.value = value;
        }

        public PropertySpec(Object value, Instant start, Instant end) {
            this.value = value;
            this.lifetimeStart = start;
            this.lifetimeEnd = end;
        }

        public Object getValue() {
            return value;
        }

        public Instant getLifetimeStart() {
            return lifetimeStart;
        }

        public Instant getLifetimeEnd() {
            return lifetimeEnd;
        }
    }

    public static class VertexRequest implements Mutation {
        private String label;
        private Instant lifetimeStart;
        private Instant lifetimeEnd;
        private Map<String, PropertySpec> properties = new HashMap<>();

        public VertexRequest(String label) {
            this.label = label;
        }

        public VertexRequest lifetime(Instant start, Instant end) {
            this.lifetimeStart = start;
            this.lifetimeEnd = end;
            return this;
        }

        public VertexRequest property(String key, Object value) {
            this.properties.put(key, new PropertySpec(value));
            return this;
        }

        public VertexRequest property(String key, Object value, Instant start, Instant end) {
            this.properties.put(key, new PropertySpec(value, start, end));
            return this;
        }

        public String getLabel() {
            return label;
        }

        public Instant getLifetimeStart() {
            return lifetimeStart;
        }

        public Instant getLifetimeEnd() {
            return lifetimeEnd;
        }

        public Map<String, PropertySpec> getProperties() {
            return properties;
        }

        @Override
        public void accept(MutationVisitor visitor) {
            visitor.visit(this);
        }
    }

    public static class EdgeRequest implements Mutation {
        private String label;
        private Object fromVertexId;
        private Object toVertexId;
        private Instant lifetimeStart;
        private Instant lifetimeEnd;
        private Map<String, PropertySpec> properties = new HashMap<>();

        public EdgeRequest(String label, Object from, Object to) {
            this.label = label;
            this.fromVertexId = from;
            this.toVertexId = to;
        }

        public EdgeRequest lifetime(Instant start, Instant end) {
            this.lifetimeStart = start;
            this.lifetimeEnd = end;
            return this;
        }

        public EdgeRequest property(String key, Object value) {
            this.properties.put(key, new PropertySpec(value));
            return this;
        }

        public EdgeRequest property(String key, Object value, Instant start, Instant end) {
            this.properties.put(key, new PropertySpec(value, start, end));
            return this;
        }

        public String getLabel() {
            return label;
        }

        public Object getFromVertexId() {
            return fromVertexId;
        }

        public Object getToVertexId() {
            return toVertexId;
        }

        public Instant getLifetimeStart() {
            return lifetimeStart;
        }

        public Instant getLifetimeEnd() {
            return lifetimeEnd;
        }

        public Map<String, PropertySpec> getProperties() {
            return properties;
        }

        @Override
        public void accept(MutationVisitor visitor) {
            visitor.visit(this);
        }
    }

    public VertexRequest addVertex(String label) {
        VertexRequest req = new VertexRequest(label);
        mutations.add(req);
        return req;
    }

    public EdgeRequest addEdge(String label, Object fromId, Object toId) {
        EdgeRequest req = new EdgeRequest(label, fromId, toId);
        mutations.add(req);
        return req;
    }

    public List<Mutation> getMutations() {
        return mutations;
    }
}
