package com.temporal.gremlin.benchmark;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runner for interactive and informational demo programs.
 */
public class DemoRunner {

    public static final Map<String, String> DEMO_MAP = new LinkedHashMap<>();
    public static final Map<String, String> DEMO_DESC = new LinkedHashMap<>();

    static {
        DEMO_MAP.put("translator", "com.temporal.gremlin.demo.TranslatorDemo");
        DEMO_DESC.put("translator", "Interactive 4-phase translation pipeline demo");

        DEMO_MAP.put("correctness", "com.temporal.gremlin.demo.CorrectnessDemo");
        DEMO_DESC.put("correctness", "Graph A (Native) vs Graph B (Translator) query comparison");

        DEMO_MAP.put("apps", "com.temporal.gremlin.demo.TemporalApplicationsDemo");
        DEMO_DESC.put("apps", "Temporal graph algorithms & application scenarios");

        DEMO_MAP.put("continuous-path", "com.temporal.gremlin.demo.TestContinuousPath");
        DEMO_DESC.put("continuous-path", "Continuous temporal path traversal verification");

        DEMO_MAP.put("string", "com.temporal.gremlin.demo.StringTransformerDemo");
        DEMO_DESC.put("string", "String-based temporal query transformer demo");
    }

    public static void runDemo(String demoKey, BenchmarkOutput output) {
        String key = demoKey != null ? demoKey.trim().toLowerCase() : "translator";
        String className = DEMO_MAP.get(key);

        if (className == null) {
            output.println("Unknown demo: '" + demoKey + "'. Available demos: " + DEMO_MAP.keySet());
            return;
        }

        output.header("RUNNING DEMO: " + key.toUpperCase() + " (" + DEMO_DESC.get(key) + ")");
        try {
            Class<?> clazz = Class.forName(className);
            Method main = clazz.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } catch (Exception e) {
            output.println("Error running demo '" + demoKey + "': " + e.getMessage());
            e.printStackTrace();
        }
    }
}
