import type { APIRequestContext } from '@playwright/test';
import { apiBase } from './datasources';

export interface CreatedDeploymentPipeline {
  id: string;
  name: string;
}

/** Creates a deployment pipeline (#696; admin-only endpoint — PERM_DEPLOYMENT_PIPELINE_MANAGE). */
export async function createDeploymentPipelineViaApi(
  request: APIRequestContext,
  token: string,
  options: {
    name: string;
    provider?: string;
    repositoryUrl?: string;
    aiAnalysisEnabled?: boolean;
    reviewPlanId?: string;
  },
): Promise<CreatedDeploymentPipeline> {
  const res = await request.post(`${apiBase()}/api/v1/deployment-pipelines`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: options.name,
      provider: options.provider ?? 'GENERIC',
      ...(options.repositoryUrl === undefined ? {} : { repository_url: options.repositoryUrl }),
      // Omitted → backend defaults to true. The e2e AI mock analyzes deployments too, but
      // most specs disable AI so the request lands in PENDING_REVIEW deterministically.
      ...(options.aiAnalysisEnabled === undefined
        ? {}
        : { ai_analysis_enabled: options.aiAnalysisEnabled }),
      ...(options.reviewPlanId === undefined ? {} : { review_plan_id: options.reviewPlanId }),
    },
  });
  if (!res.ok()) {
    throw new Error(`Create deployment pipeline failed: ${res.status()} ${await res.text()}`);
  }
  const body = (await res.json()) as { id: string; name: string };
  return { id: body.id, name: body.name };
}

export interface CreatedDeploymentEnvironment {
  id: string;
  name: string;
}

/** Adds an environment to a pipeline; defaults require review with 1 approval. */
export async function createDeploymentEnvironmentViaApi(
  request: APIRequestContext,
  token: string,
  pipelineId: string,
  options: {
    name: string;
    requiredApprovals?: number;
    requireReview?: boolean;
    /** Free-form grouping labels (#741) — at most 10, each at most 32 chars. */
    tags?: string[];
  },
): Promise<CreatedDeploymentEnvironment> {
  const res = await request.post(
    `${apiBase()}/api/v1/deployment-pipelines/${pipelineId}/environments`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        name: options.name,
        ...(options.tags === undefined ? {} : { tags: options.tags }),
        ...(options.requiredApprovals === undefined
          ? {}
          : { required_approvals: options.requiredApprovals }),
        ...(options.requireReview === undefined ? {} : { require_review: options.requireReview }),
      },
    },
  );
  if (!res.ok()) {
    throw new Error(`Create deployment environment failed: ${res.status()} ${await res.text()}`);
  }
  const body = (await res.json()) as { id: string; name: string };
  return { id: body.id, name: body.name };
}

/** Grants a user can_trigger (and optionally break-glass) on a pipeline. */
export async function grantDeploymentPermissionViaApi(
  request: APIRequestContext,
  token: string,
  pipelineId: string,
  userId: string,
  options: { canTrigger?: boolean; canBreakGlass?: boolean } = {},
): Promise<void> {
  const res = await request.post(
    `${apiBase()}/api/v1/deployment-pipelines/${pipelineId}/permissions`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        user_id: userId,
        can_trigger: options.canTrigger ?? true,
        can_break_glass: options.canBreakGlass ?? false,
      },
    },
  );
  if (!res.ok()) {
    throw new Error(`Grant deployment permission failed: ${res.status()} ${await res.text()}`);
  }
}

export interface TriggeredDeployment {
  id: string;
  status: string;
}

/**
 * Builds the auth header a deployment call should carry. Passing `apiKey` exercises the real
 * machine path (`X-API-Key`, resolved by ApiKeyAuthenticationFilter) instead of a bearer JWT.
 */
function authHeaders(token: string, apiKey?: string): Record<string, string> {
  return apiKey ? { 'X-API-Key': apiKey } : { Authorization: `Bearer ${token}` };
}

/** Triggers a deployment request the way a CI job would. */
export async function triggerDeploymentViaApi(
  request: APIRequestContext,
  token: string,
  options: {
    pipelineId: string;
    environment: string;
    version: string;
    externalRunId?: string;
    commitSha?: string;
    justification?: string;
    /** Authenticate with this raw API key instead of the bearer token. */
    apiKey?: string;
  },
): Promise<TriggeredDeployment> {
  const res = await request.post(`${apiBase()}/api/v1/deployment-requests`, {
    headers: { ...authHeaders(token, options.apiKey), 'X-AccessFlow-CI': 'true' },
    data: {
      pipeline_id: options.pipelineId,
      environment: options.environment,
      version: options.version,
      ...(options.externalRunId === undefined ? {} : { external_run_id: options.externalRunId }),
      ...(options.commitSha === undefined ? {} : { commit_sha: options.commitSha }),
      ...(options.justification === undefined ? {} : { justification: options.justification }),
    },
  });
  // 202 on first create, 200 on an idempotent replay of the same external run id.
  if (!res.ok()) {
    throw new Error(`Trigger deployment failed: ${res.status()} ${await res.text()}`);
  }
  const body = (await res.json()) as { id: string; status: string };
  return { id: body.id, status: body.status };
}

/** Polls GET /api/v1/deployment-requests/{id} until it reaches the wanted status. */
export async function waitForDeploymentStatus(
  request: APIRequestContext,
  token: string,
  requestId: string,
  wanted: string,
  timeoutMs = 15_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const res = await request.get(`${apiBase()}/api/v1/deployment-requests/${requestId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (res.ok()) {
      last = ((await res.json()) as { status: string }).status;
      if (last === wanted) return;
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error(`Deployment request ${requestId} never reached ${wanted} (last=${last})`);
}

export interface DeploymentGateResult {
  status: string;
  releasable: boolean;
  frozen: boolean;
}

/** Reads the fail-closed deployment gate for a request. */
export async function getDeploymentGateViaApi(
  request: APIRequestContext,
  token: string,
  requestId: string,
): Promise<DeploymentGateResult> {
  const res = await request.get(
    `${apiBase()}/api/v1/deployment-gate?request_id=${encodeURIComponent(requestId)}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!res.ok()) {
    throw new Error(`Deployment gate failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as DeploymentGateResult;
}

/** Best-effort delete tolerating 404, for afterAll cleanup. */
export async function deleteDeploymentPipelineViaApi(
  request: APIRequestContext,
  token: string,
  pipelineId: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/deployment-pipelines/${pipelineId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok() && res.status() !== 404) {
    throw new Error(`Delete deployment pipeline failed: ${res.status()} ${await res.text()}`);
  }
}

/**
 * Confirms the pipeline proceeded (`APPROVED → EXECUTED`). Re-evaluates releasability at this
 * instant, so a freeze window that opened during the poll loop still blocks with 409.
 */
export async function confirmDeploymentExecutionViaApi(
  request: APIRequestContext,
  token: string,
  requestId: string,
  apiKey?: string,
): Promise<{ status: string }> {
  const res = await request.post(
    `${apiBase()}/api/v1/deployment-requests/${requestId}/confirm-execution`,
    { headers: authHeaders(token, apiKey) },
  );
  if (!res.ok()) {
    throw new Error(`Confirm deployment execution failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as { status: string };
}

export interface ApiCallResult {
  ok: boolean;
  status: number;
  error: string | null;
}

/**
 * Reports the post-deploy outcome. Returns the raw result rather than throwing, so a spec can
 * assert the fail-closed conflict codes (409 DEPLOYMENT_OUTCOME_CONFLICT, self-acknowledge, …).
 */
export async function reportDeploymentOutcomeViaApi(
  request: APIRequestContext,
  token: string,
  requestId: string,
  outcome: 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK',
  options: { detail?: string; apiKey?: string } = {},
): Promise<ApiCallResult> {
  const res = await request.post(`${apiBase()}/api/v1/deployment-requests/${requestId}/outcome`, {
    headers: authHeaders(token, options.apiKey),
    data: { outcome, ...(options.detail === undefined ? {} : { detail: options.detail }) },
  });
  let error: string | null = null;
  if (!res.ok()) {
    error = ((await res.json().catch(() => ({}))) as { error?: string }).error ?? null;
  }
  return { ok: res.ok(), status: res.status(), error };
}

export interface RollbackReview {
  id: string;
  deployment_request_id: string;
  status: string;
  outcome_detail: string | null;
}

/** Lists the rollback follow-up reviews (JWT-side, PERM_DEPLOYMENT_REVIEW). */
export async function listDeploymentRollbackReviewsViaApi(
  request: APIRequestContext,
  token: string,
  status?: string,
  // The endpoint defaults to 20 rows; a long-lived local stack accumulates more than that and a
  // stale page would read as "the review was never opened" rather than as a paging artefact.
  size = 100,
): Promise<RollbackReview[]> {
  const params = new URLSearchParams({ size: String(size) });
  if (status) params.set('status', status);
  const res = await request.get(
    `${apiBase()}/api/v1/deployment-rollback-reviews?${params.toString()}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!res.ok()) {
    throw new Error(`List rollback reviews failed: ${res.status()} ${await res.text()}`);
  }
  return ((await res.json()) as { content: RollbackReview[] }).content;
}

/**
 * Acknowledges a rollback review. Returns the raw result so a spec can assert the
 * 409 the deployment's own submitter gets.
 */
export async function acknowledgeRollbackReviewViaApi(
  request: APIRequestContext,
  token: string,
  reviewId: string,
  comment?: string,
): Promise<ApiCallResult> {
  const res = await request.post(
    `${apiBase()}/api/v1/deployment-rollback-reviews/${reviewId}/acknowledge`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: comment === undefined ? {} : { comment },
    },
  );
  let error: string | null = null;
  if (!res.ok()) {
    error = ((await res.json().catch(() => ({}))) as { error?: string }).error ?? null;
  }
  return { ok: res.ok(), status: res.status(), error };
}

/**
 * Approves a deployment as a reviewer (JWT-side, PERM_DEPLOYMENT_REVIEW). Returns the raw result
 * rather than throwing, so a spec can assert the self-approval 409 with the same helper.
 */
export async function approveDeploymentViaApi(
  request: APIRequestContext,
  token: string,
  requestId: string,
  comment?: string,
): Promise<ApiCallResult & { resultingStatus: string | null }> {
  const res = await request.post(`${apiBase()}/api/v1/deployment-reviews/${requestId}/approve`, {
    headers: { Authorization: `Bearer ${token}` },
    // The endpoint declares a required @RequestBody, so an empty object is the minimum.
    data: comment === undefined ? {} : { comment },
  });
  const body = (await res.json().catch(() => ({}))) as {
    error?: string;
    resulting_status?: string;
  };
  return {
    ok: res.ok(),
    status: res.status(),
    error: res.ok() ? null : (body.error ?? null),
    resultingStatus: body.resulting_status ?? null,
  };
}

export interface DeploymentRoutingConditionsWire {
  environments: string[];
  providers: string[];
  min_risk_level: string | null;
  version_globs: string[];
  days_of_week: number[];
  start_time: string | null;
  end_time: string | null;
  timezone: string | null;
}

export interface DeploymentRoutingPolicyWire {
  id: string;
  pipeline_id: string | null;
  name: string;
  action: string;
  required_approvals: number | null;
  priority: number;
  enabled: boolean;
  conditions: DeploymentRoutingConditionsWire;
}

const ROUTING_POLICIES_PATH = '/api/v1/admin/deployment-routing-policies';

/** Lists every deployment routing policy in the org (the endpoint returns a bare array). */
export async function listDeploymentRoutingPoliciesViaApi(
  request: APIRequestContext,
  token: string,
): Promise<DeploymentRoutingPolicyWire[]> {
  const res = await request.get(`${apiBase()}${ROUTING_POLICIES_PATH}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) {
    throw new Error(`List deployment routing policies failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as DeploymentRoutingPolicyWire[];
}

/**
 * The next priority no policy holds yet.
 *
 * `priority` is UNIQUE per organization across scoped *and* global policies, and the create modal
 * defaults it to 100 — so a spec that accepts the default 409s forever once anything else in the
 * org sits at 100. Every policy a spec creates must take its priority from here.
 */
export async function nextFreeRoutingPriorityViaApi(
  request: APIRequestContext,
  token: string,
): Promise<number> {
  const existing = await listDeploymentRoutingPoliciesViaApi(request, token);
  return existing.reduce((max, p) => Math.max(max, p.priority), 100) + 10;
}

/** Creates a routing policy directly, for conditions the modal cannot comfortably drive. */
export async function createDeploymentRoutingPolicyViaApi(
  request: APIRequestContext,
  token: string,
  options: {
    name: string;
    action: string;
    priority: number;
    pipelineId?: string | null;
    requiredApprovals?: number | null;
    enabled?: boolean;
    conditions?: Partial<DeploymentRoutingConditionsWire>;
  },
): Promise<DeploymentRoutingPolicyWire> {
  const res = await request.post(`${apiBase()}${ROUTING_POLICIES_PATH}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: options.name,
      action: options.action,
      priority: options.priority,
      pipeline_id: options.pipelineId ?? null,
      required_approvals: options.requiredApprovals ?? null,
      // Sent explicitly rather than defaulted: an enabled org-global policy is a cross-spec
      // weapon, so a spec should never be able to create one by omission.
      enabled: options.enabled ?? true,
      ...(options.conditions === undefined ? {} : { conditions: options.conditions }),
    },
  });
  if (!res.ok()) {
    throw new Error(`Create deployment routing policy failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as DeploymentRoutingPolicyWire;
}

/**
 * Best-effort delete tolerating 404, for afterAll cleanup.
 *
 * `deployment_routing_policies` has no FK on `pipeline_id`, so deleting the pipeline does not
 * remove its policies — it orphans them and burns their priorities. Delete policies first.
 */
export async function deleteDeploymentRoutingPolicyViaApi(
  request: APIRequestContext,
  token: string,
  policyId: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}${ROUTING_POLICIES_PATH}/${policyId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(`Routing policy cleanup skipped: ${res.status()} ${await res.text()}`);
  }
}
