package com.jobengine.controller;

import com.jobengine.dto.CreateJobRequest;
import com.jobengine.dto.JobResponse;
import com.jobengine.service.JobProducerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Job Management", description = "Endpoints for creating and retrieving jobs in the distributed job queue")
public class JobController {

    private final JobProducerService jobProducerService;

    public JobController(JobProducerService jobProducerService) {
        this.jobProducerService = jobProducerService;
    }

    @PostMapping
    @Operation(
        summary = "Create a new job",
        description = "Creates a new job with the given task type, persists it to PostgreSQL, and enqueues it into Redis for processing."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Job created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request body")
    })
    public ResponseEntity<JobResponse> createJob(@RequestBody CreateJobRequest request) {
        log.info("Received request to create job with taskType: {}", request.getTaskType());
        JobResponse createdJob = jobProducerService.createAndEnqueueJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdJob);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Get a job by ID",
        description = "Retrieves the status and details of a job by its unique identifier."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Job found"),
        @ApiResponse(responseCode = "404", description = "Job not found")
    })
    public ResponseEntity<JobResponse> getJobById(@Parameter(description = "Unique job ID", required = true) @PathVariable("id") UUID id) {
        return jobProducerService.getJobById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
