# eBPF-Based Granular Energy Profiler for Green Cloud Computing

![Java](https://img.shields.io/badge/Java-17-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen.svg)
![Python](https://img.shields.io/badge/Python-3.x-blue.svg)
![eBPF](https://img.shields.io/badge/eBPF-BCC-yellow.svg)
![Platform](https://img.shields.io/badge/Platform-Linux%20Bare--Metal-lightgrey.svg)

## Overview
Measuring the carbon footprint of containerized microservices sharing a single host is notoriously difficult. Hardware power metrics, like Intel RAPL, only provide energy data at the CPU socket level. Traditional observability tools rely on user-space polling (e.g., `top`), which completely misses high-frequency microservice context switches and short-lived ephemeral tasks.

This project is a zero-dependency, bare-metal systems engineering tool that bridges hardware power domains and software execution. It leverages **eBPF (Extended Berkeley Packet Filter)** to intercept OS scheduler events with nanosecond precision, proportionally attributing dynamic hardware Joules to individual Process IDs (PIDs).

### The "Golden Hook" (Novelty)
Industry-standard tools (like CNCF Kepler) have historically struggled to capture the energy footprint of short-lived processes, eventually pivoting away from eBPF entirely due to dropped metrics. This project resolves that gap by explicitly hooking the Linux kernel's `sched_process_exit` tracepoint. This acts as an immediate in-kernel garbage collector, capturing the exact execution time of ephemeral micro-threads right before they terminate, ensuring zero data loss and preventing kernel memory leaks.

## Architecture
The system is decoupled into three distinct modules:

*   **Module 1: eBPF Kernel Probe (`tracker.py`)**
    A Python (BCC) daemon that compiles a C-based eBPF program, hooking into `sched_switch` and `sched_process_exit`. It aggregates PID execution time natively in kernel Hash Maps and exports the data to a local `ebpf_metrics.json` pipeline every second.
*   **Module 2: RAPL Hardware Reader (`RaplHardwareReaderService.java`)**
    A Java Spring Boot background service that polls the `/sys/class/powercap/intel-rapl:0/energy_uj` interface. It calculates total dynamic Joules and Watts consumed by the CPU package over the same 1-second interval, gracefully handling hardware register wrap-arounds.
*   **Module 3: Attribution Engine (In Development)**
    An ingestion engine that merges the eBPF nanosecond timings with the hardware RAPL Joules, proportionally distributing the energy footprint across all active processes.

## Prerequisites
*   **OS:** Bare-metal Linux (Kernel >= 5.8) or WSL2 (runs in Simulation Mode).
*   **Hardware:** Intel or AMD CPU with RAPL/powercap support.
*   **Dependencies:** `bcc-tools`, Python 3, Java 17, and Maven.
*   **Privileges:** `root` access is strictly required to inject eBPF bytecode and read RAPL `sysfs` files.

## Running the Profiler

### 1. Start the eBPF Tracer (Module 1)
Open a terminal and run the tracer with root privileges:
```bash
sudo python3 tracker.py
```
*This will begin logging active PIDs to the console and writing to `ebpf_metrics.json`.*

### 2. Start the Hardware Reader (Module 2)
In a separate terminal, compile and run the Spring Boot application:
```bash
mvn clean package
sudo java -jar target/profiler-0.0.1-SNAPSHOT.jar
```
*This will begin polling the hardware and outputting package-level Joules and Watts to the console.*

### Note on WSL2 / Virtual Machines
Hypervisors (like Windows Hyper-V used by WSL2) do not pass physical CPU powercap interfaces to the guest OS. If Module 2 detects it is running inside a VM without RAPL access, it will gracefully fallback to **Simulation Mode**, generating mathematically realistic mock Joules to allow downstream pipeline testing.