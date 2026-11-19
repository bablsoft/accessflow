import { useCallback } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';

interface Page<T> {
  content: T[];
}

export interface RemovalSnapshot<P> {
  previous: P | undefined;
}

/**
 * Removes one row from a cached queue page as soon as a decision is sent, and puts it back if the
 * server refuses. Returns `remove` for `onMutate` and `restore` for `onError`.
 */
export function useOptimisticRemoval<T, P extends Page<T>>(
  queryKey: QueryKey,
  idOf: (row: T) => string,
) {
  const queryClient = useQueryClient();
  // Compared by value: callers build the key inline, so its identity changes every render.
  const keyHash = JSON.stringify(queryKey);
  const remove = useCallback(
    async (id: string): Promise<RemovalSnapshot<P>> => {
      await queryClient.cancelQueries({ queryKey });
      const previous = queryClient.getQueryData<P>(queryKey);
      if (previous) {
        queryClient.setQueryData<P>(queryKey, {
          ...previous,
          content: previous.content.filter((row) => idOf(row) !== id),
        });
      }
      return { previous };
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [queryClient, keyHash],
  );
  const restore = useCallback(
    (snapshot: RemovalSnapshot<P> | undefined) => {
      if (snapshot?.previous) queryClient.setQueryData(queryKey, snapshot.previous);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [queryClient, keyHash],
  );
  return { remove, restore };
}
