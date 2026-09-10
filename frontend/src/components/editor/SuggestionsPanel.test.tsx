import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { QuerySuggestion } from '@/types/api';

const { fetchQuerySuggestions } = vi.hoisted(() => ({ fetchQuerySuggestions: vi.fn() }));

vi.mock('@/api/querySuggestions', () => ({
  fetchQuerySuggestions,
  querySuggestionKeys: {
    all: ['query-suggestions'],
    list: (datasourceId: string, limit?: number) => [
      'query-suggestions',
      'list',
      datasourceId,
      limit ?? null,
    ],
  },
}));

const { SuggestionsPanel } = await import('./SuggestionsPanel');

const suggestion = (over: Partial<QuerySuggestion> = {}): QuerySuggestion => ({
  id: 's-1',
  sql: 'SELECT id FROM orders',
  query_type: 'SELECT',
  referenced_tables: ['public.orders'],
  approved_count: 14,
  distinct_submitter_count: 3,
  first_submitted_at: '2026-06-01T08:00:00Z',
  last_submitted_at: '2026-09-09T08:00:00Z',
  ...over,
});

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('SuggestionsPanel', () => {
  beforeEach(() => {
    fetchQuerySuggestions.mockReset();
  });

  it('renders each suggestion with its approval evidence', async () => {
    fetchQuerySuggestions.mockResolvedValue([suggestion()]);

    render(<SuggestionsPanel datasourceId="ds-1" onApply={vi.fn()} />, { wrapper });

    expect(await screen.findByText('SELECT id FROM orders')).toBeInTheDocument();
    expect(screen.getAllByText('public.orders').length).toBeGreaterThan(0);
    expect(screen.getByText(/Approved 14× · submitters: 3/)).toBeInTheDocument();
  });

  it('says drafts are still reviewed, so the rail never reads as pre-approval', async () => {
    fetchQuerySuggestions.mockResolvedValue([suggestion()]);

    render(<SuggestionsPanel datasourceId="ds-1" onApply={vi.fn()} />, { wrapper });

    expect(
      await screen.findByText(/still analysed and reviewed like any other query/i),
    ).toBeInTheDocument();
  });

  it('hands the raw sql to onApply', async () => {
    fetchQuerySuggestions.mockResolvedValue([suggestion()]);
    const onApply = vi.fn();

    render(<SuggestionsPanel datasourceId="ds-1" onApply={onApply} />, { wrapper });
    fireEvent.click(await screen.findByRole('button', { name: /apply as draft/i }));

    expect(onApply).toHaveBeenCalledWith('SELECT id FROM orders');
  });

  it('shows the cold-start empty state rather than an error', async () => {
    fetchQuerySuggestions.mockResolvedValue([]);

    render(<SuggestionsPanel datasourceId="ds-1" onApply={vi.fn()} />, { wrapper });

    expect(await screen.findByText(/no suggestions yet/i)).toBeInTheDocument();
  });

  it("surfaces the server's own reason rather than a generic failure string", async () => {
    // A 404 ("not accessible") and a 500 must not read identically to the analyst.
    fetchQuerySuggestions.mockRejectedValue(
      Object.assign(new Error('Request failed'), {
        isAxiosError: true,
        response: { status: 404, data: { detail: 'Datasource not found' } },
      }),
    );

    render(<SuggestionsPanel datasourceId="ds-1" onApply={vi.fn()} />, { wrapper });

    await waitFor(() =>
      expect(screen.getByText('Datasource not found')).toBeInTheDocument(),
    );
  });

  it('falls back to the localized string when the failure carries no message', async () => {
    fetchQuerySuggestions.mockRejectedValue({});

    render(<SuggestionsPanel datasourceId="ds-1" onApply={vi.fn()} />, { wrapper });

    await waitFor(() =>
      expect(screen.getByText(/could not load suggestions/i)).toBeInTheDocument(),
    );
  });

  it('fetches per datasource', async () => {
    fetchQuerySuggestions.mockResolvedValue([]);

    render(<SuggestionsPanel datasourceId="ds-42" onApply={vi.fn()} />, { wrapper });

    await waitFor(() => expect(fetchQuerySuggestions).toHaveBeenCalledWith('ds-42'));
  });
});
