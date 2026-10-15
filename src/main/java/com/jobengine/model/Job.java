package com.jobengine.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "task_type", nullable = false, length = 100)
    private String taskType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", nullable = false, columnDefinition = "job_status")
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Job() {
    }

    public Job(UUID id, String taskType, String payload, JobStatus status, int retryCount, int maxRetries, String errorMessage, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.taskType = taskType;
        this.payload = payload;
        this.status = status != null ? status : JobStatus.PENDING;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public static JobBuilder builder() {
        return new JobBuilder();
    }

    public static class JobBuilder {
        private UUID id;
        private String taskType;
        private String payload;
        private JobStatus status = JobStatus.PENDING;
        private int retryCount = 0;
        private int maxRetries = 3;
        private String errorMessage;
        private Instant createdAt;
        private Instant updatedAt;

        public JobBuilder id(UUID id) { this.id = id; return this; }
        public JobBuilder taskType(String taskType) { this.taskType = taskType; return this; }
        public JobBuilder payload(String payload) { this.payload = payload; return this; }
        public JobBuilder status(JobStatus status) { this.status = status; return this; }
        public JobBuilder retryCount(int retryCount) { this.retryCount = retryCount; return this; }
        public JobBuilder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public JobBuilder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public JobBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public JobBuilder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public Job build() {
            return new Job(id, taskType, payload, status, retryCount, maxRetries, errorMessage, createdAt, updatedAt);
        }
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = JobStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
