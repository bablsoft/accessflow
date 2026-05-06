import { jittered, sleep } from '@/mocks/delay';
import { DATASOURCES, PERMS } from '@/mocks/data';
import { buildMockSchema } from '@/mocks/schema';
import type { Datasource, DatasourcePermission, DatasourceSchema } from '@/types/api';

export async function listDatasources(): Promise<Datasource[]> {
  await jittered();
  return DATASOURCES;
}

export async function getDatasource(id: string): Promise<Datasource | null> {
  await jittered(80, 200);
  return DATASOURCES.find((d) => d.id === id) ?? null;
}

export async function getDatasourceSchema(id: string): Promise<DatasourceSchema> {
  await jittered();
  const ds = DATASOURCES.find((d) => d.id === id)!;
  return buildMockSchema(ds);
}

export async function listPermissions(dsId: string): Promise<DatasourcePermission[]> {
  await jittered(80, 200);
  return PERMS.filter((p) => p.datasource_id === dsId);
}

export async function testConnection(_id: string): Promise<{ ok: true; latencyMs: number }> {
  await sleep(900);
  return { ok: true, latencyMs: 42 };
}
