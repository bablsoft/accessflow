import { apiClient } from './client';
import type {
  ReviewPlan,
  ReviewPlanTemplate,
  ReviewPlanWriteRequest,
} from '@/types/api';

const BASE = '/api/v1/review-plans';

interface ReviewPlanListEnvelope {
  content: ReviewPlan[];
}

interface ReviewPlanTemplateListEnvelope {
  content: ReviewPlanTemplate[];
}

export const reviewPlanKeys = {
  all: ['reviewPlans'] as const,
  lists: () => ['reviewPlans', 'list'] as const,
  details: () => ['reviewPlans', 'detail'] as const,
  detail: (id: string) => ['reviewPlans', 'detail', id] as const,
  templates: () => ['reviewPlans', 'templates'] as const,
};

export async function listReviewPlans(): Promise<ReviewPlan[]> {
  const { data } = await apiClient.get<ReviewPlanListEnvelope>(BASE);
  return data.content;
}

export async function listReviewPlanTemplates(): Promise<ReviewPlanTemplate[]> {
  const { data } = await apiClient.get<ReviewPlanTemplateListEnvelope>(
    `${BASE}/templates`,
  );
  return data.content;
}

export async function getReviewPlan(id: string): Promise<ReviewPlan> {
  const { data } = await apiClient.get<ReviewPlan>(`${BASE}/${id}`);
  return data;
}

export async function createReviewPlan(
  payload: ReviewPlanWriteRequest,
): Promise<ReviewPlan> {
  const { data } = await apiClient.post<ReviewPlan>(BASE, payload);
  return data;
}

export async function updateReviewPlan(
  id: string,
  payload: ReviewPlanWriteRequest,
): Promise<ReviewPlan> {
  const { data } = await apiClient.put<ReviewPlan>(`${BASE}/${id}`, payload);
  return data;
}

export async function deleteReviewPlan(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}
