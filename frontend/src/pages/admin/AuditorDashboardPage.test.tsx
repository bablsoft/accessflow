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
    row_count: 1,
    truncated: false,
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
});
