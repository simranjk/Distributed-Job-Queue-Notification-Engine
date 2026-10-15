package com.jobengine.service;

import com.jobengine.model.Job;
import com.jobengine.model.JobStatus;
import com.jobengine.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class JobConsumerService {

    public static final String DLQ_QUEUE_NAME = "job:dlq";
    private static final Duration POP_TIMEOUT = Duration.ofSeconds(2);
    private static final int LOCK_WAIT_SECONDS = 3;
    private static final int LOCK_LEASE_SECONDS = 30;

    private final RedisTemplate<String, String> redisTemplate;
    private final JobRepository jobRepository;
    private final RedissonClient redissonClient;
    private final JobExecutor jobExecutor;
    private final MetricsService metricsService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    public JobConsumerService(RedisTemplate<String, String> redisTemplate,
                              JobRepository jobRepository,
                              RedissonClient redissonClient,
                              JobExecutor jobExecutor,
                              MetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.jobRepository = jobRepository;
        this.redissonClient = redissonClient;
        this.jobExecutor = jobExecutor;
        this.metricsService = metricsService;
    }

    @PostConstruct
    public void start() {
        running.set(true);
        consumerThread = Thread.ofVirtual()
                .name("job-consumer-worker-", 1)
                .start(this::pollQueueLoop);
        log.info("Initialized JobConsumerService background worker on Java 21 Virtual Thread");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        log.info("Shutdown signal sent to JobConsumerService worker thread");
    }

    private void pollQueueLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Safe blocking right pop from Redis list
                String jobId = redisTemplate.opsForList().rightPop(JobProducerService.QUEUE_NAME, POP_TIMEOUT);

                if (jobId == null || jobId.trim().isEmpty()) {
                    continue;
                }

                log.debug("Popped job ID from queue: {}", jobId);
                processJobWithLock(jobId.trim());

            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("JobConsumerService worker loop interrupted, exiting...");
                    break;
                }
                log.error("Unexpected error in JobConsumerService polling loop: {}", e.getMessage(), e);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processJobWithLock(String jobIdStr) {
        String lockKey = "lock:job:" + jobIdStr;
        RLock lock = redissonClient.getLock(lockKey);

        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);

            if (!lockAcquired) {
                log.warn("Could not acquire lock for job ID {}. Re-queueing job.", jobIdStr);
                redisTemplate.opsForList().leftPush(JobProducerService.QUEUE_NAME, jobIdStr);
                return;
            }

            log.info("Successfully acquired Redisson lock for job ID {}", jobIdStr);
            UUID jobId = UUID.fromString(jobIdStr);
            executeJobLifecycle(jobId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread interrupted while waiting for lock on job {}", jobIdStr);
        } catch (Exception e) {
            log.error("Error occurred while processing job {}: {}", jobIdStr, e.getMessage(), e);
        } finally {
            if (lockAcquired) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        log.info("Released Redisson lock for job ID {}", jobIdStr);
                    }
                } catch (Exception e) {
                    log.error("Failed to release lock for job ID {}: {}", jobIdStr, e.getMessage());
                }
            }
        }
    }

    private void executeJobLifecycle(UUID jobId) {
        Optional<Job> jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Job {} not found in database, discarding.", jobId);
            return;
        }

        Job job = jobOpt.get();

        // If job already finalized, skip execution
        if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.DLQ) {
            log.info("Job {} is already in terminal state {}, skipping.", jobId, job.getStatus());
            return;
        }

        // Transition to PROCESSING
        job.setStatus(JobStatus.PROCESSING);
        jobRepository.save(job);
        log.info("Job {} transitioned to PROCESSING", jobId);

        long startTime = System.currentTimeMillis();
        try {
            jobExecutor.execute(job);
            long durationMs = System.currentTimeMillis() - startTime;

            // Successful completion
            job.setStatus(JobStatus.COMPLETED);
            job.setErrorMessage(null);
            jobRepository.save(job);
            log.info("Job {} successfully COMPLETED in {} ms", jobId, durationMs);

            metricsService.recordJobProcessed(job.getTaskType());
            metricsService.recordProcessingDuration(job.getTaskType(), durationMs, true);

        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - startTime;
            metricsService.recordProcessingDuration(job.getTaskType(), durationMs, false);
            handleJobFailure(job, ex);
        }
    }

    private void handleJobFailure(Job job, Exception ex) {
        int newRetryCount = job.getRetryCount() + 1;
        job.setRetryCount(newRetryCount);
        job.setErrorMessage(ex.getMessage());

        long backoffDelayMs = (long) Math.pow(2, newRetryCount) * 1000L;
        String jobIdStr = job.getId().toString();

        if (newRetryCount < job.getMaxRetries()) {
            job.setStatus(JobStatus.FAILED);
            jobRepository.save(job);
            metricsService.recordJobFailed(job.getTaskType(), false);
            log.warn("Job {} failed (attempt {}/{}): '{}'. Scheduling retry in {} ms (exponential backoff)",
                    jobIdStr, newRetryCount, job.getMaxRetries(), ex.getMessage(), backoffDelayMs);

            // Re-enqueue after exponential backoff using a virtual thread
            Thread.ofVirtual()
                    .name("job-retry-delay-" + jobIdStr)
                    .start(() -> {
                        try {
                            Thread.sleep(backoffDelayMs);
                            redisTemplate.opsForList().leftPush(JobProducerService.QUEUE_NAME, jobIdStr);
                            log.info("Re-enqueued job {} after {} ms backoff", jobIdStr, backoffDelayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Retry delay interrupted for job {}", jobIdStr);
                        }
                    });

        } else {
            job.setStatus(JobStatus.DLQ);
            jobRepository.save(job);
            redisTemplate.opsForList().leftPush(DLQ_QUEUE_NAME, jobIdStr);
            metricsService.recordJobFailed(job.getTaskType(), true);
            log.error("ALERT: Job {} exceeded max retries ({}/{}). Transitioned to DLQ ('{}'). Error: {}",
                    jobIdStr, newRetryCount, job.getMaxRetries(), DLQ_QUEUE_NAME, ex.getMessage());
        }
    }
}
