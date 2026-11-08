package com.temporal.gremlin.benchmark;

import com.temporal.gremlin.util.DatasetResolver;

import java.io.File;

/**
 * Encapsulates the execution context, configuration options, dataset resolution,
 * and logger for benchmark runs.
 */
public class BenchmarkContext {

    private final String customDatasetPath;
    private File resolvedDatasetFile;
    private final ScaleMode scale;
    private final int[] customSizes;
    private final int warmupIterationsOverride;
    private final int measureIterationsOverride;
    private final BenchmarkOutput output;

    public BenchmarkContext(String customDatasetPath, ScaleMode scale, int[] customSizes,
                            int warmupIterationsOverride, int measureIterationsOverride,
                            BenchmarkOutput output) {
        this.customDatasetPath = customDatasetPath;
        this.scale = scale != null ? scale : ScaleMode.FULL;
        this.customSizes = customSizes;
        this.warmupIterationsOverride = warmupIterationsOverride;
        this.measureIterationsOverride = measureIterationsOverride;
        this.output = output != null ? output : new BenchmarkOutput(null, false, false);
    }

    /**
     * Resolves the dataset file on demand.
     */
    public File getDatasetFile() {
        if (resolvedDatasetFile == null) {
            resolvedDatasetFile = DatasetResolver.resolveFlightCsv(customDatasetPath);
        }
        return resolvedDatasetFile;
    }

    public String getCustomDatasetPath() {
        return customDatasetPath;
    }

    public ScaleMode getScale() {
        return scale;
    }

    public int[] getCustomSizes() {
        return customSizes;
    }

    public BenchmarkOutput getOutput() {
        return output;
    }

    /**
     * Resolves appropriate scale sizes for a benchmark given defaults for full and quick modes.
     */
    public int[] getSizes(String benchmarkId, int[] defaultFullSizes, int[] defaultQuickSizes) {
        if (scale == ScaleMode.CUSTOM && customSizes != null && customSizes.length > 0) {
            return customSizes;
        }
        if (scale == ScaleMode.QUICK) {
            return defaultQuickSizes != null ? defaultQuickSizes : defaultFullSizes;
        }
        return defaultFullSizes;
    }

    /**
     * Adjusts measurement iteration count based on scale mode and overrides.
     */
    public int getIterations(int defaultIters) {
        if (measureIterationsOverride > 0) {
            return measureIterationsOverride;
        }
        if (scale == ScaleMode.QUICK) {
            return Math.max(2, Math.min(defaultIters, 3));
        }
        return defaultIters;
    }

    /**
     * Adjusts warmup iteration count based on scale mode and overrides.
     */
    public int getWarmupIterations(int defaultWarmup) {
        if (warmupIterationsOverride >= 0) {
            return warmupIterationsOverride;
        }
        if (scale == ScaleMode.QUICK) {
            return Math.max(1, Math.min(defaultWarmup, 2));
        }
        return defaultWarmup;
    }

    public void println(String msg) {
        output.println(msg);
    }

    public void println() {
        output.println();
    }

    public void printf(String format, Object... args) {
        output.printf(format, args);
    }
}
