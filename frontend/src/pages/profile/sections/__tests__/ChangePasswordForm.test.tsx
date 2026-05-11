import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';

const changePassword = vi.fn();
vi.mock('@/api/me', () => ({
  changePassword: (...args: unknown[]) => changePassword(...args),
}));

const { ChangePasswordForm } = await import('../ChangePasswordForm');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ChangePasswordForm', () => {
  beforeEach(() => {
    changePassword.mockReset();
  });

  it('rejects mismatching new and confirm passwords', async () => {
    render(wrap(<ChangePasswordForm />));

    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'OldPassword1!' },
    });
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'NewPassword1!' },
    });
    fireEvent.change(screen.getByLabelText('Confirm new password'), {
      target: { value: 'Different1!' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Update password' }));

    expect(await screen.findByText('Passwords do not match.')).toBeInTheDocument();
    expect(changePassword).not.toHaveBeenCalled();
  });

  it('submits when current/new/confirm are valid', async () => {
    changePassword.mockResolvedValueOnce(undefined);
    render(wrap(<ChangePasswordForm />));

    fireEvent.change(screen.getByLabelText('Current password'), {
      target: { value: 'OldPassword1!' },
    });
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'NewPassword1!' },
    });
    fireEvent.change(screen.getByLabelText('Confirm new password'), {
      target: { value: 'NewPassword1!' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Update password' }));

    await waitFor(() =>
      expect(changePassword).toHaveBeenCalledWith({
        current_password: 'OldPassword1!',
        new_password: 'NewPassword1!',
      }),
    );
  });
});
