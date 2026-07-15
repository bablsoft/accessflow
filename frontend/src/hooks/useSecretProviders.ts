import { useQuery } from '@tanstack/react-query';
import { datasourceKeys, getSecretProviders } from '@/api/datasources';
import type { SecretProvider } from '@/types/api';

/**
 * Enabled external secret-store providers (AF-448). Deployment-level config that only changes
 * with a backend restart, so a long staleTime avoids refetch churn across the datasource forms.
 */
export function useSecretProviders(): SecretProvider[] {
  const query = useQuery({
    queryKey: datasourceKeys.secretProviders(),
    queryFn: getSecretProviders,
    // Deployment config — effectively static for the session.
    staleTime: 10 * 60_000,
  });
  return query.data?.providers ?? [];
}
