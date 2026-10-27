# Module 1: Per-PID CPU Execution Time Tracker
# Running Guide - 
# S1. Open a wsl terminal
# S2. Execute sudo python3 tracker.py
# S3. In a new wsl terminal execute "cat ebpf_metrics.json"

from bcc import BPF
import time
import json
import os

ebpfKernelProgramSource = r"""
#include <uapi/linux/ptrace.h>

// Sized explicitly to 102,400 to prevent map overflow during fork-storms
BPF_HASH(start_time, u32, u64, 102400);
BPF_HASH(exec_time, u32, u64, 102400);

TRACEPOINT_PROBE(sched, sched_switch) {
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
    
    // Do not time the idle task (PID 0)
    if (incomingProcessId != 0) {
        start_time.update(&incomingProcessId, &currentTimestampNs);
    }
    return 0;
}

TRACEPOINT_PROBE(sched, sched_process_exit) {
    u32 exitingProcessId = args->pid;
    u64 currentTimestampNs = bpf_ktime_get_ns();
    
    // FIX FOR CHALLENGE 4: Finalize the running slice before the process dies
    u64 *processStartTimestamp = start_time.lookup(&exitingProcessId);
    if (processStartTimestamp != 0) {
        u64 cpuTimeDeltaNs = currentTimestampNs - *processStartTimestamp;
        u64 *aggregatedExecutionTimeNs = exec_time.lookup(&exitingProcessId);
        if (aggregatedExecutionTimeNs != 0) {
            *aggregatedExecutionTimeNs += cpuTimeDeltaNs;
        } else {
            exec_time.update(&exitingProcessId, &cpuTimeDeltaNs);
        }
    }
    
    // Delete start_time, but intentionally leave exec_time intact so the 
    // Python exporter can read the final runtime slice in the next 1-second tick.
    start_time.delete(&exitingProcessId);
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

        # FIX FOR CHALLENGE 3: Atomic file writes to prevent Java from reading a half-empty file
        temp_filename = "ebpf_metrics_temp.json"
        with open(temp_filename, "w") as jsonOutputFile:
            json.dump(metricsExportPayload, jsonOutputFile, indent=4)
        os.replace(temp_filename, "ebpf_metrics.json")

        print(f"\n[+] Exported {len(metricsExportPayload)} active PIDs to ebpf_metrics.json")
        print("=" * 45 + "\n")
        
        # Clear the map for the next 1-second window
        cpuExecutionTimeMap.clear()

except KeyboardInterrupt:
    print("\nStopping tracker.")