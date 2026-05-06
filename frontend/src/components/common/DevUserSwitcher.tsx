import { Select } from 'antd';
import { useAuthStore } from '@/store/authStore';
import { USERS } from '@/mocks/data';
import type { AuthUser } from '@/api/auth';

// Dev-only override: lets the local UI swap to a different mock user without
// re-authenticating against the backend. The real session (accessToken, cookie)
// is unchanged — backend calls still execute as the originally signed-in user.
// This file is excluded from production bundles via the DEV-only import in
// Topbar.tsx, so the USERS mock list never reaches prod.
export function DevUserSwitcher() {
  const user = useAuthStore((s) => s.user);
  const setSession = useAuthStore((s) => s.setSession);
  const accessToken = useAuthStore((s) => s.accessToken);

  const setUser = (id: string) => {
    const match = USERS.find((u) => u.id === id);
    if (!match) return;
    const mock: AuthUser = {
      id: match.id,
      email: match.email,
      display_name: match.display_name,
      role: match.role,
    };
    setSession({ access_token: accessToken ?? '', expires_in: 0, user: mock });
  };

  return (
    <Select
      size="small"
      value={user?.id}
      style={{ minWidth: 200 }}
      className="hide-mobile"
      onChange={setUser}
      aria-label="Switch user (dev only)"
      options={USERS.filter((u) => u.active)
        .slice(0, 8)
        .map((u) => ({
          value: u.id,
          label: `${u.display_name} · ${u.role}`,
        }))}
    />
  );
}
