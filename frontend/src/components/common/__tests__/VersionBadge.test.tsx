import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { UpdateStatus } from '@/types/api';
import { APP_VERSION } from '@/config/version';
import { CHANGELOG_URL } from '@/config/docs';
import '@/i18n';

const { fetchUpdateStatusMock } = vi.hoisted(() => ({
  fetchUpdateStatusMock: vi.fn(),
}));

vi.mock('@/api/updates', () => ({
  fetchUpdateStatus: fetchUpdateStatusMock,
  updateKeys: {
    all: ['updates'],
    status: () => ['updates', 'status'],
  },
}));

const { VersionBadge } = await import('../VersionBadge');

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{node}</QueryClientProvider>;
}

const unknown: UpdateStatus = {
  current_version: '1.0.0-SNAPSHOT',
  latest_version: null,
  update_available: false,
  changelog_url: null,
  checked_at: null,
  status: 'UNKNOWN',
};

const behind: UpdateStatus = {
  current_version: '2.4.0',
  latest_version: '2.5.0',
  update_available: true,
  changelog_url: 'https://accessflow.io/changelog/#v2-5-0',
  checked_at: '2026-09-20T08:00:00Z',
  status: 'UPDATE_AVAILABLE',
};

describe('VersionBadge', () => {
  beforeEach(() => {
    fetchUpdateStatusMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders the plain version while the check is unknown', async () => {
    fetchUpdateStatusMock.mockResolvedValue(unknown);
    render(wrap(<VersionBadge />));

    await waitFor(() => expect(fetchUpdateStatusMock).toHaveBeenCalledTimes(1));
    expect(screen.getByText(`v${APP_VERSION}`)).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByLabelText(`AccessFlow version ${APP_VERSION}`)).toBeInTheDocument();
  });

  it('stays on the plain version when the check fails (fail-soft)', async () => {
    fetchUpdateStatusMock.mockRejectedValue(new Error('network down'));
    render(wrap(<VersionBadge />));

    await waitFor(() => expect(fetchUpdateStatusMock).toHaveBeenCalledTimes(1));
    expect(screen.getByText(`v${APP_VERSION}`)).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('becomes a changelog link in a new tab when a newer release exists', async () => {
    fetchUpdateStatusMock.mockResolvedValue(behind);
    render(wrap(<VersionBadge />));

    const link = await screen.findByRole('link', { name: 'v2.5.0 is available — view the changelog' });
    expect(link).toHaveAttribute('href', 'https://accessflow.io/changelog/#v2-5-0');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link).toHaveTextContent(`v${APP_VERSION}`);
  });

  it('stays plain when update_available is set without a version to announce', async () => {
    fetchUpdateStatusMock.mockResolvedValue({ ...behind, latest_version: null });
    render(wrap(<VersionBadge />));

    await waitFor(() => expect(fetchUpdateStatusMock).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText(`v${APP_VERSION}`)).toBeInTheDocument();
  });

  it('falls back to the public changelog when the manifest carries no link', async () => {
    fetchUpdateStatusMock.mockResolvedValue({ ...behind, changelog_url: null });
    render(wrap(<VersionBadge />));

    const link = await screen.findByRole('link');
    expect(link).toHaveAttribute('href', CHANGELOG_URL);
  });
});
