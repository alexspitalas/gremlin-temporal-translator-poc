package com.temporal.gremlin.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility for discovering and resolving dataset files across environments,
 * custom user paths, relative directories, and environment variables.
 */
public class DatasetResolver {

    public static final String DEFAULT_FLIGHT_CSV = "flightlist_20190101_20190131.csv";
    public static final String PROPERTY_DATASET_PATH = "temporal.dataset.path";
    public static final String ENV_DATASET_PATH = "TEMPORAL_DATASET_PATH";

    private static String configuredOverridePath = null;

    /**
     * Globally configure an override dataset path for the current JVM run.
     */
    public static void setConfiguredOverridePath(String path) {
        configuredOverridePath = path;
    }

    /**
     * Resolves the flight dataset CSV file.
     *
     * @param customPath User-specified path (nullable).
     * @return Existing, readable File.
     * @throws IllegalStateException If dataset could not be found.
     */
    public static File resolveFlightCsv(String customPath) {
        List<String> candidates = new ArrayList<>();

        if (customPath != null && !customPath.trim().isEmpty()) {
            candidates.add(customPath.trim());
        }

        if (configuredOverridePath != null && !configuredOverridePath.trim().isEmpty()) {
            candidates.add(configuredOverridePath.trim());
        }

        String sysProp = System.getProperty(PROPERTY_DATASET_PATH);
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            candidates.add(sysProp.trim());
        }

        String envVar = System.getenv(ENV_DATASET_PATH);
        if (envVar != null && !envVar.trim().isEmpty()) {
            candidates.add(envVar.trim());
        }

        // Standard relative locations
        candidates.addAll(Arrays.asList(
                DEFAULT_FLIGHT_CSV,
                "datasets/" + DEFAULT_FLIGHT_CSV,
                "../" + DEFAULT_FLIGHT_CSV,
                "../datasets/" + DEFAULT_FLIGHT_CSV,
                "../../" + DEFAULT_FLIGHT_CSV
        ));

        for (String c : candidates) {
            File f = new File(c);
            if (f.exists() && f.isFile() && f.canRead()) {
                return f.getAbsoluteFile();
            }
        }

        // Search directory 'datasets/' for any CSV file
        File datasetsDir = new File("datasets");
        if (datasetsDir.exists() && datasetsDir.isDirectory()) {
            File[] files = datasetsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".csv"));
            if (files != null && files.length > 0) {
                return files[0].getAbsoluteFile();
            }
        }

        // Search current working directory for flightlist*.csv
        File currDir = new File(".");
        File[] localCsvs = currDir.listFiles((dir, name) -> name.toLowerCase().startsWith("flightlist") && name.endsWith(".csv"));
        if (localCsvs != null && localCsvs.length > 0) {
            return localCsvs[0].getAbsoluteFile();
        }

        throw new IllegalStateException(
                "OpenSky flight dataset could not be found.\n" +
                "Looked in candidates: " + candidates + "\n" +
                "To resolve, do one of the following:\n" +
                "  1. Place 'flightlist_20190101_20190131.csv' in the project root or 'datasets/' directory.\n" +
                "  2. Pass --dataset=/path/to/dataset.csv in the CLI.\n" +
                "  3. Set -Dtemporal.dataset.path=/path/to/dataset.csv or export TEMPORAL_DATASET_PATH=/path/to/dataset.csv"
        );
    }

    /**
     * Check if default dataset is available.
     */
    public static boolean isDefaultDatasetAvailable() {
        try {
            resolveFlightCsv(null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
