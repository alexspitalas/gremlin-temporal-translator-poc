package com.temporal.gremlin.util;

import com.temporal.gremlin.query.TemporalTransaction;
import com.temporal.gremlin.translator.TemporalGremlinTranslator;
import com.temporal.gremlin.executor.GremlinExecutor;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for loading datasets into the graph with temporal properties.
 */
public class GraphDataLoader {

    private final GraphTraversalSource g;
    private final TemporalGremlinTranslator translator;
    private final GremlinExecutor executor;
    private final Set<String> processedAirports = new HashSet<>();
    private final Map<String, Object> airportIdCache = new HashMap<>();

    // Formatter for "2018-12-31 00:43:16+00:00"
    public static final DateTimeFormatter CSV_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    public GraphDataLoader(GraphTraversalSource g) {
        this.g = g;
        this.translator = new TemporalGremlinTranslator(g);
        this.executor = new GremlinExecutor(g);
    }

    /**
     * Loads the flight dataset from a CSV file.
     * Airports are vertices, flights are edges.
     * 
     * @param csvPath Path to the CSV file.
     * @param limit   Maximum number of flights to load (-1 for all).
     */
    public void load_dataset_flight_translator(String csvPath, int limit) {
        System.out.println("Loading flight dataset from: " + csvPath);
        long startTime = System.currentTimeMillis();
        int count = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String header = br.readLine(); // skip header
            String line;

            while ((line = br.readLine()) != null && (limit == -1 || count < limit)) {
                // simple CSV splitting (assuming no commas in fields)
                String[] parts = line.split(",");
                if (parts.length < 9)
                    continue;

                String callsign = parts[0];
                String origin = parts[5];
                String destination = parts[6];
                String firstseen = parts[7];
                String lastseen = parts[8];

                if (origin.isEmpty() || destination.isEmpty())
                    continue;

                Instant start = parseInstant(firstseen);
                Instant end = parseInstant(lastseen);

                Object fromId = ensureAirport(origin);
                Object toId = ensureAirport(destination);

                addFlightEdge(fromId, toId, callsign, start, end);

                count++;
                if (count % 10000 == 0) {
                    System.out.println("  Processed " + count + " flights...");
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading flight data: " + e.getMessage());
            e.printStackTrace();
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Successfully loaded " + count + " flights and " + processedAirports.size()
                + " airports in " + duration + "ms.");
    }

    private Instant parseInstant(String s) {
        try {
            return OffsetDateTime.parse(s, CSV_DATE_FORMATTER).toInstant();
        } catch (Exception e) {
            // Return current time if parsing fails
            return Instant.now();
        }
    }

    private Object ensureAirport(String code) {
        if (airportIdCache.containsKey(code)) {
            return airportIdCache.get(code);
        }

        Vertex v;
        if (g.V().has("Airport", "code", code).hasNext()) {
            v = g.V().has("Airport", "code", code).next();
        } else {
            v = g.addV("Airport").property("code", code).next();
            processedAirports.add(code);
        }

        Object id = v.id();
        airportIdCache.put(code, id);
        return id;
    }

    private void addFlightEdge(Object fromId, Object toId, String callsign, Instant start, Instant end) {
        TemporalTransaction tx = new TemporalTransaction();

        tx.addEdge("flight", fromId, toId)
                .property("callsign", callsign)
                .lifetime(start, end);
        TemporalGremlinTranslator.TranslationResult result = translator.translate(tx);
        executor.execute(result.bytecode);

    }

    /**
     * Native loading approach for Graph A comparison.
     * Uses direct g.addE() calls instead of the translator.
     */
    public void load_dataset_flight_native(String csvPath, int limit) {
        System.out.println("Loading flight dataset (NATIVE) from: " + csvPath);
        int count = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null && (limit == -1 || count < limit)) {
                try {
                    String[] parts = line.split(",");
                    if (parts.length < 9)
                        continue;
                    String origin = parts[5];
                    String destination = parts[6];
                    if (origin.isEmpty() || destination.isEmpty())
                        continue;

                    Instant start = parseInstant(parts[7]);
                    Instant end = parseInstant(parts[8]);

                    Object fromId = ensureAirport(origin);
                    Object toId = ensureAirport(destination);

                    // Format with millisecond precision (3 decimal places)
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                            .withZone(java.time.ZoneOffset.UTC);
                    String startStr = formatter.format(start);
                    String endStr = formatter.format(end);

                    g.addE("flight")
                            .from(g.V(fromId).next())
                            .to(g.V(toId).next())
                            .property("callsign", parts[0])
                            .lifetime(startStr, endStr)
                            .iterate();

                    count++;
                } catch (Exception e) {
                    System.err.println("Error processing line: " + line);
                    e.printStackTrace();
                    // Don't break the whole loading process
                }
            }
        } catch (Exception e) {
            System.err.println("Critical error in native load: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Native load complete: " + count + " flights.");
    }

}