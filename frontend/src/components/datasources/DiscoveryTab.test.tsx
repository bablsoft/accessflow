import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@/i18n';
import type { DiscoveryFinding, DiscoveryScanConfig } from '@/types/api';

const fetchDiscoveryConfig = vi.fn();
const updateDiscoveryConfig = vi.fn();
const fetchDiscoveryFindings = vi.fn();
const bulkDecideFindings = vi.fn();
const triggerDiscoveryScan = vi.fn();

vi.mock('@/api/discovery', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/discovery')>();
  return {
    discoveryKeys: original.discoveryKeys,
    fetchDiscoveryConfig: (...args: unknown[]) => fetchDiscoveryConfig(...args),
    updateDiscoveryConfig: (...args: unknown[]) => updateDiscoveryConfig(...args),
    fetchDiscoveryFindings: (...args: unknown[]) => fetchDiscoveryFindings(...args),
    bulkDecideFindings: (...args: unknown[]) => bulkDecideFindings(...args),
    triggerDiscoveryScan: (...args: unknown[]) => triggerDiscoveryScan(...args),
  };
});

const { DiscoveryTab } = await import('./DiscoveryTab');

const config: DiscoveryScanConfig = {
  datasource_id: 'ds-1',
  enabled: false,
  sample_size: 100,
  scan_interval_hours: 24,
  ai_classification_enabled: false,
  last_scan_at: '2026-07-27T02:00:00Z',
  last_scan_error: null,
};

const finding: DiscoveryFinding = {
  id: 'f-1',
  schema_name: 'public',
  table_name: 'users',
  column_name: 'email',
  classification: 'PII',
  detector: 'EMAIL',
  confidence: 96,
  sample_redacted: '*************.com',
  rationale: null,
  match_count: 48,
  sample_count: 50,
  status: 'PENDING',
  first_detected_at: '2026-07-20T02:00:00Z',
  last_detected_at: '2026-07-27T02:00:00Z',
  decided_by: null,
  decided_at: null,
};

function page(content: DiscoveryFinding[]) {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: 1,
  };
}

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <DiscoveryTab dsId="ds-1" />
      </AntdApp>
    </QueryClientProvider>,
  );
}

describe('DiscoveryTab', () => {
  beforeEach(() => {
    fetchDiscoveryConfig.mockReset().mockResolvedValue(config);
    fetchDiscoveryFindings.mockReset().mockResolvedValue(page([finding]));
    updateDiscoveryConfig.mockReset();
    bulkDecideFindings.mockReset();
    triggerDiscoveryScan.mockReset().mockResolvedValue(undefined);
  });

  it('renders the settings card with config values and the findings table', async () => {
    renderTab();

    expect(await screen.findByText('Discovery settings')).toBeInTheDocument();
    expect(await screen.findByText('public.users.email')).toBeInTheDocument();
    expect(screen.getByText('PII')).toBeInTheDocument();
    expect(screen.getByText('*************.com')).toBeInTheDocument();
    expect(screen.getByText(/96%/)).toBeInTheDocument();
    expect(fetchDiscoveryFindings).toHaveBeenCalledWith('ds-1', {
      status: 'PENDING',
      page: 0,
      size: 20,
    });
  });

  it('saves the settings form', async () => {
    updateDiscoveryConfig.mockResolvedValue({ ...config, enabled: true });
    renderTab();

    const saveButton = await screen.findByRole('button', { name: /Save/ });
    fireEvent.click(screen.getAllByRole('switch')[0]!);
    fireEvent.click(saveButton);

    await waitFor(() => expect(updateDiscoveryConfig).toHaveBeenCalled());
    expect(updateDiscoveryConfig.mock.calls[0]![0]).toBe('ds-1');
    expect(await screen.findByText('Discovery settings saved')).toBeInTheDocument();
  });

  it('triggers an on-demand scan', async () => {
    renderTab();

    fireEvent.click(await screen.findByRole('button', { name: /Scan now/ }));

    await waitFor(() => expect(triggerDiscoveryScan).toHaveBeenCalledWith('ds-1'));
    expect(await screen.findByText('Discovery scan started')).toBeInTheDocument();
  });

  it('bulk-confirms selected findings and reports the summary', async () => {
    bulkDecideFindings.mockResolvedValue({
      results: [{ finding_id: 'f-1', status: 'SUCCESS', new_status: 'CONFIRMED' }],
    });
    renderTab();
    await screen.findByText('public.users.email');

    // Select the row via its checkbox, then confirm from the bulk bar.
    const checkboxes = screen.getAllByRole('checkbox');
    fireEvent.click(checkboxes[checkboxes.length - 1]!);
    fireEvent.click(await screen.findByRole('button', { name: /Confirm selected/ }));

    await waitFor(() => expect(bulkDecideFindings).toHaveBeenCalled());
    expect(bulkDecideFindings.mock.calls[0]![0]).toBe('ds-1');
    expect(bulkDecideFindings.mock.calls[0]![1]).toEqual({
      finding_ids: ['f-1'],
      decision: 'CONFIRM',
    });
    expect(await screen.findByText('1 finding decided')).toBeInTheDocument();
  });

  it('keeps failed rows selected and shows the partial toast on mixed outcomes', async () => {
    const second: DiscoveryFinding = { ...finding, id: 'f-2', column_name: 'phone' };
    fetchDiscoveryFindings.mockResolvedValue(page([finding, second]));
    bulkDecideFindings.mockResolvedValue({
      results: [
        { finding_id: 'f-1', status: 'SUCCESS', new_status: 'CONFIRMED' },
        { finding_id: 'f-2', status: 'INVALID_STATE', new_status: 'DISMISSED' },
      ],
    });
    renderTab();
    await screen.findByText('public.users.email');

    // "Select all" header checkbox.
    fireEvent.click(screen.getAllByRole('checkbox')[0]!);
    fireEvent.click(await screen.findByRole('button', { name: /Confirm selected/ }));

    await waitFor(() => expect(bulkDecideFindings).toHaveBeenCalled());
    expect(await screen.findByText(/1 decided · 1 failed/)).toBeInTheDocument();
    expect(await screen.findByText('Already decided')).toBeInTheDocument();
  });

  it('shows the last-scan error alert when present', async () => {
    fetchDiscoveryConfig.mockResolvedValue({ ...config, last_scan_error: 'partial: budget' });
    renderTab();

    expect(await screen.findByText(/partial: budget/)).toBeInTheDocument();
  });

  it('shows the empty state when there are no findings', async () => {
    fetchDiscoveryFindings.mockResolvedValue(page([]));
    renderTab();

    expect(await screen.findByText('No findings')).toBeInTheDocument();
  });
});
