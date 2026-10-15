# Distributed Job Queue & Notification Engine

A Spring Boot 3 distributed job queue with PostgreSQL persistence, Redis-backed
queueing, Redisson distributed locking, Java 21 virtual threads, retry with
exponential backoff, dead-letter queue, and observability (Actuator + Prometheus
+ Swagger UI).

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring Boot 3.3.4](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/build-Maven-blue.svg)](https://maven.apache.org/)
[![Docker Compose](https://img.shields.io/badge/infra-Docker%20Compose-0db7ed.svg)](https://docs.docker.com/compose/)

---

## Features

- **REST API**: `POST /api/jobs` create jobs, `GET /api/jobs/{id}` retrieve status
- **Durable storage**: Jobs persisted in PostgreSQL (JSONB payload, status enum)
- **Async processing**: Background consumer polls Redis list queue (`job:queue`)
- **Distributed locking**: Redisson `RLock` prevents concurrent processing of the same job
- **Reliability**: Retry with exponential backoff → dead-letter queue (`job:dlq`)
- **Concurrency**: Java 21 virtual threads for the worker and retry timers
- **Observability**: Actuator health/metrics, Micrometer + Prometheus, Grafana
- **API docs**: OpenAPI 3 + Swagger UI (`/v3/api-docs`, `/swagger-ui.html`)
- **Security**: Basic auth for API, actuator, and Swagger endpoints

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.3.4 |
| Web | Spring MVC |
| Data | Spring Data JPA, Hibernate |
| Database | PostgreSQL 15 |
| Cache/Queue | Redis 7 |
| Distributed Lock | Redisson 3.37.0 |
| Concurrency | Java 21 Virtual Threads |
| Metrics | Micrometer + Prometheus |
| API Docs | springdoc-openapi 2.6.0 (Swagger UI) |
| Security | Spring Security (Basic Auth) |
| Build | Maven Wrapper (`./mvnw`) |
| Tests | JUnit 5, Testcontainers, Awaitility |

---

## Architecture

```
Client → POST /api/jobs → JobProducerService
                                │
                                ├─ saves Job (PENDING) → PostgreSQL
                                └─ LPUSH jobId → Redis (job:queue)

JobConsumerService (virtual thread worker)
                                │
                                └─ RPOP jobId → Redis (job:queue)
                                        │
                                        ├─ Redisson lock: lock:job:<id>
                                        ├─ set PROCESSING → PostgreSQL
                                        ├─ JobExecutor.execute()
                                        ├─ success → COMPLETED
                                        └─ failure → retry w/ backoff → DLQ (job:dlq)
```

---

## Quick Start

### Prerequisites
- Java 21 (JDK)
- Docker & Docker Compose

### 1. Start infrastructure (PostgreSQL, Redis, Prometheus, Grafana)

```sh
docker compose up -d
```

Services:
- PostgreSQL: `localhost:5432` (db: `jobengine`, user: `postgres`, pass: `password`)
- Redis: `localhost:6379`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (admin / admin)

### 2. Build and run the application

```sh
./mvnw clean compile
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`.

### 3. Create a job (Basic Auth required)

Default credentials: `admin` / `change-me` (override via `APP_SECURITY_USER` / `APP_SECURITY_PASSWORD`).

```sh
curl -u admin:change-me -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"taskType":"WELCOME_EMAIL"}'

curl -u admin:change-me http://localhost:8080/api/jobs/{id}
```

Supported `taskType` values: `WELCOME_EMAIL`, `GENERATE_INVOICE`, `FAILING_TASK` (exercises retry → DLQ).

### 4. OpenAPI / Swagger UI

- OpenAPI JSON: `http://localhost:8080/v3/api-docs` (auth required)
- Swagger UI: `http://localhost:8080/swagger-ui.html` (auth required)

### 5. Observability

- Health (public): `http://localhost:8080/actuator/health`
- Metrics (auth): `http://localhost:8080/actuator/prometheus`
- Prometheus scrapes `/actuator/prometheus` per `prometheus.yml`

---

## Configuration (`application.yml`)

| Property | Description | Default |
|----------|-------------|---------|
| `server.port` | HTTP port | `8080` |
| `spring.datasource.*` | PostgreSQL connection | `localhost:5432/jobengine` |
| `spring.data.redis.*` | Redis connection | `localhost:6379` |
| `spring.security.user.name` | Basic auth username | `admin` |
| `spring.security.user.password` | Basic auth password | `change-me` |
| `APP_SECURITY_USER` | Env override for username | — |
| `APP_SECURITY_PASSWORD` | Env override for password | — |

---

## Testing

```sh
./mvnw test
```

Integration tests use Testcontainers (spins up real PostgreSQL/Redis containers), so Docker must be running.

---

## Project Structure

```
src/main/java/com/jobengine/
├── JobEngineApplication.java
├── controller/JobController.java
├── config/SecurityConfig.java
├── config/RedisConfig.java
├── dto/CreateJobRequest.java
├── dto/JobResponse.java
├── exception/GlobalExceptionHandler.java
├── exception/ErrorResponse.java
├── model/Job.java
├── model/JobStatus.java
├── repository/JobRepository.java
└── service/
    ├── JobProducerService.java
    ├── JobConsumerService.java
    ├── JobExecutor.java
    └── MetricsService.java
src/main/resources/
├── application.yml
└── schema.sql
```

---

## Production Checklist

- [x] Spring Security (Basic Auth) on API, actuator, Swagger
- [x] Global exception handling (structured JSON error responses)
- [x] Java 21 pinned via `.mvn/jvm.config`
- [x] Swagger/OpenAPI 3 documentation
- [ ] Replace `NoOpPasswordEncoder` with `BCryptPasswordEncoder`
- [ ] Externalize secrets (environment variables / secret manager)
- [ ] Add HTTPS / TLS termination
- [ ] Add CI/CD pipeline (see `.github/workflows/ci.yml`)
- [ ] Add database migration tool (Flyway/Liquibase) instead of `schema.sql`
- [ ] Add distributed tracing (OpenTelemetry / Sleuth)
