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
