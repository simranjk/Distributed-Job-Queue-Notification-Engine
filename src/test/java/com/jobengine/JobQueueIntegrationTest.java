package com.jobengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobengine.dto.CreateJobRequest;
import com.jobengine.model.Job;
import com.jobengine.model.JobStatus;
import com.jobengine.repository.JobRepository;
import com.jobengine.service.JobConsumerService;
import com.jobengine.service.JobProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class JobQueueIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("jobengine")
            .withUsername("postgres")
            .withPassword("password");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Clear redis queues between tests
        redisTemplate.delete(JobProducerService.QUEUE_NAME);
        redisTemplate.delete(JobConsumerService.DLQ_QUEUE_NAME);
    }

    @Test
    @DisplayName("POST /api/jobs submits job, persists in PostgreSQL, and enqueues to Redis")
    void testJobSubmissionAndProducerEnqueue() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"recipient\":\"alice@example.com\",\"content\":\"Welcome!\"}");
        CreateJobRequest request = CreateJobRequest.builder()
                .taskType("WELCOME_EMAIL")
                .payload(payload)
                .maxRetries(3)
                .build();

        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.taskType").value("WELCOME_EMAIL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID jobId = UUID.fromString(responseJson.get("id").asText());

        // Verify database persistence
        assertThat(jobRepository.findById(jobId)).isPresent();
        Job savedJob = jobRepository.findById(jobId).get();
        assertThat(savedJob.getTaskType()).isEqualTo("WELCOME_EMAIL");
    }

    @Test
    @DisplayName("End-to-End: Background worker consumer consumes job and transitions to COMPLETED")
    void testEndToEndJobProcessingSuccess() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"invoiceId\":\"INV-9921\",\"amount\":450.00}");
        CreateJobRequest request = CreateJobRequest.builder()
                .taskType("GENERATE_INVOICE")
                .payload(payload)
                .maxRetries(3)
                .build();

        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID jobId = UUID.fromString(responseJson.get("id").asText());

        // Await background consumer processing (virtual thread + Redisson lock)
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Job job = jobRepository.findById(jobId).orElse(null);
                    assertThat(job).isNotNull();
                    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
                    assertThat(job.getErrorMessage()).isNull();
                });

        // Verify via REST API
        mockMvc.perform(get("/api/jobs/" + jobId)
                        .with(httpBasic("admin", "change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.taskType").value("GENERATE_INVOICE"));
    }

    @Test
    @DisplayName("Resilience: Failing job retries with exponential backoff and transitions to DLQ")
    void testFailingJobRetriesAndTransitionsToDLQ() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"simulateFailure\":true,\"failureReason\":\"Database timeout\"}");
        CreateJobRequest request = CreateJobRequest.builder()
                .taskType("FAILING_TASK")
                .payload(payload)
                .maxRetries(2)
                .build();

        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .with(httpBasic("admin", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID jobId = UUID.fromString(responseJson.get("id").asText());

        // Await failure retries (attempt 1 with 2s backoff, attempt 2 final DLQ)
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Job job = jobRepository.findById(jobId).orElse(null);
                    assertThat(job).isNotNull();
                    assertThat(job.getStatus()).isEqualTo(JobStatus.DLQ);
                    assertThat(job.getRetryCount()).isEqualTo(2);
                    assertThat(job.getErrorMessage()).isNotNull();
                });

        // Verify the job ID was placed into the Redis DLQ list
        String dlqJobId = redisTemplate.opsForList().rightPop(JobConsumerService.DLQ_QUEUE_NAME);
        assertThat(dlqJobId).isEqualTo(jobId.toString());
    }
}
