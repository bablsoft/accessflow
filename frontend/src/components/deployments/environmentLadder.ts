import type { DeploymentEnvironment } from '@/types/api';

/**
 * The server's own default for an omitted `sort_order` (#877): one past the pipeline's current
 * last rung, or 0 for an empty pipeline. Mirrored here so the create form's prefill never proposes
 * a position the unique `(pipeline, sort_order)` constraint would refuse.
 */
export function nextSortOrder(
  environments: ReadonlyArray<Pick<DeploymentEnvironment, 'sort_order'>>,
): number {
  return environments.length === 0 ? 0 : Math.max(...environments.map((e) => e.sort_order)) + 1;
}
