import type { APIRequestContext } from '@playwright/test';
import { apiBase } from './datasources';

export interface CreatedApiConnector {
  id: string;
  name: string;
}

/** Creates a minimal REST connector (no schema, no auth) for API-call group members (#559). */
export async function createApiConnectorViaApi(
  request: APIRequestContext,
  token: string,
  options: { name: string; baseUrl?: string },
): Promise<CreatedApiConnector> {
  const res = await request.post(`${apiBase()}/api/v1/api-connectors`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: options.name,
      protocol: 'REST',
      base_url: options.baseUrl ?? 'http://backend:8080',
      auth_method: 'NONE',
    },
  });
  if (!res.ok()) {
    throw new Error(`Create API connector failed: ${res.status()} ${await res.text()}`);
  }
  const body = (await res.json()) as { id: string; name: string };
  return { id: body.id, name: body.name };
}

export async function deleteApiConnectorViaApi(
  request: APIRequestContext,
  token: string,
  connectorId: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/api-connectors/${connectorId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok() && res.status() !== 404) {
    throw new Error(`Delete API connector failed: ${res.status()} ${await res.text()}`);
  }
}

/** Resolves the caller's own user id from their token via GET /api/v1/me (#567). */
export async function getCurrentUserIdViaApi(
  request: APIRequestContext,
  token: string,
): Promise<string> {
  const res = await request.get(`${apiBase()}/api/v1/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) {
    throw new Error(`Get current user failed: ${res.status()} ${await res.text()}`);
  }
  const body = (await res.json()) as { id: string };
  return body.id;
}

/** Grants a user read/write access on a connector so they can submit governed API calls (#567). */
export async function grantApiConnectorPermissionViaApi(
  request: APIRequestContext,
  token: string,
  connectorId: string,
  userId: string,
  options: { canRead?: boolean; canWrite?: boolean },
): Promise<void> {
  const res = await request.post(`${apiBase()}/api/v1/api-connectors/${connectorId}/permissions`, {
    headers: { Authorization: `Bearer ${token}` },
    // can_break_glass is a primitive boolean on the backend DTO; omitting it
    // deserializes as null and fails (FAIL_ON_NULL_FOR_PRIMITIVES), so send all three.
    data: {
      user_id: userId,
      can_read: options.canRead ?? false,
      can_write: options.canWrite ?? false,
      can_break_glass: false,
    },
  });
  if (!res.ok()) {
    throw new Error(`Grant API connector permission failed: ${res.status()} ${await res.text()}`);
  }
}

export interface SubmittedApiRequest {
  id: string;
  status: string;
}

/** Submits a governed API call; a write op against a default connector lands in PENDING_REVIEW (#567). */
export async function submitApiRequestViaApi(
  request: APIRequestContext,
  token: string,
  options: { connectorId: string; verb: string; requestPath: string; justification?: string },
): Promise<SubmittedApiRequest> {
  const res = await request.post(`${apiBase()}/api/v1/api-requests`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      connector_id: options.connectorId,
      verb: options.verb,
      request_path: options.requestPath,
      justification: options.justification ?? null,
    },
  });
  if (!res.ok()) {
    throw new Error(`Submit API request failed: ${res.status()} ${await res.text()}`);
  }
  const body = (await res.json()) as { id: string; status: string };
  return { id: body.id, status: body.status };
}

/** Polls GET /api/v1/api-requests/{id} until it reaches the wanted status (routing is async, #567). */
export async function waitForApiRequestStatus(
  request: APIRequestContext,
  token: string,
  requestId: string,
  wanted: string,
  timeoutMs = 15_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const res = await request.get(`${apiBase()}/api/v1/api-requests/${requestId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (res.ok()) {
      last = ((await res.json()) as { status: string }).status;
      if (last === wanted) return;
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error(`API request ${requestId} never reached ${wanted} (last=${last})`);
}
