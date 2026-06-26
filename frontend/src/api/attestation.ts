import { apiClient } from './client';
import type {
  AttestationCampaign,
  AttestationCampaignPage,
  AttestationCampaignStatus,
  AttestationDecisionResponse,
  AttestationItemPage,
  BulkAttestationDecisionRequest,
  BulkAttestationDecisionResponse,
  CreateAttestationCampaignRequest,
} from '@/types/api';

const ADMIN_BASE = '/api/v1/admin/attestation-campaigns';
const REVIEW_BASE = '/api/v1/reviews/attestations';

export interface CampaignListFilters {
  status?: AttestationCampaignStatus;
  page?: number;
  size?: number;
}

export interface CampaignItemsFilters {
  page?: number;
  size?: number;
}

export interface WorklistFilters {
  page?: number;
  size?: number;
}

export const attestationKeys = {
  all: ['attestation'] as const,
  campaigns: () => ['attestation', 'campaigns'] as const,
  campaignList: (filters: CampaignListFilters) => ['attestation', 'campaigns', 'list', filters] as const,
  campaignDetail: (id: string) => ['attestation', 'campaigns', 'detail', id] as const,
  campaignItems: (id: string, filters: CampaignItemsFilters) =>
    ['attestation', 'campaigns', 'detail', id, 'items', filters] as const,
  worklist: () => ['attestation', 'worklist'] as const,
  worklistFor: (filters: WorklistFilters) => ['attestation', 'worklist', filters] as const,
};

// ── Admin ───────────────────────────────────────────────────────────────────

export async function listCampaigns(
  filters: CampaignListFilters = {},
): Promise<AttestationCampaignPage> {
  const params: Record<string, string | number> = {};
  if (filters.status) params.status = filters.status;
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<AttestationCampaignPage>(ADMIN_BASE, { params });
  return data;
}

export async function getCampaign(id: string): Promise<AttestationCampaign> {
  const { data } = await apiClient.get<AttestationCampaign>(`${ADMIN_BASE}/${id}`);
  return data;
}

export async function createCampaign(
  body: CreateAttestationCampaignRequest,
): Promise<AttestationCampaign> {
  const { data } = await apiClient.post<AttestationCampaign>(ADMIN_BASE, body);
  return data;
}

export async function cancelCampaign(id: string): Promise<void> {
  await apiClient.post(`${ADMIN_BASE}/${id}/cancel`);
}

export async function openCampaign(id: string): Promise<AttestationCampaign> {
  const { data } = await apiClient.post<AttestationCampaign>(`${ADMIN_BASE}/${id}/open`);
  return data;
}

export async function listCampaignItems(
  id: string,
  filters: CampaignItemsFilters = {},
): Promise<AttestationItemPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<AttestationItemPage>(`${ADMIN_BASE}/${id}/items`, {
    params,
  });
  return data;
}

export interface EvidenceExportResult {
  blob: Blob;
  filename: string;
  truncated: boolean;
}

export async function exportEvidenceCsv(campaignId: string): Promise<EvidenceExportResult> {
  const response = await apiClient.get<Blob>(`${ADMIN_BASE}/${campaignId}/evidence.csv`, {
    responseType: 'blob',
  });
  const disposition = response.headers['content-disposition'];
  const filename =
    parseFilename(typeof disposition === 'string' ? disposition : undefined) ??
    `attestation-campaign-${campaignId}-evidence.csv`;
  const truncatedHeader = response.headers['x-accessflow-export-truncated'];
  return {
    blob: response.data,
    filename,
    truncated: typeof truncatedHeader === 'string' && truncatedHeader.toLowerCase() === 'true',
  };
}

function parseFilename(header: string | undefined): string | null {
  if (!header) return null;
  const match = /filename="([^"]+)"/i.exec(header);
  return match?.[1] ?? null;
}

// ── Reviewer worklist ────────────────────────────────────────────────────────

export async function listAttestationWorklist(
  filters: WorklistFilters = {},
): Promise<AttestationItemPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<AttestationItemPage>(`${REVIEW_BASE}/items`, { params });
  return data;
}

export async function certifyItem(
  itemId: string,
  comment?: string,
): Promise<AttestationDecisionResponse> {
  const { data } = await apiClient.post<AttestationDecisionResponse>(
    `${REVIEW_BASE}/items/${itemId}/certify`,
    { comment: comment ?? null },
  );
  return data;
}

export async function revokeItem(
  itemId: string,
  comment: string,
): Promise<AttestationDecisionResponse> {
  const { data } = await apiClient.post<AttestationDecisionResponse>(
    `${REVIEW_BASE}/items/${itemId}/revoke`,
    { comment },
  );
  return data;
}

export async function bulkDecideItems(
  body: BulkAttestationDecisionRequest,
): Promise<BulkAttestationDecisionResponse> {
  const { data } = await apiClient.post<BulkAttestationDecisionResponse>(
    `${REVIEW_BASE}/items/bulk`,
    {
      item_ids: body.item_ids,
      decision: body.decision,
      comment: body.comment ?? null,
    },
  );
  return data;
}
