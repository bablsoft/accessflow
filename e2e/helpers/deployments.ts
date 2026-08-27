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
  options: { name: string; requiredApprovals?: number; requireReview?: boolean },
): Promise<CreatedDeploymentEnvironment> {
  const res = await request.post(
    `${apiBase()}/api/v1/deployment-pipelines/${pipelineId}/environments`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        name: options.name,
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

/** Triggers a deployment request the way a CI job would (bearer token works like an API key). */
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
  },
): Promise<TriggeredDeployment> {
  const res = await request.post(`${apiBase()}/api/v1/deployment-requests`, {
    headers: { Authorization: `Bearer ${token}`, 'X-AccessFlow-CI': 'true' },
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
