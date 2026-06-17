import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { datasourceKeys, getDatasourceSampleRows } from '@/api/datasources';
import type { SampleRowsResponse } from '@/types/api';

/** Default sample size; the backend further clamps it to the configured proxy row cap. */
export const SAMPLE_LIMIT = 50;

/**
 * Fetches a bounded, RLS- and masking-aware sample of rows for a table (AF-443). Enabled only when
 * a table is selected for preview, so it never fires for the idle state.
 */
export function useTableSample(
  datasourceId: string | undefined,
  table: string | undefined,
  schema?: string,
  limit: number = SAMPLE_LIMIT,
): UseQueryResult<SampleRowsResponse, Error> {
  const enabled = !!datasourceId && !!table;
  return useQuery({
    queryKey: enabled
      ? datasourceKeys.sampleRows(datasourceId, schema, table, limit)
      : ['datasources', 'sample-rows', 'idle'],
    queryFn: () => getDatasourceSampleRows(datasourceId!, { schema, table: table!, limit }),
    enabled,
    staleTime: 60_000,
    retry: false,
  });
}
