import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ComplianceReport } from '@/types/api';

const { fetchComplianceReportMock, exportComplianceReportMock } = vi.hoisted(() => ({
  fetchComplianceReportMock: vi.fn(),
  exportComplianceReportMock: vi.fn(),
}));

vi.mock('@/api/compliance', async () => {
  const actual = await vi.importActual<typeof import('@/api/compliance')>('@/api/compliance');
  return {
    ...actual,
    fetchComplianceReport: fetchComplianceReportMock,
    exportComplianceReport: exportComplianceReportMock,
  };
});

const AuditorDashboardPage = (await import('./AuditorDashboardPage')).default;

function classifiedReport(): ComplianceReport {
  return {
    type: 'CLASSIFIED_ACCESS',
    organization_id: 'org-1',
    period_from: '2026-01-01T00:00:00Z',
    period_to: '2026-04-01T00:00:00Z',
    generated_at: '2026-04-02T00:00:00Z',
    datasource_id: null,
    classified_access: [
      {
        query_request_id: 'q-1',
        datasource_id: 'ds-1',
        datasource_name: 'ProdDb',
        submitted_by: 'u-1',
        submitter_email: 'alice@example.com',
        query_type: 'SELECT',
        referenced_tables: ['public.customers'],
        matched: [{ table_name: 'customers', column_name: 'ssn', classification: 'PII' }],
        rows_affected: 5,
        executed_at: '2026-02-01T10:00:00Z',
      },
    ],
    audit_trail: [],
    deployment_metrics: [],
    row_count: 1,
    truncated: false,
  };
}

function deploymentReport(): ComplianceReport {
  return {
    ...classifiedReport(),
    type: 'DEPLOYMENT_METRICS',
    classified_access: [],
    deployment_metrics: [
      {
        pipeline_id: 'p-1',
        pipeline_name: 'payments-api',
        environment_id: 'e-1',
        environment_name: 'production',
        deployments: 48,
        lead_time_p50_seconds: 5400,
        lead_time_p95_seconds: 90_000,
        lead_time_source: 'MIXED',
        change_failure_rate: 0.125,
        failed: 4,
        rolled_back: 2,
        unreported: 3,
        open_incidents: 1,
        approval_latency_p50_seconds: 900,
        approval_latency_p95_seconds: 14_400,
        timeout_rate: 0.02,
        timed_out: 1,
        break_glass_count: 7,
      },
    ],
    row_count: 1,
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

describe('AuditorDashboardPage', () => {
  beforeEach(() => {
    fetchComplianceReportMock.mockReset();
    exportComplianceReportMock.mockReset();
  });

  it('renders the classified-access report rows', async () => {
    fetchComplianceReportMock.mockResolvedValue(classifiedReport());

    render(wrap(<AuditorDashboardPage />));

    await waitFor(() => expect(screen.getByText('alice@example.com')).toBeInTheDocument());
    expect(screen.getByText('ProdDb')).toBeInTheDocument();
    expect(screen.getByText(/customers\.ssn: PII/)).toBeInTheDocument();
  });

  it('shows an empty state when the report has no rows', async () => {
    fetchComplianceReportMock.mockResolvedValue({ ...classifiedReport(), classified_access: [], row_count: 0 });

    render(wrap(<AuditorDashboardPage />));

    await waitFor(() =>
      expect(screen.getByText('No matching activity')).toBeInTheDocument(),
    );
  });

  it('shows the effective executed SQL on the regulatory audit trail', async () => {
    const trailRow = {
      query_request_id: 'q-2',
      datasource_id: 'ds-1',
      datasource_name: 'ProdDb',
      submitted_by: 'u-1',
      submitter_email: 'bob@example.com',
      query_type: 'DELETE' as const,
      sql_text: 'DELETE FROM orders',
      approvers: [],
      executed_at: '2026-02-01T10:00:00Z',
    };
    fetchComplianceReportMock.mockImplementation(async (type: string) =>
      type === 'REGULATORY_AUDIT_TRAIL'
        ? {
            ...classifiedReport(),
            type: 'REGULATORY_AUDIT_TRAIL',
            classified_access: [],
            audit_trail: [
              { ...trailRow, effective_sql: 'DELETE FROM orders WHERE orders.region = ?' },
              { ...trailRow, query_request_id: 'q-3', sql_text: 'DELETE FROM carts' },
            ],
            row_count: 2,
          }
        : classifiedReport(),
    );

    render(wrap(<AuditorDashboardPage />));
    await waitFor(() => expect(screen.getByText('alice@example.com')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Regulatory audit trail'));

    await waitFor(() =>
      expect(screen.getByText('DELETE FROM orders WHERE orders.region = ?')).toBeInTheDocument(),
    );
    expect(screen.getByText('Effective SQL')).toBeInTheDocument();
    expect(screen.getByText('DELETE FROM carts')).toBeInTheDocument();
    // The row without a rewrite renders the placeholder, never an empty code cell.
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('exports a signed PDF on button click', async () => {
    fetchComplianceReportMock.mockResolvedValue(classifiedReport());
    exportComplianceReportMock.mockResolvedValue({
      blob: new Blob(['%PDF']),
      filename: 'compliance-classified-access.pdf',
      signature: 'sig',
      signatureAlgorithm: 'SHA256withRSA',
      contentSha256: 'abc',
      truncated: false,
    });
    const createUrl = vi.fn(() => 'blob:x');
    const revokeUrl = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { value: createUrl, writable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeUrl, writable: true });

    render(wrap(<AuditorDashboardPage />));
    await waitFor(() => expect(screen.getByText('alice@example.com')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Export signed PDF/i }));

    await waitFor(() =>
      expect(exportComplianceReportMock).toHaveBeenCalledWith(
        'CLASSIFIED_ACCESS',
        'PDF',
        expect.objectContaining({ from: expect.any(String), to: expect.any(String) }),
      ),
    );
    expect(createUrl).toHaveBeenCalled();
  });

  it('renders the deployment-metrics tab with DORA columns', async () => {
    fetchComplianceReportMock.mockImplementation(async (type: string) =>
      type === 'DEPLOYMENT_METRICS' ? deploymentReport() : classifiedReport(),
    );

    render(wrap(<AuditorDashboardPage />));
    await waitFor(() => expect(screen.getByText('alice@example.com')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Deployment metrics (DORA)'));

    await waitFor(() => expect(screen.getByText('payments-api')).toBeInTheDocument());
    expect(fetchComplianceReportMock).toHaveBeenCalledWith('DEPLOYMENT_METRICS', expect.anything());
    expect(screen.getByText('production')).toBeInTheDocument();
    expect(screen.getAllByText('Lead time p50 / p95').length).toBeGreaterThan(0);
    expect(screen.getByText('1h 30m / 1d 1h')).toBeInTheDocument();
    expect(screen.getByText('12.5% (4 failed, 2 rolled back)')).toBeInTheDocument();
    expect(screen.getByText('15m / 4h')).toBeInTheDocument();
    expect(screen.getByText('2%')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    // No restore sample: the percentile cell renders the placeholder, never "0".
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('shows the deployment empty state when no pipeline exists', async () => {
    fetchComplianceReportMock.mockImplementation(async (type: string) =>
      type === 'DEPLOYMENT_METRICS'
        ? { ...deploymentReport(), deployment_metrics: [], row_count: 0 }
        : classifiedReport(),
    );

    render(wrap(<AuditorDashboardPage />));
    await waitFor(() => expect(screen.getByText('alice@example.com')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Deployment metrics (DORA)'));

    await waitFor(() =>
      expect(
        screen.getByText('No deployment environments exist in this organization yet.'),
      ).toBeInTheDocument(),
    );
  });

  it('renders placeholders for a pipeline with no deployments', async () => {
    const empty = deploymentReport();
    const row = empty.deployment_metrics[0]!;
    empty.deployment_metrics = [
      {
        pipeline_id: row.pipeline_id,
        pipeline_name: row.pipeline_name,
        environment_id: row.environment_id,
        environment_name: row.environment_name,
        deployments: 0,
        failed: 0,
        rolled_back: 0,
        unreported: 0,
        open_incidents: 0,
        timed_out: 0,
        break_glass_count: 0,
      },
    ];
    fetchComplianceReportMock.mockImplementation(async (type: string) =>
      type === 'DEPLOYMENT_METRICS' ? empty : classifiedReport(),
    );

    render(wrap(<AuditorDashboardPage />));
    await waitFor(() => expect(screen.getByText('alice@example.com')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Deployment metrics (DORA)'));

    await waitFor(() => expect(screen.getByText('payments-api')).toBeInTheDocument());
    expect(screen.getByText('— (0 failed, 0 rolled back)')).toBeInTheDocument();
    expect(screen.getAllByText('—')).toHaveLength(4);
  });
});
