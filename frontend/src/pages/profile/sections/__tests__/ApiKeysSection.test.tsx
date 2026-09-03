import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import dayjs from 'dayjs';
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

  it('labels a key past its expiry as Expired rather than Active', async () => {
    listApiKeys.mockResolvedValueOnce([
      { ...baseKey, id: 'k-2', name: 'stale', expires_at: '2026-05-02T12:00:00Z' },
    ]);
    render(wrap(<ApiKeysSection />));
    expect(await screen.findByText('stale')).toBeInTheDocument();
    expect(screen.getByText('Expired')).toBeInTheDocument();
    expect(screen.queryByText('Active')).not.toBeInTheDocument();
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

  it('sends the chosen expiry when one is picked', async () => {
    listApiKeys.mockResolvedValue([]);
    createApiKey.mockResolvedValueOnce({
      api_key: { ...baseKey, name: 'expiring' },
      raw_key: 'af_expiring-once-only',
    });
    render(wrap(<ApiKeysSection />));

    fireEvent.click(await screen.findByRole('button', { name: 'Create API key' }));
    fireEvent.change(await screen.findByLabelText('Key name'), { target: { value: 'expiring' } });

    // AntD's DatePicker accepts typed input; commit it with Enter.
    const expiry = dayjs().add(7, 'day').hour(9).minute(30).second(0).millisecond(0);
    const picker = screen.getByPlaceholderText('Never expires');
    fireEvent.change(picker, { target: { value: expiry.format('YYYY-MM-DD HH:mm:ss') } });
    fireEvent.keyDown(picker, { key: 'Enter', code: 'Enter' });

    const createButtons = screen.getAllByRole('button', { name: 'Create API key' });
    fireEvent.click(createButtons[createButtons.length - 1]!);

    await waitFor(() =>
      expect(createApiKey).toHaveBeenCalledWith({
        name: 'expiring',
        expires_at: expiry.toISOString(),
      }),
    );
  });

  it('disables past dates and past hours in the expiry picker', async () => {
    // Frozen clock: mid-month so the previous day is guaranteed to be rendered in
    // the panel, and mid-morning so the disabled-hour count is non-zero.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(2026, 5, 15, 10, 30, 0));
    try {
      listApiKeys.mockResolvedValue([]);
      render(wrap(<ApiKeysSection />));

      fireEvent.click(await screen.findByRole('button', { name: 'Create API key' }));
      const picker = screen.getByPlaceholderText('Never expires');
      fireEvent.mouseDown(picker);
      fireEvent.click(picker);
      fireEvent.focus(picker);

      expect(await screen.findByTitle('2026-06-14')).toHaveClass('ant-picker-cell-disabled');
      expect(screen.getByTitle('2026-06-15')).not.toHaveClass('ant-picker-cell-disabled');

      // The time column defaults to today, so exactly the ten hours already past
      // are disabled.
      const hourColumn = document.querySelector(
        '.ant-picker-time-panel-column[data-type="hour"]',
      );
      expect(hourColumn).not.toBeNull();
      expect(hourColumn!.querySelectorAll('.ant-picker-time-panel-cell-disabled')).toHaveLength(
        10,
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('commits an expiry chosen from the panel rather than typed', async () => {
    listApiKeys.mockResolvedValue([]);
    createApiKey.mockResolvedValueOnce({
      api_key: { ...baseKey, name: 'panel-picked' },
      raw_key: 'af_panel-picked',
    });
    render(wrap(<ApiKeysSection />));

    fireEvent.click(await screen.findByRole('button', { name: 'Create API key' }));
    fireEvent.change(await screen.findByLabelText('Key name'), {
      target: { value: 'panel-picked' },
    });

    const picker = screen.getByPlaceholderText('Never expires');
    fireEvent.mouseDown(picker);
    fireEvent.click(picker);
    fireEvent.focus(picker);

    const target = dayjs().add(5, 'day').startOf('day');
    const cell = await screen.findByTitle(target.format('YYYY-MM-DD'));
    fireEvent.click(cell.querySelector('.ant-picker-cell-inner')!);

    // Closing the popup is what a click on the modal's OK does first. Without
    // needConfirm={false} the panel selection is dropped right here and the key
    // is minted permanent even though the input still shows the date.
    fireEvent.mouseDown(document.body);
    fireEvent.click(document.body);
    // One event-loop turn: in a browser the mousedown that dismisses the popup
    // and the click that submits are separate events, so the close-commit has
    // already landed by the time onFinish reads the form.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    const createButtons = screen.getAllByRole('button', { name: 'Create API key' });
    fireEvent.click(createButtons[createButtons.length - 1]!);

    await waitFor(() =>
      expect(createApiKey).toHaveBeenCalledWith({
        name: 'panel-picked',
        expires_at: target.toISOString(),
      }),
    );
  });

  it('refuses a past instant typed straight into the expiry field', async () => {
    listApiKeys.mockResolvedValue([]);
    createApiKey.mockResolvedValueOnce({
      api_key: { ...baseKey, name: 'typed-past' },
      raw_key: 'af_typed-past',
    });
    render(wrap(<ApiKeysSection />));

    fireEvent.click(await screen.findByRole('button', { name: 'Create API key' }));
    fireEvent.change(await screen.findByLabelText('Key name'), { target: { value: 'typed-past' } });

    const past = dayjs().subtract(10, 'day').hour(9).minute(0).second(0);
    const picker = screen.getByPlaceholderText('Never expires');
    fireEvent.change(picker, { target: { value: past.format('YYYY-MM-DD HH:mm:ss') } });
    fireEvent.keyDown(picker, { key: 'Enter', code: 'Enter' });

    const createButtons = screen.getAllByRole('button', { name: 'Create API key' });
    fireEvent.click(createButtons[createButtons.length - 1]!);

    // disabledDate rejects the typed value, so the field stays empty and the
    // key is minted non-expiring rather than with an already-past expiry.
    await waitFor(() => expect(createApiKey).toHaveBeenCalledWith({ name: 'typed-past' }));
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
