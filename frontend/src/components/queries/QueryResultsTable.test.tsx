import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';
import i18n from '@/i18n';
import { QueryResultsTable } from './QueryResultsTable';
import type { ExportDecision, QueryResultsPage } from '@/types/api';

const { getQueryResultsMock, fetchExportDecisionMock, downloadResultExportMock, downloadBlobMock } =
  vi.hoisted(() => ({
    getQueryResultsMock: vi.fn(),
    fetchExportDecisionMock: vi.fn(),
    downloadResultExportMock: vi.fn(),
    downloadBlobMock: vi.fn(),
  }));

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>();
  return { ...actual, getQueryResults: getQueryResultsMock };
});

vi.mock('@/api/resultExport', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/resultExport')>();
  return {
    ...actual,
    fetchExportDecision: fetchExportDecisionMock,
    downloadResultExport: downloadResultExportMock,
  };
});

vi.mock('@/utils/downloadBlob', () => ({ downloadBlob: downloadBlobMock }));

function renderTable() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <AntApp>
          <QueryResultsTable queryId="q-1" />
        </AntApp>
      </I18nextProvider>
    </QueryClientProvider>,
  );
}

function page(overrides: Partial<QueryResultsPage>): QueryResultsPage {
  return {
    columns: [{ name: 'id', type: 'uuid' }],
    rows: [['1']],
    row_count: 1,
    truncated: false,
    page: 0,
    size: 50,
    ...overrides,
  };
}

function decision(overrides: Partial<ExportDecision>): ExportDecision {
  return {
    allowed: true,
    effective_mode: 'ALLOW',
    row_cap: null,
    watermark: false,
    policy_ids: [],
    classifications_present: [],
    ...overrides,
  };
}

describe('QueryResultsTable', () => {
  beforeEach(() => {
    getQueryResultsMock.mockReset();
    fetchExportDecisionMock.mockReset();
    downloadResultExportMock.mockReset();
    downloadBlobMock.mockReset();
    // Default: decision endpoint unavailable — the export button simply hides.
    fetchExportDecisionMock.mockRejectedValue(new Error('nope'));
  });

  it('renders rows without a truncation footer when not truncated', async () => {
    getQueryResultsMock.mockResolvedValue(page({}));
    renderTable();
    expect((await screen.findAllByText('id')).length).toBeGreaterThan(0);
    expect(screen.queryByText(/truncated/i)).not.toBeInTheDocument();
  });

  it('renders the row-limit footer when truncated without a reason', async () => {
    getQueryResultsMock.mockResolvedValue(page({ truncated: true }));
    renderTable();
    expect(
      await screen.findByText(/truncated by datasource max_rows/i),
    ).toBeInTheDocument();
  });

  it('renders the byte-limit footer when truncated_reason is BYTE_LIMIT', async () => {
    getQueryResultsMock.mockResolvedValue(
      page({ truncated: true, truncated_reason: 'BYTE_LIMIT' }),
    );
    renderTable();
    expect(
      await screen.findByText(/truncated by the result size limit/i),
    ).toBeInTheDocument();
  });

  it('hides the export button while the decision is unavailable', async () => {
    getQueryResultsMock.mockResolvedValue(page({}));
    renderTable();
    await screen.findAllByText('id');
    expect(screen.queryByRole('button', { name: /export/i })).not.toBeInTheDocument();
  });

  it('shows an enabled export button when the decision allows', async () => {
    getQueryResultsMock.mockResolvedValue(page({}));
    fetchExportDecisionMock.mockResolvedValue(decision({}));
    renderTable();
    const button = await screen.findByRole('button', { name: /export/i });
    expect(button).toBeEnabled();
  });

  it('disables the export button when denied and names the classifications', async () => {
    getQueryResultsMock.mockResolvedValue(page({}));
    fetchExportDecisionMock.mockResolvedValue(
      decision({
        allowed: false,
        effective_mode: 'DENY_CLASSIFIED',
        classifications_present: ['PCI', 'PHI'],
      }),
    );
    renderTable();
    // The denied reason rides on the button's accessible name (aria-label + tooltip pair),
    // per the repo convention of asserting labels rather than AntD tooltip popups.
    const button = await screen.findByRole('button', { name: /PCI, PHI/ });
    expect(button).toBeDisabled();
  });

  it('downloads the CSV export through downloadBlob on menu click', async () => {
    getQueryResultsMock.mockResolvedValue(page({}));
    fetchExportDecisionMock.mockResolvedValue(decision({}));
    downloadResultExportMock.mockResolvedValue({
      blob: new Blob(['id\r\n1\r\n']),
      filename: 'query-results-abc-20260818T093000Z.csv',
      signature: 'sig',
      signatureAlgorithm: 'SHA256withRSA',
      contentSha256: 'sha',
      truncated: false,
    });
    renderTable();
    fireEvent.click(await screen.findByRole('button', { name: /export/i }));
    fireEvent.click(await screen.findByText(/export csv/i));
    await waitFor(() => expect(downloadResultExportMock).toHaveBeenCalled());
    expect(downloadResultExportMock.mock.calls[0]?.[0]).toBe('q-1');
    expect(downloadResultExportMock.mock.calls[0]?.[1]).toBe('CSV');
    await waitFor(() =>
      expect(downloadBlobMock.mock.calls[0]?.[0]).toMatchObject({
        filename: 'query-results-abc-20260818T093000Z.csv',
      }),
    );
  });

  it('downloads PDF via the menu and warns when the export was truncated', async () => {
    getQueryResultsMock.mockResolvedValue(page({}));
    fetchExportDecisionMock.mockResolvedValue(decision({ effective_mode: 'ROW_CAP', row_cap: 1 }));
    downloadResultExportMock.mockResolvedValue({
      blob: new Blob(['id\r\n1\r\n']),
      filename: 'query-results-abc-20260818T093000Z.pdf',
      signature: 'sig',
      signatureAlgorithm: 'SHA256withRSA',
      contentSha256: 'sha',
      truncated: true,
    });
    renderTable();
    fireEvent.click(await screen.findByRole('button', { name: /export/i }));
    fireEvent.click(await screen.findByText(/export pdf/i));
    await waitFor(() =>
      expect(downloadResultExportMock.mock.calls[0]?.[1]).toBe('PDF'),
    );
    expect(
      await screen.findByText(/truncated at the configured row cap/i),
    ).toBeInTheDocument();
  });

  it('surfaces an error toast when the export fails', async () => {
    getQueryResultsMock.mockResolvedValue(page({}));
    fetchExportDecisionMock.mockResolvedValue(decision({}));
    downloadResultExportMock.mockRejectedValue(new Error('boom'));
    renderTable();
    fireEvent.click(await screen.findByRole('button', { name: /export/i }));
    fireEvent.click(await screen.findByText(/export csv/i));
    // apiErrorMessage surfaces the error's own message for non-axios errors.
    expect(await screen.findByText(/boom/)).toBeInTheDocument();
    expect(downloadBlobMock).not.toHaveBeenCalled();
  });
});
