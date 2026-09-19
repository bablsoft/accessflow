import { useQuery } from '@tanstack/react-query';
import { listUsers, userKeys } from '@/api/admin';
import { userSelectOptions, type UserSelectOption } from '@/utils/userOptions';

const HUMAN_PICKER_FILTERS = { size: 100, principal_type: 'HUMAN' as const };

/**
 * Options for the owner / principal pickers (#875): active people only — a service account can
 * neither own another account nor be named on behalf of, and the API refuses both.
 */
export function useHumanUserOptions(enabled: boolean): {
  options: UserSelectOption[];
  loading: boolean;
} {
  const query = useQuery({
    queryKey: userKeys.list(HUMAN_PICKER_FILTERS),
    queryFn: () => listUsers(HUMAN_PICKER_FILTERS),
    enabled,
  });
  const users = (query.data?.content ?? []).filter((user) => user.active);
  return { options: userSelectOptions(users), loading: query.isLoading };
}
