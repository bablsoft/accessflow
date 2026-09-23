import { useQuery } from '@tanstack/react-query';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { useAuthStore } from '@/store/authStore';
import { hasAnyPermission } from '@/utils/permissions';
import type { Datasource } from '@/types/api';

const DATASOURCE_FILTERS = { page: 0, size: 100 };

/** Whether `GET /datasources` returns every datasource in the organization for this caller. */
export function useCanListAllDatasources(): boolean {
  return hasAnyPermission(useAuthStore((s) => s.user), ['QUERY_ADMIN', 'DATASOURCE_MANAGE']);
}

/** The datasources the caller can see, shared by the picker and the SQL editor's dialect. */
export function useVisibleDatasources(): { rows: Datasource[]; loading: boolean } {
  const query = useQuery({
    queryKey: datasourceKeys.list(DATASOURCE_FILTERS),
    queryFn: () => listDatasources(DATASOURCE_FILTERS),
  });
  return { rows: query.data?.content ?? [], loading: query.isLoading };
}
