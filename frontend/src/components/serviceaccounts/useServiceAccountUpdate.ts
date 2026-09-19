import { App } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { serviceAccountKeys, updateServiceAccount } from '@/api/serviceAccounts';
import type { ServiceAccount, UpdateServiceAccountInput } from '@/types/api';
import { serviceAccountErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';

/**
 * The one PUT every settings tab shares (#875): writes the fresh detail into the cache and
 * invalidates the list, so the header and the list page pick the change up without a refetch.
 */
export function useServiceAccountUpdate(accountId: string, successMessage: string) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: UpdateServiceAccountInput) => updateServiceAccount(accountId, input),
    onSuccess: (updated: ServiceAccount) => {
      message.success(successMessage);
      queryClient.setQueryData(serviceAccountKeys.detail(accountId), updated);
      void queryClient.invalidateQueries({ queryKey: serviceAccountKeys.lists() });
    },
    onError: (err) => showApiError(message, err, serviceAccountErrorMessage),
  });
}
