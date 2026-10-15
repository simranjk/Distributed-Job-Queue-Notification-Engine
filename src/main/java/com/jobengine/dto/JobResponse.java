package com.jobengine.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.jobengine.model.Job;
import com.jobengine.model.JobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobResponse {

    private UUID id;
    private String taskType;

    @JsonRawValue
    private String payload;

    private JobStatus status;
    private int retryCount;
    private int maxRetries;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static JobResponse fromEntity(Job job) {
        return JobResponse.builder()
                .id(job.getId())
                .taskType(job.getTaskType())
                .payload(job.getPayload())
                .status(job.getStatus())
                .retryCount(job.getRetryCount())
                .maxRetries(job.getMaxRetries())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
