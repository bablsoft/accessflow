import { create } from 'zustand';
import type { QueryRequest } from '@/types/api';
import { QUERIES } from '@/mocks/data';

interface QueriesState {
  queries: QueryRequest[];
  reset: () => void;
  upsert: (q: QueryRequest) => void;
  patch: (id: string, patch: Partial<QueryRequest>) => void;
}

export const useQueriesStore = create<QueriesState>((set) => ({
  queries: [...QUERIES],
  reset: () => set({ queries: [...QUERIES] }),
  upsert: (q) =>
    set((s) => {
      const idx = s.queries.findIndex((x) => x.id === q.id);
      if (idx === -1) return { queries: [q, ...s.queries] };
      const next = [...s.queries];
      next[idx] = q;
      return { queries: next };
    }),
  patch: (id, patch) =>
    set((s) => ({
      queries: s.queries.map((q) => (q.id === id ? { ...q, ...patch } : q)),
    })),
}));
