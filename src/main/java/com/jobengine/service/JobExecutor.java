package com.jobengine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobengine.model.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JobExecutor {

    private final ObjectMapper objectMapper;
    private final MetricsService metricsService;

    public JobExecutor(ObjectMapper objectMapper, MetricsService metricsService) {
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public void execute(Job job) throws Exception {
        log.info("Executing job id: {}, taskType: {}", job.getId(), job.getTaskType());

        // Check if job payload requests simulated failure for retry/DLQ testing
        if ("FAILING_TASK".equalsIgnoreCase(job.getTaskType())) {
            throw new RuntimeException("Simulated task failure for taskType FAILING_TASK");
        }

        if (job.getPayload() != null && !job.getPayload().isBlank()) {
            try {
                JsonNode jsonNode = objectMapper.readTree(job.getPayload());
                if (jsonNode.has("simulateFailure") && jsonNode.get("simulateFailure").asBoolean()) {
                    String reason = jsonNode.has("failureReason")
                            ? jsonNode.get("failureReason").asText()
                            : "Simulated job failure via payload trigger";
                    throw new RuntimeException(reason);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Could not parse payload as JSON during execution simulation: {}", e.getMessage());
            }
        }

        // Simulate workload processing time
        Thread.sleep(150);
        log.info("Successfully executed job id: {}, taskType: {}", job.getId(), job.getTaskType());
    }
}
