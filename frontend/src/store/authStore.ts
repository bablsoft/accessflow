import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User } from '@/types/api';
import { USERS } from '@/mocks/data';

interface AuthState {
  userId: string | null;
  edition: 'COMMUNITY' | 'ENTERPRISE';
  setUserId: (id: string) => void;
  setEdition: (e: 'COMMUNITY' | 'ENTERPRISE') => void;
  login: (email: string) => Promise<void>;
  logout: () => void;
  user: () => User | null;
  isAuthenticated: () => boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      userId: null,
      edition: 'ENTERPRISE',
      setUserId: (id) => set({ userId: id }),
      setEdition: (edition) => set({ edition }),
      login: async (email) => {
        await new Promise((r) => setTimeout(r, 600));
        const match = USERS.find((u) => u.email.toLowerCase() === email.toLowerCase());
        // In demo mode any email works — fall back to alice if no match
        const u = match ?? USERS[0]!;
        set({ userId: u.id });
      },
      logout: () => set({ userId: null }),
      user: () => {
        const id = get().userId;
        return id ? USERS.find((u) => u.id === id) ?? null : null;
      },
      isAuthenticated: () => get().userId !== null,
    }),
    { name: 'af-auth' },
  ),
);
