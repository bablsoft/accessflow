import { apiClient } from './client';
import type { Connector, ConnectorListResponse } from '@/types/api';

const BASE = '/api/v1/datasources/connectors';

export const connectorKeys = {
  all: ['connectors'] as const,
  lists: () => ['connectors', 'list'] as const,
};

export async function listConnectors(): Promise<Connector[]> {
  const { data } = await apiClient.get<ConnectorListResponse>(BASE);
  return data.connectors;
}

export async function installConnector(id: string): Promise<Connector> {
  const { data } = await apiClient.post<Connector>(`${BASE}/${id}/install`);
  return data;
}
