import { create } from 'zustand';
import * as authApi from '@/api/auth';
import type { AuthUser, LoginPayload } from '@/api/auth';
import { usePreferencesStore } from '@/store/preferencesStore';

interface AuthState {
  user: AuthUser | null;
  accessToken: string | null;
  setSession: (payload: LoginPayload) => void;
  clear: () => void;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  isAuthenticated: () => boolean;
}

function applyPreferredLanguage(user: AuthUser) {
  if (user.preferred_language) {
    usePreferencesStore.getState().setLanguage(user.preferred_language);
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessToken: null,
  setSession: (payload) => {
    applyPreferredLanguage(payload.user);
    set({ user: payload.user, accessToken: payload.access_token });
  },
  clear: () => set({ user: null, accessToken: null }),
  login: async (email, password) => {
    const payload = await authApi.login(email, password);
    applyPreferredLanguage(payload.user);
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
