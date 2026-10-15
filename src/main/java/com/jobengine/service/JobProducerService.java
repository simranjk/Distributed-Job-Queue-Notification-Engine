package com.jobengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobengine.dto.CreateJobRequest;
import com.jobengine.dto.JobResponse;
import com.jobengine.model.Job;
import com.jobengine.model.JobStatus;
import com.jobengine.repository.JobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class JobProducerService {

    public static final String QUEUE_NAME = "job:queue";

    private final JobRepository jobRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    public JobProducerService(JobRepository jobRepository,
                              RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper,
                              MetricsService metricsService) {
        this.jobRepository = jobRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    @Transactional
    public JobResponse createAndEnqueueJob(CreateJobRequest request) {
        if (request.getTaskType() == null || request.getTaskType().trim().isEmpty()) {
            throw new IllegalArgumentException("taskType cannot be empty");
        }

        String payloadJson = "{}";
        if (request.getPayload() != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(request.getPayload());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize job payload", e);
                throw new IllegalArgumentException("Invalid JSON payload", e);
            }
        }

        int maxRetries = (request.getMaxRetries() != null && request.getMaxRetries() > 0)
                ? request.getMaxRetries()
                : 3;

        Job job = Job.builder()
                .taskType(request.getTaskType().trim())
                .payload(payloadJson)
                .status(JobStatus.PENDING)
                .retryCount(0)
                .maxRetries(maxRetries)
                .build();

        Job savedJob = jobRepository.save(job);
        log.info("Saved new job to PostgreSQL: id={}, taskType={}", savedJob.getId(), savedJob.getTaskType());

        String jobIdStr = savedJob.getId().toString();
        Long queueSize = redisTemplate.opsForList().leftPush(QUEUE_NAME, jobIdStr);
        log.info("Enqueued job ID {} into Redis queue '{}'. Current queue size: {}", jobIdStr, QUEUE_NAME, queueSize);

        metricsService.recordJobEnqueued(savedJob.getTaskType());

        return JobResponse.fromEntity(savedJob);
    }

    @Transactional(readOnly = true)
    public Optional<JobResponse> getJobById(UUID jobId) {
        return jobRepository.findById(jobId).map(JobResponse::fromEntity);
    }
}
