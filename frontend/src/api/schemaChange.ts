import { apiClient } from './client';
import type {
  CreateSchemaChangeSetInput,
  SchemaChangeLadder,
  SchemaChangePipeline,
  SchemaChangePromotion,
  SchemaChangeSet,
  SchemaChangeSetPage,
  SchemaChangeSetStatus,
  SchemaDriftFinding,
  SchemaDriftFindingPage,
  SchemaDriftFindingStatus,
  SchemaDriftScan,
  SchemaDriftScanPage,
  UpdateSchemaChangeSetInput,
} from '@/types/api';

const SETS = '/api/v1/schema-change-sets';
const PROMOTIONS = '/api/v1/schema-change-promotions';
const DRIFT = '/api/v1/schema-drift';

export interface SchemaChangeSetListFilters {
  page?: number;
  size?: number;
  pipeline_id?: string;
  status?: SchemaChangeSetStatus;
}

export interface SchemaDriftFindingFilters {
  page?: number;
  size?: number;
  pipeline_id?: string;
  environment_id?: string;
  status?: SchemaDriftFindingStatus;
}

export interface SchemaDriftScanFilters {
  page?: number;
  size?: number;
  pipeline_id?: string;
  environment_id?: string;
}

export const schemaChangeKeys = {
  all: ['schema-change'] as const,
  pipelines: () => ['schema-change', 'pipelines'] as const,
  lists: () => ['schema-change', 'sets', 'list'] as const,
  list: (filters: SchemaChangeSetListFilters) =>
    ['schema-change', 'sets', 'list', filters] as const,
  details: () => ['schema-change', 'sets', 'detail'] as const,
  detail: (id: string) => ['schema-change', 'sets', 'detail', id] as const,
  ladder: (id: string) => ['schema-change', 'sets', 'detail', id, 'ladder'] as const,
  promotions: (id: string) => ['schema-change', 'sets', 'detail', id, 'promotions'] as const,
  drift: () => ['schema-change', 'drift'] as const,
  driftFindings: (filters: SchemaDriftFindingFilters) =>
    ['schema-change', 'drift', 'findings', filters] as const,
  driftScans: (filters: SchemaDriftScanFilters) =>
    ['schema-change', 'drift', 'scans', filters] as const,
};

type Params = Record<string, string | number>;

function params(filters: object): Params {
  const out: Params = {};
  for (const [key, value] of Object.entries(filters)) {
    if (typeof value === 'number' || (typeof value === 'string' && value !== '')) {
      out[key] = value;
    }
  }
  return out;
}

export async function listSchemaChangePipelines(): Promise<SchemaChangePipeline[]> {
  const { data } = await apiClient.get<SchemaChangePipeline[]>('/api/v1/schema-change-pipelines');
  return data;
}

export async function listSchemaChangeSets(
  filters: SchemaChangeSetListFilters = {},
): Promise<SchemaChangeSetPage> {
  const { data } = await apiClient.get<SchemaChangeSetPage>(SETS, { params: params(filters) });
  return data;
}

export async function getSchemaChangeSet(id: string): Promise<SchemaChangeSet> {
  const { data } = await apiClient.get<SchemaChangeSet>(`${SETS}/${id}`);
  return data;
}

export async function createSchemaChangeSet(
  input: CreateSchemaChangeSetInput,
): Promise<SchemaChangeSet> {
  const { data } = await apiClient.post<SchemaChangeSet>(SETS, input);
  return data;
}

export async function updateSchemaChangeSet(
  id: string,
  input: UpdateSchemaChangeSetInput,
): Promise<SchemaChangeSet> {
  const { data } = await apiClient.put<SchemaChangeSet>(`${SETS}/${id}`, input);
  return data;
}

export async function replaceSchemaChangeSetStatements(
  id: string,
  statements: string[],
): Promise<SchemaChangeSet> {
  const { data } = await apiClient.put<SchemaChangeSet>(`${SETS}/${id}/statements`, {
    statements: statements.map((sql_text) => ({ sql_text })),
  });
  return data;
}

export async function deleteSchemaChangeSet(id: string): Promise<void> {
  await apiClient.delete(`${SETS}/${id}`);
}

export async function getSchemaChangeLadder(id: string): Promise<SchemaChangeLadder> {
  const { data } = await apiClient.get<SchemaChangeLadder>(`${SETS}/${id}/ladder`);
  return data;
}

export async function listSchemaChangePromotions(id: string): Promise<SchemaChangePromotion[]> {
  const { data } = await apiClient.get<SchemaChangePromotion[]>(`${SETS}/${id}/promotions`);
  return data;
}

export async function promoteSchemaChangeSet(
  id: string,
  environmentId: string,
): Promise<SchemaChangePromotion> {
  const { data } = await apiClient.post<SchemaChangePromotion>(`${SETS}/${id}/promotions`, {
    environment_id: environmentId,
  });
  return data;
}

export async function cancelSchemaChangePromotion(promotionId: string): Promise<void> {
  await apiClient.post(`${PROMOTIONS}/${promotionId}/cancel`);
}

export async function listSchemaDriftFindings(
  filters: SchemaDriftFindingFilters = {},
): Promise<SchemaDriftFindingPage> {
  const { data } = await apiClient.get<SchemaDriftFindingPage>(`${DRIFT}/findings`, {
    params: params(filters),
  });
  return data;
}

export async function listSchemaDriftScans(
  filters: SchemaDriftScanFilters = {},
): Promise<SchemaDriftScanPage> {
  const { data } = await apiClient.get<SchemaDriftScanPage>(`${DRIFT}/scans`, {
    params: params(filters),
  });
  return data;
}

export async function requestSchemaDriftScan(environmentId: string): Promise<SchemaDriftScan> {
  const { data } = await apiClient.post<SchemaDriftScan>(`${DRIFT}/scans`, {
    environment_id: environmentId,
  });
  return data;
}

export async function acknowledgeSchemaDriftFinding(id: string): Promise<SchemaDriftFinding> {
  const { data } = await apiClient.post<SchemaDriftFinding>(`${DRIFT}/findings/${id}/acknowledge`);
  return data;
}
