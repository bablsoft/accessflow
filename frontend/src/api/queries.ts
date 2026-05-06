import type { AiAnalysis, QueryRequest, QueryStatus } from '@/types/api';
import { useQueriesStore } from '@/store/queriesStore';
import { useAuthStore } from '@/store/authStore';
import { DATASOURCES } from '@/mocks/data';
import { mockAnalyze, deriveQueryType } from '@/mocks/analyzer';
import { jittered } from '@/mocks/delay';

export interface SubmitQueryInput {
  datasource_id: string;
  sql: string;
  justification: string;
}

export async function listQueries(): Promise<QueryRequest[]> {
  await jittered();
  return useQueriesStore.getState().queries;
}

export async function getQuery(id: string): Promise<QueryRequest | null> {
  await jittered(80, 200);
  return useQueriesStore.getState().queries.find((q) => q.id === id) ?? null;
}

export async function submitQuery(input: SubmitQueryInput): Promise<QueryRequest> {
  await jittered(300, 600);
  const user = useAuthStore.getState().user;
  if (!user) throw new Error('not authenticated');
  const ds = DATASOURCES.find((d) => d.id === input.datasource_id)!;
  const id = `q-${Math.floor(Math.random() * 9000 + 1000)}-${Math.random().toString(36).slice(2, 5)}`;
  const query_type = deriveQueryType(input.sql);
  const created: QueryRequest = {
    id,
    datasource_id: ds.id,
    datasource_name: ds.name,
    db_type: ds.db_type,
    submitted_by: user.id,
    submitter_name: user.display_name,
    submitter_email: user.email,
    sql: input.sql,
    query_type,
    status: 'PENDING_AI',
    risk_level: 'LOW',
    risk_score: 0,
    justification: input.justification,
    created_at: new Date().toISOString(),
    rows_affected: null,
    duration_ms: null,
    ai_summary: '',
    ai_issues: [],
  };
  useQueriesStore.getState().upsert(created);

  // Async analysis arrives ~1.8s later
  setTimeout(() => {
    const a = mockAnalyze(input.sql);
    const requiresHuman =
      (query_type !== 'SELECT' && ds.require_review_writes) ||
      (query_type === 'SELECT' && ds.require_review_reads);
    const next: Partial<QueryRequest> = {
      risk_level: a.risk_level,
      risk_score: a.risk_score,
      ai_summary: a.summary,
      ai_issues: a.issues,
      status: requiresHuman ? 'PENDING_REVIEW' : 'APPROVED',
    };
    useQueriesStore.getState().patch(id, next);
    if (next.status === 'APPROVED') {
      // auto-execute when no human review required
      setTimeout(() => {
        useQueriesStore.getState().patch(id, {
          status: 'EXECUTED',
          rows_affected: Math.floor(Math.random() * 5000) + 1,
          duration_ms: Math.floor(Math.random() * 800) + 50,
        });
      }, 1200);
    }
  }, 1800);

  return created;
}

export async function approveQuery(id: string, _comment?: string): Promise<void> {
  await jittered(150, 350);
  useQueriesStore.getState().patch(id, { status: 'APPROVED' });
  setTimeout(() => {
    useQueriesStore.getState().patch(id, {
      status: 'EXECUTED',
      rows_affected: Math.floor(Math.random() * 5000) + 1,
      duration_ms: Math.floor(Math.random() * 800) + 50,
    });
  }, 1400);
}

export async function rejectQuery(id: string, _comment?: string): Promise<void> {
  await jittered(150, 350);
  useQueriesStore.getState().patch(id, { status: 'REJECTED' });
}

export async function cancelQuery(id: string): Promise<void> {
  await jittered(80, 200);
  useQueriesStore.getState().patch(id, { status: 'CANCELLED' });
}

export async function analyzeOnly(sql: string): Promise<AiAnalysis> {
  await jittered(400, 800);
  return mockAnalyze(sql);
}

export const isPending = (s: QueryStatus): boolean =>
  s === 'PENDING_AI' || s === 'PENDING_REVIEW';
