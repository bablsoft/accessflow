import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';

const updateProfile = vi.fn();
vi.mock('@/api/me', () => ({
  updateProfile: (...args: unknown[]) => updateProfile(...args),
  meKeys: { current: ['me'] as const },
}));

const { DisplayNameForm } = await import('../DisplayNameForm');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

const profile = {
  id: 'u-1',
  email: 'alice@example.com',
  display_name: 'Alice',
  role: 'ANALYST' as const,
  auth_provider: 'LOCAL' as const,
  totp_enabled: false,
  preferred_language: null,
};

describe('DisplayNameForm', () => {
  beforeEach(() => {
    updateProfile.mockReset();
  });

  it('submits the new display name', async () => {
    updateProfile.mockResolvedValueOnce({ ...profile, display_name: 'Alice Updated' });
    render(wrap(<DisplayNameForm profile={profile} />));

    const input = screen.getByLabelText('Display name') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'Alice Updated' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith({ display_name: 'Alice Updated' }),
    );
  });

  it('shows a validation error when the field is empty', async () => {
    render(wrap(<DisplayNameForm profile={profile} />));

    const input = screen.getByLabelText('Display name') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Display name is required.')).toBeInTheDocument();
    expect(updateProfile).not.toHaveBeenCalled();
  });
});
