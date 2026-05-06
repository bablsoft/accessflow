import { create } from 'zustand';
import * as authApi from '@/api/auth';
import type { AuthUser, LoginPayload } from '@/api/auth';

interface AuthState {
  user: AuthUser | null;
  accessToken: string | null;
  setSession: (payload: LoginPayload) => void;
  clear: () => void;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  isAuthenticated: () => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessToken: null,
  setSession: (payload) => set({ user: payload.user, accessToken: payload.access_token }),
  clear: () => set({ user: null, accessToken: null }),
  login: async (email, password) => {
    const payload = await authApi.login(email, password);
    set({ user: payload.user, accessToken: payload.access_token });
  },
  logout: async () => {
    try {
      await authApi.logout();
    } catch {
      // Best-effort: even if the network call fails, drop local state.
    }
    set({ user: null, accessToken: null });
  },
  isAuthenticated: () => get().user !== null,
}));
