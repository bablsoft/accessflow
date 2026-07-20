import { apiClient } from './client';
import type {
  ApiConnectorVariable,
  ApiConnectorVariableSummary,
  CreateApiConnectorVariableInput,
  UpdateApiConnectorVariableInput,
} from '@/types/api';

const base = (connectorId: string) => `/api/v1/api-connectors/${connectorId}/variables`;

export const apiConnectorVariableKeys = {
  all: ['api-connector-variables'] as const,
  list: (connectorId: string) => ['api-connector-variables', 'list', connectorId] as const,
  summary: (connectorId: string) => ['api-connector-variables', 'summary', connectorId] as const,
};

export async function listApiConnectorVariables(
  connectorId: string,
): Promise<ApiConnectorVariable[]> {
  const { data } = await apiClient.get<{ content: ApiConnectorVariable[] }>(base(connectorId));
  return data.content;
}

/**
 * The submitter-safe projection, used by the request composer. Unlike the admin list it needs no
 * connector-manage permission and carries no expression, algorithm or secret.
 */
export async function listApiConnectorVariableSummaries(
  connectorId: string,
): Promise<ApiConnectorVariableSummary[]> {
  const { data } = await apiClient.get<{ content: ApiConnectorVariableSummary[] }>(
    `/api/v1/api-connectors/${connectorId}/variables/summary`,
  );
  return data.content;
}

export async function createApiConnectorVariable(
  connectorId: string,
  input: CreateApiConnectorVariableInput,
): Promise<ApiConnectorVariable> {
  const { data } = await apiClient.post<ApiConnectorVariable>(base(connectorId), input);
  return data;
}

export async function updateApiConnectorVariable(
  connectorId: string,
  variableId: string,
  input: UpdateApiConnectorVariableInput,
): Promise<ApiConnectorVariable> {
  const { data } = await apiClient.put<ApiConnectorVariable>(
    `${base(connectorId)}/${variableId}`,
    input,
  );
  return data;
}

export async function deleteApiConnectorVariable(
  connectorId: string,
  variableId: string,
): Promise<void> {
  await apiClient.delete(`${base(connectorId)}/${variableId}`);
}

/** The body must list every variable on the connector exactly once, in evaluation order. */
export async function reorderApiConnectorVariables(
  connectorId: string,
  variableIds: string[],
): Promise<ApiConnectorVariable[]> {
  const { data } = await apiClient.put<{ content: ApiConnectorVariable[] }>(
    `${base(connectorId)}/order`,
    { variable_ids: variableIds },
  );
  return data.content;
}
