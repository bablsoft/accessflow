// k6 load test for issue #49: verifies the global query-execution concurrency budget holds.
//
// Drives POST /api/v1/queries/break-glass with a SELECT pg_sleep(N) so every request holds a
// semaphore permit for a deterministic time. With the backend tuned down
// (ACCESSFLOW_PROXY_EXECUTION_MAX_CONCURRENT=4, ACCESSFLOW_PROXY_EXECUTION_ACQUIRE_TIMEOUT=1s),
// 20 virtual users must produce a stream of 503 QUERY_CONCURRENCY_LIMIT rejections while
// successful throughput stays at or below max_concurrent / sleep_seconds. See README.md for the
// full setup (datasource + can_break_glass grant + JWT).
//
// Run:
//   k6 run -e BASE_URL=http://localhost:8080 -e TOKEN=<jwt> -e DATASOURCE_ID=<uuid> \
//     [-e SLEEP_SECONDS=2] [-e MAX_CONCURRENT=4] backend/load-tests/concurrency-budget.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
const DATASOURCE_ID = __ENV.DATASOURCE_ID;
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || 2);
const MAX_CONCURRENT = Number(__ENV.MAX_CONCURRENT || 4);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 60);

const executed = new Counter('queries_executed');
const rejected = new Counter('queries_rejected_concurrency');
const unexpectedStatus = new Rate('unexpected_status');

export const options = {
  scenarios: {
    saturate: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: `${DURATION_SECONDS}s`,
    },
  },
  thresholds: {
    // The budget must actually reject overflow traffic…
    queries_rejected_concurrency: ['count > 0'],
    // …and everything is either a 200 execution or a 503 concurrency rejection.
    unexpected_status: ['rate == 0'],
  },
};

export default function run() {
  const res = http.post(
    `${BASE_URL}/api/v1/queries/break-glass`,
    JSON.stringify({
      datasource_id: DATASOURCE_ID,
      sql: `SELECT pg_sleep(${SLEEP_SECONDS})`,
      justification: 'k6 concurrency-budget load test (issue #49)',
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${TOKEN}`,
      },
      timeout: `${SLEEP_SECONDS * 2 + 30}s`,
    },
  );

  if (res.status === 200) {
    executed.add(1);
    unexpectedStatus.add(false);
  } else if (res.status === 503) {
    const body = res.json();
    const isConcurrency = check(body, {
      '503 carries QUERY_CONCURRENCY_LIMIT': (b) => b && b.error === 'QUERY_CONCURRENCY_LIMIT',
    });
    rejected.add(1);
    unexpectedStatus.add(!isConcurrency);
  } else {
    unexpectedStatus.add(true);
    console.error(`unexpected status ${res.status}: ${res.body}`);
  }
}

export function handleSummary(data) {
  const ok = data.metrics.queries_executed
    ? data.metrics.queries_executed.values.count
    : 0;
  // Cap check: with each execution holding a permit for SLEEP_SECONDS, at most
  // MAX_CONCURRENT / SLEEP_SECONDS executions per second can complete. Allow 25% slack for
  // ramp-up/teardown edges.
  const maxAllowed = ((MAX_CONCURRENT / SLEEP_SECONDS) * DURATION_SECONDS) * 1.25;
  const capHolds = ok <= maxAllowed;
  const summary = {
    executed: ok,
    rejected: data.metrics.queries_rejected_concurrency
      ? data.metrics.queries_rejected_concurrency.values.count
      : 0,
    max_allowed_executions: Math.floor(maxAllowed),
    cap_holds: capHolds,
  };
  if (!capHolds) {
    // Non-zero exit so CI or scripts can detect a broken cap.
    throw new Error(
      `concurrency cap violated: ${ok} executions > allowed ${Math.floor(maxAllowed)}`,
    );
  }
  return {
    stdout: `\nconcurrency-budget summary: ${JSON.stringify(summary, null, 2)}\n`,
  };
}
