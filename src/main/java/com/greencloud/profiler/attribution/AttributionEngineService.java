package com.greencloud.profiler.attribution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greencloud.profiler.rapl.RaplHardwareReaderService.RaplEnergySample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AttributionEngineService {

    private static final Logger log = LoggerFactory.getLogger(AttributionEngineService.class);

    /**
     * Oldest metrics file, relative to the energy sample, that is still treated as
     * current.
     */
    private static final Duration MAX_METRICS_AGE = Duration.ofSeconds(2);

    /**
     * Allowed relative error of SUM(E_i) against E_dyn (metric M3 in the Review 2
     * plan).
     */
    private static final double CONSERVATION_TOLERANCE = 0.01;

    /**
     * After the first skipped window, repeat the same warning only every this many
     * windows.
     */
    private static final int SKIP_LOG_THROTTLE_INTERVAL = 60;

    private static final TypeReference<Map<String, Double>> METRICS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final double idlePowerWatts;
    private final Path metricsFilePath;
    private final Path outputFilePath;

    private int consecutiveSkippedTicks = 0;
    private SkipReason lastSkipReason = null;

    public AttributionEngineService(
            ObjectMapper objectMapper,
            @Value("${profiler.idle-power-watts:5.0}") double idlePowerWatts,
            @Value("${profiler.metrics-file:ebpf_metrics.json}") String metricsFile,
            @Value("${profiler.output-file:energy_attribution_output.json}") String outputFile) {
        this.objectMapper = objectMapper;
        this.idlePowerWatts = idlePowerWatts;
        this.metricsFilePath = Paths.get(metricsFile);
        this.outputFilePath = Paths.get(outputFile);
    }

    /**
     * Attributes the package energy of one window to the tasks that ran in it.
     *
     * @param sample the latest energy sample from Module 2; a {@code null} sample
     *               is ignored
     */
    public void attribute(RaplEnergySample sample) {
        if (sample == null) {
            return;
        }
        try {
            Map<Integer, Double> cpuTimeMsByPid = loadFreshCpuTimes(sample.sampledAt());
            AttributionReport report = buildReport(sample, cpuTimeMsByPid);
            writeReport(report);
            logSummary(report);

            if (consecutiveSkippedTicks > 0) {
                log.info("Attribution resumed after {} skipped tick(s).", consecutiveSkippedTicks);
                consecutiveSkippedTicks = 0;
                lastSkipReason = null;
            }
        } catch (NoSuchFileException exception) {
            skipTick(SkipReason.FILE_MISSING, "metrics file not found: " + exception.getFile());
        } catch (StaleMetricsException exception) {
            skipTick(SkipReason.STALE_METRICS, exception.getMessage());
        } catch (IOException exception) {
            skipTick(SkipReason.IO_ERROR,
                    "could not read or write attribution files (" + exception.getMessage() + ")");
        } catch (RuntimeException exception) {
            // Last line of defence: nothing in this service may break the Module 2 loop.
            log.error("Unexpected error during attribution; skipping this tick.", exception);
        }
    }

    /**
     * Reads {@code ebpf_metrics.json} if it is fresh enough for the given energy
     * sample.
     *
     * @return CPU time in milliseconds per task ID; entries with a non-numeric PID
     *         or an invalid
     *         time value are dropped
     * @throws NoSuchFileException   if Module 1 has not written the file yet
     * @throws StaleMetricsException if the file is too old to belong to this window
     * @throws IOException           if the file cannot be read or is not valid JSON
     */
    private Map<Integer, Double> loadFreshCpuTimes(Instant sampledAt)
            throws IOException, StaleMetricsException {
        Instant lastModified = Files.getLastModifiedTime(metricsFilePath).toInstant();
        Duration age = Duration.between(lastModified, sampledAt);
        if (age.compareTo(MAX_METRICS_AGE) > 0) {
            throw new StaleMetricsException(String.format(
                    "%s is stale (%.1f s older than the energy sample, limit %.1f s)",
                    metricsFilePath, age.toMillis() / 1000.0, MAX_METRICS_AGE.toMillis() / 1000.0));
        }

        Map<String, Double> rawCpuTimes = objectMapper.readValue(metricsFilePath.toFile(), METRICS_TYPE);
        Map<Integer, Double> cpuTimeMsByPid = new HashMap<>();
        for (Map.Entry<String, Double> entry : rawCpuTimes.entrySet()) {
            Double cpuTimeMs = entry.getValue();
            if (cpuTimeMs == null || !Double.isFinite(cpuTimeMs) || cpuTimeMs < 0.0) {
                log.debug("Ignoring invalid CPU time for PID key '{}': {}", entry.getKey(), cpuTimeMs);
                continue;
            }
            try {
                cpuTimeMsByPid.put(Integer.parseInt(entry.getKey()), cpuTimeMs);
            } catch (NumberFormatException exception) {
                log.debug("Ignoring non-numeric PID key '{}'", entry.getKey());
            }
        }
        return cpuTimeMsByPid;
    }

    /** Applies Eq. (1) and Eq. (2) to one window. */
    private AttributionReport buildReport(RaplEnergySample sample, Map<Integer, Double> cpuTimeMsByPid) {
        double packageEnergyJoules = sample.intervalJoules();
        double idleEnergyJoules = idlePowerWatts * sample.elapsedSeconds();
        double dynamicEnergyJoules = Math.max(0.0, packageEnergyJoules - idleEnergyJoules);
        double totalCpuTimeMs = cpuTimeMsByPid.values().stream().mapToDouble(Double::doubleValue).sum();

        List<ProcessEnergy> processes = new ArrayList<>();
        if (totalCpuTimeMs > 0.0) {
            double attributedEnergyJoules = 0.0;
            List<Map.Entry<Integer, Double>> entriesByCpuTime = new ArrayList<>(cpuTimeMsByPid.entrySet());
            entriesByCpuTime.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());

            for (Map.Entry<Integer, Double> entry : entriesByCpuTime) {
                double cpuTimeShare = entry.getValue() / totalCpuTimeMs;
                double energyJoules = dynamicEnergyJoules * cpuTimeShare;
                attributedEnergyJoules += energyJoules;
                processes.add(new ProcessEnergy(
                        entry.getKey(), entry.getValue(), round(cpuTimeShare * 100.0, 3), round(energyJoules, 6)));
            }
            verifyEnergyConservation(attributedEnergyJoules, dynamicEnergyJoules);
        }
        // When totalCpuTimeMs == 0 no task ran, so E_dyn stays unattributed and the
        // list is empty.

        return new AttributionReport(
                sample.sampledAt().toString(),
                round(sample.elapsedSeconds(), 3),
                round(packageEnergyJoules, 6),
                round(idleEnergyJoules, 6),
                round(dynamicEnergyJoules, 6),
                processes);
    }

    /**
     * Metric M3: the attributed energy must add up to E_dyn within the configured
     * tolerance.
     */
    private void verifyEnergyConservation(double attributedEnergyJoules, double dynamicEnergyJoules) {
        if (dynamicEnergyJoules <= 0.0) {
            return;
        }
        double relativeError = Math.abs(attributedEnergyJoules - dynamicEnergyJoules) / dynamicEnergyJoules;
        if (relativeError > CONSERVATION_TOLERANCE) {
            log.warn("Energy conservation check failed: sum of E_i differs from E_dyn by {}%.",
                    String.format("%.3f", relativeError * 100.0));
        }
    }

    /**
     * Writes the report to a temporary file and renames it over the output file, so
     * a reader never
     * sees a half-written JSON document.
     */
    private void writeReport(AttributionReport report) throws IOException {
        Path temporaryFilePath = outputFilePath.resolveSibling(outputFilePath.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFilePath.toFile(), report);
        try {
            Files.move(temporaryFilePath, outputFilePath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFilePath, outputFilePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void logSummary(AttributionReport report) {
        if (report.processes().isEmpty()) {
            log.info("No CPU time recorded in this window; {} J of dynamic energy left unattributed.",
                    String.format("%.4f", report.dynamicEnergyJoules()));
        } else {
            log.info("Attributed {} Joules across {} processes.",
                    String.format("%.4f", report.dynamicEnergyJoules()), report.processes().size());
        }
    }

    /**
     * Records a skipped window. A warning is logged whenever the reason changes and
     * otherwise once
     * in every {@value #SKIP_LOG_THROTTLE_INTERVAL} windows, so a stopped Module 1
     * does not flood
     * the log.
     */
    private void skipTick(SkipReason reason, String detail) {
        boolean reasonChanged = reason != lastSkipReason;
        lastSkipReason = reason;
        consecutiveSkippedTicks++;
        if (reasonChanged || consecutiveSkippedTicks % SKIP_LOG_THROTTLE_INTERVAL == 0) {
            log.warn("Skipping attribution for this tick: {} (consecutive skips: {})",
                    detail, consecutiveSkippedTicks);
        }
    }

    private static double round(double value, int decimalPlaces) {
        double scale = Math.pow(10, decimalPlaces);
        return Math.round(value * scale) / scale;
    }

    /** Output document written to {@code energy_attribution_output.json}. */
    public record AttributionReport(
            String windowEndUtc,
            double windowSeconds,
            double packageEnergyJoules,
            double idleEnergyJoules,
            double dynamicEnergyJoules,
            List<ProcessEnergy> processes) {
    }

    /** Energy attributed to one task in one window. */
    public record ProcessEnergy(int pid, double cpuTimeMs, double sharePercent, double energyJoules) {
    }

    private enum SkipReason {
        FILE_MISSING, STALE_METRICS, IO_ERROR
    }

    /**
     * Signals that the metrics file does not belong to the current energy window.
     */
    private static final class StaleMetricsException extends Exception {
        private static final long serialVersionUID = 1L;

        StaleMetricsException(String message) {
            super(message);
        }
    }
}
