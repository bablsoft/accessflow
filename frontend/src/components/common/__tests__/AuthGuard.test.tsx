import { describe, expect, it, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AuthGuard } from '../AuthGuard';
import { useAuthStore } from '@/store/authStore';
import type { AuthUser } from '@/api/auth';

function setUser(partial: Partial<AuthUser> | null) {
  if (partial === null) {
    useAuthStore.setState({ user: null, accessToken: null });
    return;
  }
  useAuthStore.setState({
    user: {
      id: 'u-1',
      email: 'a@b.c',
      display_name: 'A',
      role: 'ADMIN',
      auth_provider: 'LOCAL',
      totp_enabled: false,
      platform_admin: false,
      preferred_language: null,
      ...partial,
    },
    accessToken: 'token',
  });
}

function renderGuard(requirePlatformAdmin: boolean) {
  return render(
    <MemoryRouter initialEntries={['/secret']}>
      <Routes>
        <Route
          path="/secret"
          element={
            <AuthGuard requirePlatformAdmin={requirePlatformAdmin}>
              <div>secret content</div>
            </AuthGuard>
          }
        />
        <Route path="/editor" element={<div>editor page</div>} />
        <Route path="/login" element={<div>login page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AuthGuard requirePlatformAdmin', () => {
  beforeEach(() => setUser(null));

  it('renders children for a platform admin', () => {
    setUser({ platform_admin: true });
    renderGuard(true);
    expect(screen.getByText('secret content')).toBeInTheDocument();
  });

  it('redirects a non-platform-admin to /editor', () => {
    setUser({ platform_admin: false });
    renderGuard(true);
    expect(screen.getByText('editor page')).toBeInTheDocument();
    expect(screen.queryByText('secret content')).toBeNull();
  });

  it('redirects an unauthenticated user to /login', () => {
    setUser(null);
    renderGuard(true);
    expect(screen.getByText('login page')).toBeInTheDocument();
  });
});
