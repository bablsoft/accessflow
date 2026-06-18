import { apiClient } from './client';
import type { CreateCommentInput, QueryComment, QueryCommentThread } from '@/types/api';

const base = (queryId: string) => `/api/v1/queries/${queryId}/comments`;

export const commentKeys = {
  all: (queryId: string) => ['queries', 'detail', queryId, 'comments'] as const,
};

export async function listComments(queryId: string): Promise<QueryCommentThread[]> {
  const { data } = await apiClient.get<QueryCommentThread[]>(base(queryId));
  return data;
}

export async function createComment(
  queryId: string,
  input: CreateCommentInput,
): Promise<QueryComment> {
  const { data } = await apiClient.post<QueryComment>(base(queryId), input);
  return data;
}

export async function replyToComment(
  queryId: string,
  commentId: string,
  body: string,
): Promise<QueryComment> {
  const { data } = await apiClient.post<QueryComment>(
    `${base(queryId)}/${commentId}/replies`,
    { body },
  );
  return data;
}

export async function resolveComment(
  queryId: string,
  commentId: string,
): Promise<QueryComment> {
  const { data } = await apiClient.post<QueryComment>(`${base(queryId)}/${commentId}/resolve`);
  return data;
}

export async function reopenComment(
  queryId: string,
  commentId: string,
): Promise<QueryComment> {
  const { data } = await apiClient.post<QueryComment>(`${base(queryId)}/${commentId}/reopen`);
  return data;
}
