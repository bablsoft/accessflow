import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { sqlReviewKeys } from '@/api/sqlReview';
import {
  createSqlReviewCustomRule,
  deleteSqlReviewCustomRule,
  listSqlReviewCustomRules,
  sqlReviewRuleKeys,
  testSqlReviewCustomRule,
  updateSqlReviewCustomRule,
} from '@/api/sqlReviewRules';
import type { SqlReviewCustomRuleWriteRequest } from '@/types/api';

export function useSqlReviewCustomRules() {
  return useQuery({ queryKey: sqlReviewRuleKeys.list(), queryFn: listSqlReviewCustomRules });
}

/**
 * Every custom-rule write also changes what the Rulesets tab reads: the rule catalog
 * (`GET /sql-review/rules` lists the enabled custom rules) and the rulesets themselves — a delete
 * removes the rule's configs server-side, and a stale ruleset would re-send them on save (422).
 * Not `sqlReviewKeys.all`: that would also refetch every cached editor-lint evaluation.
 */
function useInvalidateCustomRules() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: sqlReviewRuleKeys.all }),
      queryClient.invalidateQueries({ queryKey: sqlReviewKeys.rules() }),
      queryClient.invalidateQueries({ queryKey: sqlReviewKeys.rulesets() }),
    ]);
}

export function useCreateSqlReviewCustomRule() {
  const invalidate = useInvalidateCustomRules();
  return useMutation({
    mutationFn: (payload: SqlReviewCustomRuleWriteRequest) => createSqlReviewCustomRule(payload),
    onSuccess: () => invalidate(),
  });
}

export function useUpdateSqlReviewCustomRule() {
  const invalidate = useInvalidateCustomRules();
  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: SqlReviewCustomRuleWriteRequest }) =>
      updateSqlReviewCustomRule(id, payload),
    onSuccess: () => invalidate(),
  });
}

export function useDeleteSqlReviewCustomRule() {
  const invalidate = useInvalidateCustomRules();
  return useMutation({
    mutationFn: (id: string) => deleteSqlReviewCustomRule(id),
    onSuccess: () => invalidate(),
  });
}

/** On-demand draft evaluation for the drawer's test panel; nothing to invalidate. */
export function useTestSqlReviewCustomRule() {
  return useMutation({ mutationFn: testSqlReviewCustomRule });
}
