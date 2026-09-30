import type { APIRequestContext } from '@playwright/test';
import { apiBase } from './datasources';

interface PromotionSummary {
  id: string;
  environment_id: string;
  status: string;
  error_message?: string;
}

const TERMINAL = new Set(['APPLIED', 'PARTIALLY_APPLIED', 'FAILED', 'CANCELLED']);

/**
 * Polls GET /api/v1/schema-change-sets/{id}/promotions until the promotion to the given
 * environment reaches the wanted status. A promotion executes through the request-group run job,
 * whose ShedLock hold floors the wait at ~30s under e2e.
 */
export async function waitForSchemaChangePromotionStatus(
  request: APIRequestContext,
  token: string,
  changeSetId: string,
  environmentId: string,
  wanted: string,
  timeoutMs = 120_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const res = await request.get(
      `${apiBase()}/api/v1/schema-change-sets/${changeSetId}/promotions`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (res.ok()) {
      const promotions = (await res.json()) as PromotionSummary[];
      const promotion = promotions.find((p) => p.environment_id === environmentId);
      last = promotion ? `${promotion.status}${promotion.error_message ? `: ${promotion.error_message}` : ''}` : '';
      if (promotion?.status === wanted) return;
      if (promotion && TERMINAL.has(promotion.status)) {
        throw new Error(`Promotion of ${changeSetId} to ${environmentId} ended ${last}, not ${wanted}`);
      }
    }
    await new Promise((r) => setTimeout(r, 1_000));
  }
  throw new Error(`Promotion of ${changeSetId} to ${environmentId} never reached ${wanted} (last=${last})`);
}
