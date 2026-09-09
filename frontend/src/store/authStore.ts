import { create } from 'zustand';
import * as authApi from '@/api/auth';
import type { AuthUser, LoginPayload } from '@/api/auth';
import { usePreferencesStore } from '@/store/preferencesStore';

interface AuthState {
  user: AuthUser | null;
  accessToken: string | null;
  setSession: (payload: LoginPayload) => void;
  /**
   * Patch the cached user in place (#926). The session payload only refreshes on login or token
   * refresh, so an admin flipping a governance domain would otherwise keep the old navigation
   * until their next refresh. No-ops when nobody is signed in.
   */
  patchUser: (patch: Partial<AuthUser>) => void;
  clear: () => void;
  login: (email: string, password: string, totpCode?: string) => Promise<void>;
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
  patchUser: (patch) => set((s) => (s.user ? { user: { ...s.user, ...patch } } : s)),
  clear: () => set({ user: null, accessToken: null }),
  login: async (email, password, totpCode) => {
    const payload = await authApi.login(email, password, totpCode);
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

// Exposed for Playwright E2E (e2e/) — already reachable via React DevTools, so no extra surface.
if (typeof window !== 'undefined') {
  (window as unknown as { __authStore?: typeof useAuthStore }).__authStore = useAuthStore;
}
