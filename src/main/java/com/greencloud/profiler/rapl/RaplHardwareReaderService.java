package com.greencloud.profiler.rapl;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RaplHardwareReaderService {

    private static final Logger log = LoggerFactory.getLogger(RaplHardwareReaderService.class);

    private static final Path SYSFS_ENERGY_UJ = Paths.get("/sys/class/powercap/intel-rapl:0/energy_uj");
    private static final Path SYSFS_MAX_RANGE_UJ = Paths.get("/sys/class/powercap/intel-rapl:0/max_energy_range_uj");

    private static final long FALLBACK_HARDWARE_LIMIT_UJ = 262_143_328_850L;

    private boolean isSimulationMode = false;
    private long hardwareWrapLimitUj = FALLBACK_HARDWARE_LIMIT_UJ;
    private boolean isFirstTick = true;
    private long lastTickEnergyUj;
    private long lastTickTimestampNs;
    private int consecutiveErrors = 0;

    private volatile RaplEnergySample latestSample;

    public record RaplEnergySample(
            double intervalJoules,
            double elapsedSeconds,
            double intervalWatts,
            boolean didHardwareWrap,
            Instant sampledAt) {
    }

    @PostConstruct
    void initHardwareLimits() {
        if (!Files.exists(SYSFS_ENERGY_UJ)) {
            log.warn("RAPL interface not found at {}. (Expected behavior inside WSL2 VM).", SYSFS_ENERGY_UJ);
            log.warn("=== ENTERING SIMULATION MODE: Generating mock Joules for Module 3 testing ===");
            isSimulationMode = true;
            return;
        }

        try {
            long detectedLimit = readSysfsUj(SYSFS_MAX_RANGE_UJ);
            if (detectedLimit > 0) {
                hardwareWrapLimitUj = detectedLimit;
                log.info("RAPL wrap limit detected: {} uJ", hardwareWrapLimitUj);
            }
        } catch (Exception e) {
            log.warn("Could not read max energy range. Defaulting to {} uJ", FALLBACK_HARDWARE_LIMIT_UJ);
        }

        if (!Files.isReadable(SYSFS_ENERGY_UJ)) {
            log.error("Cannot read {}. Are we running as root?", SYSFS_ENERGY_UJ);
        }
    }

    @Scheduled(fixedRate = 1000)
    public void pollIntelRaplCounters() {
        long currentTimestampNs = System.nanoTime();

        if (isSimulationMode) {
            generateSimulationData(currentTimestampNs);
            return;
        }

        try {
            long currentEnergyUj = readSysfsUj(SYSFS_ENERGY_UJ);

            if (consecutiveErrors > 0) {
                consecutiveErrors = 0;
            }

            if (isFirstTick) {
                lastTickEnergyUj = currentEnergyUj;
                lastTickTimestampNs = currentTimestampNs;
                isFirstTick = false;
                return;
            }

            boolean didHardwareWrap = currentEnergyUj < lastTickEnergyUj;
            long deltaUj = didHardwareWrap
                    ? (hardwareWrapLimitUj - lastTickEnergyUj) + currentEnergyUj
                    : currentEnergyUj - lastTickEnergyUj;

            double deltaJoules = deltaUj / 1_000_000.0;
            double elapsedSeconds = (currentTimestampNs - lastTickTimestampNs) / 1_000_000_000.0;
            double intervalWatts = elapsedSeconds > 0.0 ? deltaJoules / elapsedSeconds : 0.0;

            lastTickEnergyUj = currentEnergyUj;
            lastTickTimestampNs = currentTimestampNs;

            latestSample = new RaplEnergySample(
                    deltaJoules, elapsedSeconds, intervalWatts, didHardwareWrap, Instant.now());

            log.info("CPU Pkg0: {} J ({} W over {} s)",
                    String.format("%.4f", deltaJoules),
                    String.format("%.2f", intervalWatts),
                    String.format("%.3f", elapsedSeconds));

        } catch (AccessDeniedException e) {
            handleError("Root privileges required to read RAPL sysfs: " + SYSFS_ENERGY_UJ);
        } catch (NoSuchFileException e) {
            handleError("intel_rapl driver not loaded or hardware not supported.");
        } catch (Exception e) {
            handleError("Unexpected error reading RAPL: " + e.getMessage());
        }
    }

    public RaplEnergySample getLatestSample() {
        return latestSample;
    }

    private void generateSimulationData(long currentTimestampNs) {
        if (isFirstTick) {
            lastTickTimestampNs = currentTimestampNs;
            isFirstTick = false;
            return;
        }

        double elapsedSeconds = (currentTimestampNs - lastTickTimestampNs) / 1_000_000_000.0;

        // Generate a realistic CPU package power draw between 15W and 25W
        double mockWatts = 15.0 + (ThreadLocalRandom.current().nextDouble() * 10.0);
        double mockJoules = mockWatts * elapsedSeconds;

        lastTickTimestampNs = currentTimestampNs;

        latestSample = new RaplEnergySample(mockJoules, elapsedSeconds, mockWatts, false, Instant.now());

        log.info("[SIMULATION] CPU Pkg0: {} J ({} W over {} s)",
                String.format("%.4f", mockJoules),
                String.format("%.2f", mockWatts),
                String.format("%.3f", elapsedSeconds));
    }

    private long readSysfsUj(Path sysfsPath) throws IOException {
        return Long.parseLong(Files.readString(sysfsPath).trim());
    }

    private void handleError(String reason) {
        consecutiveErrors++;
        if (consecutiveErrors == 1 || consecutiveErrors % 60 == 0) {
            log.error("RAPL Read Error: {}", reason);
        }
    }
}