import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import { TwoFactorSection } from '../TwoFactorSection';

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

const baseProfile = {
  id: 'u-1',
  email: 'alice@example.com',
  display_name: 'Alice',
  role: 'ANALYST' as const,
  auth_provider: 'LOCAL' as const,
  totp_enabled: false,
  preferred_language: null,
};

describe('TwoFactorSection', () => {
  it('shows the enable button when 2FA is disabled', () => {
    render(wrap(<TwoFactorSection profile={baseProfile} />));
    expect(screen.getByRole('button', { name: 'Enable 2FA' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Disable 2FA' })).toBeNull();
  });

  it('shows the disable button and enabled badge when 2FA is enabled', () => {
    render(wrap(<TwoFactorSection profile={{ ...baseProfile, totp_enabled: true }} />));
    expect(screen.getByRole('button', { name: 'Disable 2FA' })).toBeInTheDocument();
    expect(screen.getByText('Enabled')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enable 2FA' })).toBeNull();
  });
});
