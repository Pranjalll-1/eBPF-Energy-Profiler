# eBPF-Based Granular Energy Profiler for Green Computing

A lightweight, OS-level energy profiler that attributes CPU package energy to individual Linux processes (including ephemeral workloads living 1–50 ms) without relying on container orchestrators like Kubernetes.

This project uses in-kernel eBPF instrumentation to capture nanosecond-resolution CPU execution times and pairs it with an out-of-band Java Spring Boot daemon to perform proportional energy attribution based on Intel RAPL hardware counters.

## Project Architecture

The pipeline consists of three concurrent modules:

- **Module 1: Kernel-Space Execution Tracker (`tracker.py`)**
  - Uses eBPF (BCC) to hook `sched_switch` and `sched_process_exit` tracepoints.
  - Calculates absolute on-CPU time per PID.
  - Exports data atomically to `ebpf_metrics.json` every 1 second.
- **Module 2: RAPL Hardware Polling Daemon (Spring Boot)**
  - Reads the `/sys/class/powercap/intel-rapl:0/energy_uj` counter at a fixed 1 Hz cadence.
  - Handles hardware counter wrap-around.
  - **WSL2 Simulation Fallback:** If deployed inside a virtualized environment like WSL2 (where host MSRs are masked), it detects the missing interface and automatically falls back to generating realistic mock Joules to keep the pipeline alive for testing.
- **Module 3: Dynamic Attribution Engine (Spring Boot)**
  - Ingests the `ebpf_metrics.json` payload every second.
  - Executes the attribution algorithm: `E_i = E_dyn * (t_i / Σ t_j)`.
  - Exports the final correlated data to `energy_attribution_output.json`.

## Prerequisites

- Linux kernel 5.15+ (Bare-metal Ubuntu recommended for real RAPL metrics; WSL2 supported via Simulation Mode).
- `python3` and `python3-bpfcc` (BCC tools).
- Java 17 (LTS) and Maven 3.x.
- Root privileges (required for eBPF attachment and RAPL sysfs reading).

## How to Run

1. **Start the eBPF Tracker**
   Open a terminal and execute the Python tracker with root privileges:

   ```bash
   sudo python3 tracker.py
   ```

2. **Start the Attribution Engine**
   Open a second terminal, build the Java application, and execute it with root privileges:

   ```bash
   mvn clean package
   sudo java -jar target/profiler-0.0.1-SNAPSHOT.jar
   ```

3. **View the Output**
   The final energy distribution per process is exported every second to:
   ```bash
   cat energy_attribution_output.json
   ```

## Development & Limitations

This repository currently represents a functional Proof-of-Concept (PoC). The eBPF time-tracking logic is fully hardware-accurate, capturing short-lived tasks perfectly. However, for local development within WSL2, the Intel RAPL energy values are mathematically simulated due to Hyper-V hardware masking.
