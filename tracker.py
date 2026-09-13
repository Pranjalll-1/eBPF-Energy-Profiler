# Module 1: Per-PID CPU Execution Time Tracker
#Running Guide - 
#S1. Open a wsl terminal
#S2. Execute sudo python3 tracker.py
#S3. In a new wsl terminal execute "cat ebpf_metrics.json"
#
# This module attaches to two scheduler tracepoints (`sched_switch` and
# `sched_process_exit`) to measure, with nanosecond precision, how much
# wall-clock CPU time each process (PID) actually spends running on-core.
#
# The resulting per-PID execution time is the foundational signal for the
# broader energy profiler: downstream modules combine this with RAPL/power
# telemetry to estimate the energy footprint attributable to each workload,
# which is the core value proposition for "green" cloud cost accounting.
#
# All aggregation happens in-kernel via BPF hash maps to avoid the overhead
# of forwarding a trace event to user space on every context switch — at
# high scheduling frequencies (thousands of switches/sec), that overhead
# would itself distort the very energy measurements we're trying to take.

from bcc import BPF
import time
import json

ebpfKernelProgramSource = r"""
#include <uapi/linux/ptrace.h>

BPF_HASH(start_time, u32, u64);
BPF_HASH(exec_time, u32, u64);

TRACEPOINT_PROBE(sched, sched_switch) {
    // NOTE: args->prev_pid / args->next_pid are native kernel tracepoint
    // struct fields supplied by the sched_switch ABI and must not be
    // renamed or reinterpreted.
    u32 outgoingProcessId = args->prev_pid;
    u32 incomingProcessId = args->next_pid;
    u64 currentTimestampNs = bpf_ktime_get_ns();


    u64 *processStartTimestamp = start_time.lookup(&outgoingProcessId);
    if (processStartTimestamp != 0) {
        u64 cpuTimeDeltaNs = currentTimestampNs - *processStartTimestamp;
        u64 *aggregatedExecutionTimeNs = exec_time.lookup(&outgoingProcessId);
        if (aggregatedExecutionTimeNs != 0) {
            *aggregatedExecutionTimeNs += cpuTimeDeltaNs;
        } else {
            exec_time.update(&outgoingProcessId, &cpuTimeDeltaNs);
        }
        start_time.delete(&outgoingProcessId);
    }
    if (incomingProcessId != 0) {
        start_time.update(&incomingProcessId, &currentTimestampNs);
    }
    return 0;
}

TRACEPOINT_PROBE(sched, sched_process_exit) {
    // args->pid is a native kernel tracepoint struct field and must not
    // be renamed or reinterpreted.
    u32 exitingProcessId = args->pid;
    start_time.delete(&exitingProcessId);
    exec_time.delete(&exitingProcessId);
    return 0;
}
"""

ebpfRuntimeEnvironment = BPF(text=ebpfKernelProgramSource)
print("Tracking CPU time... Exporting to ebpf_metrics.json. Press Ctrl-C to stop.\n")

try:
    while True:
        time.sleep(1)
        cpuExecutionTimeMap = ebpfRuntimeEnvironment["exec_time"]

        if len(cpuExecutionTimeMap) == 0:
            continue

        metricsExportPayload = {}

        print(f"{'PID':<8} {'COMMAND':<20} {'CPU TIME (ms)':>15}")
        print("-" * 45)
        sortedProcessEntries = sorted(
            cpuExecutionTimeMap.items(), key=lambda kv: kv[1].value, reverse=True
        )

        for processId, totalExecutionTimeNs in sortedProcessEntries:
            processIdValue = processId.value
            executionTimeMs = totalExecutionTimeNs.value / 1_000_000.0

            metricsExportPayload[str(processIdValue)] = round(executionTimeMs, 3)
            try:
                with open(f"/proc/{processIdValue}/comm", "r") as processCommFile:
                    processCommandName = processCommFile.read().strip()
            except FileNotFoundError:
                processCommandName = "<exited>"

            print(f"{processIdValue:<8} {processCommandName:<20} {executionTimeMs:>15.3f}")

        with open("ebpf_metrics.json", "w") as jsonOutputFile:
            json.dump(metricsExportPayload, jsonOutputFile, indent=4)

        print(f"\n[+] Exported {len(metricsExportPayload)} active PIDs to ebpf_metrics.json")
        print("=" * 45 + "\n")
        cpuExecutionTimeMap.clear()

except KeyboardInterrupt:
    print("\nStopping tracker.")


# ---------------------------------------------------------------------------
# INTEGRATION GUIDE FOR MODULE 2 
# ---------------------------------------------------------------------------
# 
# 1. DATA INGESTION: This script constantly overwrites "ebpf_metrics.json" 
#    every 1 second. In your Java application, set up a ScheduledExecutorService 
#    or a background thread to read this file at a similar 1-second interval.
#    Use a library like Jackson or Gson to parse the JSON into a Map<String, Double>.
# 
# 2. RAPL SYNC: At the exact same interval you read the JSON file, read the 
#    hardware energy counter directly from the Linux filesystem:
#    File path: /sys/class/powercap/intel-rapl:0/energy_uj
# 
# 3. ATTRIBUTION MATH (MODULE 3 PREP): 
#    - Sum all the execution times (values) from the JSON to get 'Total_Active_Time'.
#    - Calculate the difference in the RAPL energy counter since the last second to get 'Total_Joules'.
#    - For each active PID: (PID_Time / Total_Active_Time) * Total_Joules = PID_Energy_Footprint.
# 
# 4. CONCURRENCY WARNING: Since this Python script writes to the file while 
#    your Java daemon reads it, you might occasionally hit a file lock or read 
#    a partially written file. Wrap your Java FileReader in a try-catch block 
#    and silently skip/retry on the next tick if an IOException occurs.
# ---------------------------------------------------------------------------
