import type { APIRequestContext } from '@playwright/test';
import { apiBase } from './datasources';

const BASE = () => `${apiBase()}/api/v1/admin/service-accounts`;

export interface CreatedServiceAccount {
  id: string;
  email: string;
  display_name: string;
  managed_by: 'UI' | 'BOOTSTRAP';
}

/** Creates a UI-managed service account (#871; PERM_SERVICE_ACCOUNT_MANAGE). */
export async function createServiceAccountViaApi(
  request: APIRequestContext,
  token: string,
  options: { email: string; displayName: string; ownerUserId?: string; description?: string },
): Promise<CreatedServiceAccount> {
  const res = await request.post(BASE(), {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      email: options.email,
      display_name: options.displayName,
      ...(options.ownerUserId === undefined ? {} : { owner_user_id: options.ownerUserId }),
      ...(options.description === undefined ? {} : { description: options.description }),
    },
  });
  if (!res.ok()) {
    throw new Error(`Create service account failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as CreatedServiceAccount;
}

/** Issues a key on the account's behalf; the plaintext is returned exactly once. */
export async function issueServiceAccountKeyViaApi(
  request: APIRequestContext,
  token: string,
  accountId: string,
  name: string,
): Promise<{ id: string; rawKey: string }> {
  const res = await request.post(`${BASE()}/${accountId}/api-keys`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { name },
  });
  if (!res.ok()) {
    throw new Error(`Issue service account key failed: ${res.status()} ${await res.text()}`);
  }
  const body = (await res.json()) as { api_key: { id: string }; raw_key: string };
  return { id: body.api_key.id, rawKey: body.raw_key };
}

/** Deactivates an account (`204`); its keys stop authenticating, nothing is revoked. */
export async function deactivateServiceAccountViaApi(
  request: APIRequestContext,
  token: string,
  accountId: string,
): Promise<void> {
  const res = await request.delete(`${BASE()}/${accountId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok() && res.status() !== 404) {
    throw new Error(`Deactivate service account failed: ${res.status()} ${await res.text()}`);
  }
}

/** Finds a service account by email across the paginated admin list. */
export async function findServiceAccountByEmailViaApi(
  request: APIRequestContext,
  token: string,
  email: string,
): Promise<CreatedServiceAccount | undefined> {
  for (let page = 0; page < 20; page++) {
    const res = await request.get(`${BASE()}?page=${page}&size=100`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok()) {
      throw new Error(`List service accounts failed: ${res.status()} ${await res.text()}`);
    }
    const body = (await res.json()) as { content: CreatedServiceAccount[]; total_pages: number };
    const hit = body.content.find((a) => a.email === email);
    if (hit) return hit;
    if (page + 1 >= body.total_pages) return undefined;
  }
  return undefined;
}
