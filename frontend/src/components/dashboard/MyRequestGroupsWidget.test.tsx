import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { RequestGroup, RequestGroupPage } from '@/types/api';

const { listGroups } = vi.hoisted(() => ({ listGroups: vi.fn() }));

vi.mock('@/api/requestGroups', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/requestGroups')>('@/api/requestGroups');
  return { ...actual, listRequestGroups: listGroups };
});

const { MyRequestGroupsWidget } = await import('./MyRequestGroupsWidget');

function group(overrides: Partial<RequestGroup> = {}): RequestGroup {
  return {
    id: 'rg-1',
    organization_id: 'org-1',
    submitted_by_user_id: 'u-1',
    submitted_by_display_name: 'Me',
    name: 'Nightly backfill',
    description: null,
    status: 'DRAFT',
    continue_on_error: false,
    scheduled_for: null,
    ai_risk_level: null,
    ai_risk_score: null,
    required_approvals: null,
    current_review_stage: null,
    error_message: null,
    execution_started_at: null,
    execution_completed_at: null,
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    items: [],
    ...overrides,
  };
}

function page(content: RequestGroup[]): RequestGroupPage {
  return { content, page: 0, size: 5, total_elements: content.length, total_pages: 1, last: true };
}

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<MyRequestGroupsWidget />, { wrapper });
}

describe('MyRequestGroupsWidget', () => {
  beforeEach(() => {
    listGroups.mockResolvedValue(page([group()]));
  });

  it('renders group rows with a status pill, step count and detail link', async () => {
    renderWidget();
    expect(await screen.findByText('Nightly backfill')).toBeInTheDocument();
    expect(screen.getByText(/0 steps/i)).toBeInTheDocument();
    const view = screen.getByRole('link', { name: /^view$/i });
    expect(view).toHaveAttribute('href', '/request-groups/rg-1');
    const viewAll = screen.getByRole('link', { name: /view all/i });
    expect(viewAll).toHaveAttribute('href', '/request-groups');
  });

  it('shows the compact empty state when there are no groups', async () => {
    listGroups.mockResolvedValue(page([]));
    renderWidget();
    expect(await screen.findByText(/no request groups yet/i)).toBeInTheDocument();
  });
});
