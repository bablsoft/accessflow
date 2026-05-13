import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';

const listApiKeys = vi.fn();
const createApiKey = vi.fn();
const revokeApiKey = vi.fn();

vi.mock('@/api/apiKeys', () => ({
  listApiKeys: (...args: unknown[]) => listApiKeys(...args),
  createApiKey: (...args: unknown[]) => createApiKey(...args),
  revokeApiKey: (...args: unknown[]) => revokeApiKey(...args),
  apiKeysKeys: { list: ['api-keys'] as const },
}));

const { ApiKeysSection } = await import('../ApiKeysSection');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

const baseKey = {
  id: 'k-1',
  name: 'ci',
  key_prefix: 'af_abcdefghij',
  created_at: '2026-05-01T12:00:00Z',
  last_used_at: null,
  expires_at: null,
  revoked_at: null,
};

describe('ApiKeysSection', () => {
  beforeEach(() => {
    listApiKeys.mockReset();
    createApiKey.mockReset();
    revokeApiKey.mockReset();
  });

  it('renders an empty state when there are no keys', async () => {
    listApiKeys.mockResolvedValueOnce([]);
    render(wrap(<ApiKeysSection />));
    expect(await screen.findByText("You haven't created any API keys yet.")).toBeInTheDocument();
  });

  it('shows existing keys with their prefix and status', async () => {
    listApiKeys.mockResolvedValueOnce([baseKey]);
    render(wrap(<ApiKeysSection />));
    expect(await screen.findByText('ci')).toBeInTheDocument();
    expect(screen.getByText(/af_abcdefghij/)).toBeInTheDocument();
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('creates a new key and surfaces the raw value once', async () => {
    listApiKeys.mockResolvedValue([]);
    createApiKey.mockResolvedValueOnce({
      api_key: { ...baseKey, name: 'demo' },
      raw_key: 'af_secret-once-only',
    });
    render(wrap(<ApiKeysSection />));

    fireEvent.click(await screen.findByRole('button', { name: 'Create API key' }));
    const nameInput = await screen.findByLabelText('Key name');
    fireEvent.change(nameInput, { target: { value: 'demo' } });
    // The modal's primary action is the second button labeled "Create API key" (the modal OK).
    const createButtons = screen.getAllByRole('button', { name: 'Create API key' });
    fireEvent.click(createButtons[createButtons.length - 1]!);

    await waitFor(() => expect(createApiKey).toHaveBeenCalledWith({ name: 'demo' }));
    expect(await screen.findByText('af_secret-once-only')).toBeInTheDocument();
    expect(screen.getByText(/only time the key is shown/i)).toBeInTheDocument();
  });

  it('revokes a key when the user confirms', async () => {
    listApiKeys.mockResolvedValue([baseKey]);
    revokeApiKey.mockResolvedValueOnce(undefined);
    render(wrap(<ApiKeysSection />));

    fireEvent.click(await screen.findByRole('button', { name: 'Revoke API key ci' }));
    // Popconfirm renders an "OK" / revoke confirmation button — click it.
    const confirmButtons = await screen.findAllByRole('button', { name: 'Revoke' });
    // The last button in the popconfirm is the OK; the first one is the action trigger.
    fireEvent.click(confirmButtons[confirmButtons.length - 1]!);

    await waitFor(() => expect(revokeApiKey).toHaveBeenCalledWith('k-1'));
  });
});
