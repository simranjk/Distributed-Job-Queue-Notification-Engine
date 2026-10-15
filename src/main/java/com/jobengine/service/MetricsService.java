package com.jobengine.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increment the jobs.enqueued.total counter for submitted jobs.
     */
    public void recordJobEnqueued(String taskType) {
        String sanitizedTask = (taskType != null && !taskType.isBlank()) ? taskType : "unknown";
        Counter.builder("jobs.enqueued.total")
                .description("Total number of submitted and enqueued jobs")
                .tag("task_type", sanitizedTask)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increment the jobs.processed.total counter for successfully completed jobs.
     */
    public void recordJobProcessed(String taskType) {
        String sanitizedTask = (taskType != null && !taskType.isBlank()) ? taskType : "unknown";
        Counter.builder("jobs.processed.total")
                .description("Total number of successfully completed jobs")
                .tag("task_type", sanitizedTask)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increment the jobs.failed.total counter for failed jobs and DLQ transitions.
     */
    public void recordJobFailed(String taskType, boolean dlq) {
        String sanitizedTask = (taskType != null && !taskType.isBlank()) ? taskType : "unknown";
        Counter.builder("jobs.failed.total")
                .description("Total number of job failures and DLQ transitions")
                .tag("task_type", sanitizedTask)
                .tag("outcome", dlq ? "dlq" : "retry")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record the job.processing.duration timer measuring execution latency.
     */
    public void recordProcessingDuration(String taskType, long durationMs, boolean success) {
        String sanitizedTask = (taskType != null && !taskType.isBlank()) ? taskType : "unknown";
        Timer.builder("job.processing.duration")
                .description("Execution latency of job processing")
                .tag("task_type", sanitizedTask)
                .tag("status", success ? "success" : "failure")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
