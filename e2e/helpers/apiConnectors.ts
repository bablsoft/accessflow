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
    data: {
      user_id: userId,
      can_read: options.canRead ?? false,
      can_write: options.canWrite ?? false,
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
