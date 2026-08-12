import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { OverProvisionedGrant, OverProvisionedGrantPage } from '@/types/api';

const { listMock, exportMock, downloadBlobMock } = vi.hoisted(() => ({
  listMock: vi.fn(),
  exportMock: vi.fn(),
  downloadBlobMock: vi.fn(),
}));

vi.mock('@/api/grantUsage', async () => {
  const actual = await vi.importActual<typeof import('@/api/grantUsage')>('@/api/grantUsage');
  return {
    ...actual,
    listOverProvisionedGrants: listMock,
    exportOverProvisionedCsv: exportMock,
  };
});

vi.mock('@/utils/downloadBlob', () => ({ downloadBlob: downloadBlobMock }));

const { default: OverProvisionedAccessPage } = await import('./OverProvisionedAccessPage');

function grant(overrides: Partial<OverProvisionedGrant> = {}): OverProvisionedGrant {
  return {
    id: 'g-1',
    resource_kind: 'DATASOURCE',
    resource_id: 'ds-1',
    resource_name: 'analytics-prod',
    permission_id: 'perm-1',
    user_id: 'u-1',
    user_email: 'dev@example.com',
    user_display_name: 'Dev Example',
    granted_at: '2025-11-02T09:14:00Z',
    expires_at: null,
    granted_target_count: 12,
    used_targets: ['public.orders'],
    used_target_count: 2,
    unused_target_count: 10,
    usage_count: 37,
    first_used_at: '2026-01-08T11:02:00Z',
    last_used_at: '2026-05-30T16:41:00Z',
    observed_since: '2026-03-03T00:00:00Z',
    days_since_last_use: 2,
    usage_per_week: 2.9,
    recommendation: 'OVER_SCOPED',
    ...overrides,
  };
}

function pageOf(content: OverProvisionedGrant[]): OverProvisionedGrantPage {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: content.length === 0 ? 0 : 1,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('OverProvisionedAccessPage', () => {
  beforeEach(() => {
    listMock.mockReset();
    exportMock.mockReset();
    downloadBlobMock.mockReset();
  });

  it('renders a grant with its scope ratio and recommendation', async () => {
    listMock.mockResolvedValue(pageOf([grant()]));

    render(wrap(<OverProvisionedAccessPage />));

    expect(await screen.findByText('dev@example.com')).toBeInTheDocument();
    expect(screen.getByText('analytics-prod')).toBeInTheDocument();
    expect(screen.getByText('Over-scoped')).toBeInTheDocument();
    expect(screen.getByText('2 of 12')).toBeInTheDocument();
    expect(screen.getByText('2 days ago')).toBeInTheDocument();
  });

  /**
   * A never-used grant is the headline finding. It must not render as "0 days ago", which reads as
   * "used today" — the opposite conclusion.
   */
  it('renders a never-used grant as never used, not as zero days', async () => {
    listMock.mockResolvedValue(
      pageOf([
        grant({
          recommendation: 'NEVER_USED',
          last_used_at: null,
          days_since_last_use: null,
          usage_count: 0,
          used_target_count: 0,
          used_targets: [],
        }),
      ]),
    );

    render(wrap(<OverProvisionedAccessPage />));

    // Two cells legitimately read "Never used" on this row: the last-used column and the
    // recommendation pill.
    expect(await screen.findAllByText('Never used')).toHaveLength(2);
    expect(screen.queryByText('0 days ago')).not.toBeInTheDocument();
  });

  /** A null granted count means unrestricted, which is not "0 of 0". */
  it('renders an unrestricted grant as unrestricted rather than a zero ratio', async () => {
    listMock.mockResolvedValue(
      pageOf([grant({ granted_target_count: null, unused_target_count: null })]),
    );

    render(wrap(<OverProvisionedAccessPage />));

    expect(await screen.findByText('Unrestricted')).toBeInTheDocument();
    expect(screen.queryByText(/of 0/)).not.toBeInTheDocument();
  });

  it('shows an empty state when nothing matches', async () => {
    listMock.mockResolvedValue(pageOf([]));

    render(wrap(<OverProvisionedAccessPage />));

    expect(await screen.findByText('No standing grants')).toBeInTheDocument();
  });

  it('surfaces a load failure instead of an empty table', async () => {
    listMock.mockRejectedValue(new Error('boom'));

    render(wrap(<OverProvisionedAccessPage />));

    expect(
      await screen.findByText('Could not load the over-provisioned access report'),
    ).toBeInTheDocument();
  });

  it('exports the current filters as CSV and hands the blob to the downloader', async () => {
    listMock.mockResolvedValue(pageOf([grant()]));
    exportMock.mockResolvedValue({
      blob: new Blob(['a']),
      filename: 'over-provisioned-access.csv',
      truncated: false,
    });

    render(wrap(<OverProvisionedAccessPage />));
    (await screen.findByTestId('export-csv-button')).click();

    await waitFor(() => expect(exportMock).toHaveBeenCalled());
    expect(exportMock.mock.calls[0]?.[0]).toMatchObject({ page: 0, size: 20 });
    await waitFor(() => expect(downloadBlobMock).toHaveBeenCalled());
  });

  it('warns when the export was truncated', async () => {
    listMock.mockResolvedValue(pageOf([grant()]));
    exportMock.mockResolvedValue({
      blob: new Blob(['a']),
      filename: 'over-provisioned-access.csv',
      truncated: true,
    });

    render(wrap(<OverProvisionedAccessPage />));
    (await screen.findByTestId('export-csv-button')).click();

    expect(
      await screen.findByText('The export hit the row cap and is truncated.'),
    ).toBeInTheDocument();
  });

  /** Only a measured, genuinely-unused grant earns the critical "Never used"; too-new does not. */
  it('renders a too-new grant as not-yet-observed rather than never used', async () => {
    listMock.mockResolvedValue(
      pageOf([
        grant({
          recommendation: 'INSUFFICIENT_DATA',
          last_used_at: null,
          days_since_last_use: null,
          usage_count: 0,
        }),
      ]),
    );

    render(wrap(<OverProvisionedAccessPage />));

    expect(await screen.findByText('Not enough history yet')).toBeInTheDocument();
    expect(screen.queryByText('Never used')).not.toBeInTheDocument();
  });
});
