import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics for Prometheus/Grafana visibility
const jobsEnqueued = new Counter('k6_jobs_enqueued_total');
const jobsFailed = new Counter('k6_jobs_failed_total');
const jobCreationRate = new Rate('k6_job_creation_success_rate');
const jobCreationDuration = new Trend('k6_job_creation_duration_ms');

export const options = {
  // High throughput configuration
  vus: 50,                 // 50 concurrent virtual users
  iterations: 500,         // 500 total job creations
  duration: '60s',         // Run for up to 60 seconds
  thresholds: {
    'http_req_duration': ['p(95)<2000'],  // 95th percentile under 2s
    'k6_job_creation_success_rate': ['rate>0.95'], // >95% success
  },
  ext: {
    loadimpact: {
      projectID: 0,
      name: 'Distributed Job Queue Load Test',
    },
  },
};

// Base URL and auth credentials (Base64 of "admin:change-me")
const BASE_URL = 'http://localhost:8080';
const BASIC_AUTH = 'Basic YWRtaW46Y2hhbmdlLW1l';

// Task types to distribute load across
const TASK_TYPES = ['WELCOME_EMAIL', 'GENERATE_INVOICE', 'FAILING_TASK'];

export default function () {
  // Pick a task type round-robin style based on VU iteration
  const taskType = TASK_TYPES[__VU % TASK_TYPES.length];

  const payload = JSON.stringify({
    taskType: taskType,
    payload: { simulatedBy: 'k6', timestamp: Date.now() },
    maxRetries: 3,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': BASIC_AUTH,
    },
    tags: { taskType: taskType },
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/jobs`, payload, params);
  const duration = Date.now() - start;

  // Record custom metrics
  jobCreationDuration.add(duration, { taskType: taskType });

  const success = check(res, {
    [`POST /api/jobs ${taskType} → 201`]: (r) => r.status === 201,
  });

  jobCreationRate.add(success, { taskType: taskType });

  if (success) {
    jobsEnqueued.add(1, { taskType: taskType });
    // Optional: verify job was persisted (every 10th request)
    if (__VU % 10 === 0) {
      try {
        const body = JSON.parse(res.body);
        if (body.id) {
          const getRes = http.get(`${BASE_URL}/api/jobs/${body.id}`, {
            headers: { 'Authorization': BASIC_AUTH },
          });
          check(getRes, {
            [`GET /api/jobs/${body.id} → 200`]: (g) => g.status === 200,
          });
        }
      } catch (e) {
        // Ignore parse errors
      }
    }
  } else {
    jobsFailed.add(1, { taskType: taskType });
  }
}

export function teardown(data) {
  console.log(`Load test complete. Jobs enqueued: ${jobsEnqueued.count}, Failed: ${jobsFailed.count}`);
}
