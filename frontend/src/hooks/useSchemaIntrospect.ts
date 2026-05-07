import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { datasourceKeys, getDatasourceSchema } from '@/api/datasources';
import type { DatasourceSchema } from '@/types/api';

export function useSchemaIntrospect(
  datasourceId: string | undefined,
): UseQueryResult<DatasourceSchema, Error> {
  return useQuery({
    queryKey: datasourceId
      ? datasourceKeys.schema(datasourceId)
      : ['datasources', 'schema', 'idle'],
    queryFn: () => getDatasourceSchema(datasourceId!),
    enabled: !!datasourceId,
    staleTime: 5 * 60_000,
    retry: false,
  });
}
