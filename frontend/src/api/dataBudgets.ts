import { apiClient } from './client';
import type { DataBudget, DataBudgetInput, DataBudgetStatus } from '@/types/api';

const base = (datasourceId: string) => `/api/v1/datasources/${datasourceId}/data-budgets`;

export const dataBudgetKeys = {
  all: ['data-budgets'] as const,
  list: (datasourceId: string) => ['data-budgets', 'list', datasourceId] as const,
  mine: (datasourceId: string) => ['data-budgets', 'me', datasourceId] as const,
  forUser: (userId: string) => ['data-budgets', 'user', userId] as const,
};

export async function listDataBudgets(datasourceId: string): Promise<DataBudget[]> {
  const { data } = await apiClient.get<{ content: DataBudget[] }>(base(datasourceId));
  return data.content;
}

export async function createDataBudget(
  datasourceId: string,
  input: DataBudgetInput,
): Promise<DataBudget> {
  const { data } = await apiClient.post<DataBudget>(base(datasourceId), input);
  return data;
}

export async function updateDataBudget(
  datasourceId: string,
  budgetId: string,
  input: DataBudgetInput,
): Promise<DataBudget> {
  const { data } = await apiClient.put<DataBudget>(`${base(datasourceId)}/${budgetId}`, input);
  return data;
}

export async function deleteDataBudget(datasourceId: string, budgetId: string): Promise<void> {
  await apiClient.delete(`${base(datasourceId)}/${budgetId}`);
}

export async function getMyDataBudgetStatus(datasourceId: string): Promise<DataBudgetStatus> {
  const { data } = await apiClient.get<DataBudgetStatus>(`${base(datasourceId)}/me`);
  return data;
}

export async function getUserDataBudgetUsage(userId: string): Promise<DataBudgetStatus[]> {
  const { data } = await apiClient.get<{ content: DataBudgetStatus[] }>(
    `/api/v1/admin/users/${userId}/data-budget-usage`,
  );
  return data.content;
}
