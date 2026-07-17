import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import i18n from '@/i18n';
import { QueryResultsTable } from './QueryResultsTable';
import type { QueryResultsPage } from '@/types/api';

const { getQueryResultsMock } = vi.hoisted(() => ({ getQueryResultsMock: vi.fn() }));

vi.mock('@/api/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/queries')>();
  return { ...actual, getQueryResults: getQueryResultsMock };
});

function renderTable() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <QueryResultsTable queryId="q-1" />
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

describe('QueryResultsTable', () => {
  beforeEach(() => getQueryResultsMock.mockReset());

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
});
