import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@/i18n';
import type { Datasource } from '@/types/api';

const { listDatasourcesMock, listReviewPlansMock } = vi.hoisted(() => ({
  listDatasourcesMock: vi.fn(),
  listReviewPlansMock: vi.fn(),
}));

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, listDatasources: listDatasourcesMock };
});
vi.mock('@/api/reviewPlans', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviewPlans')>('@/api/reviewPlans');
  return { ...actual, listReviewPlans: listReviewPlansMock };
});
vi.mock('@/components/datasources/DatasourceIcon', () => ({
  DatasourceIcon: () => <span data-testid="ds-icon" />,
}));

const { DatasourceListPage } = await import('../DatasourceListPage');

const ds = (over: Partial<Datasource>): Datasource =>
  ({
    id: 'ds-1',
    name: 'orders-prod',
    db_type: 'POSTGRESQL',
    host: 'db.internal',
    port: 5432,
    database_name: 'orders',
    ssl_mode: 'REQUIRE',
    ai_analysis_enabled: true,
    review_plan_id: 'plan-1',
    active: true,
    ...over,
  }) as Datasource;

const page = (content: Datasource[]) => ({
  content,
  page: 0,
  size: 100,
  total_elements: content.length,
  total_pages: 1,
  last: true,
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <DatasourceListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DatasourceListPage', () => {
  beforeEach(() => {
    listDatasourcesMock.mockReset();
    listReviewPlansMock.mockReset();
    listReviewPlansMock.mockResolvedValue([{ id: 'plan-1', name: 'Two approvers' }]);
  });

  it('shows skeleton cards while loading', () => {
    listDatasourcesMock.mockReturnValue(new Promise(() => undefined));
    const { container } = renderPage();
    expect(container.querySelector('[aria-busy="true"]')).toBeInTheDocument();
  });

  it('renders each datasource as a card whose name links to its settings', async () => {
    listDatasourcesMock.mockResolvedValue(
      page([ds({}), ds({ id: 'ds-2', name: 'legacy', active: false, review_plan_id: null, ai_analysis_enabled: false })]),
    );
    renderPage();
    const name = await screen.findByText('orders-prod');
    expect(name.closest('a')).toHaveAttribute('href', '/datasources/ds-1/settings');
    expect(screen.getByText('active')).toBeInTheDocument();
    expect(screen.getByText('inactive')).toBeInTheDocument();
    const plan = await screen.findByText('Two approvers');
    expect(plan.closest('a')).toHaveAttribute('href', '/admin/review-plans?planId=plan-1');
    expect(screen.getByText('2 of 2')).toBeInTheDocument();
  });

  it('falls back to the plan id prefix until the plan name is known', async () => {
    listReviewPlansMock.mockResolvedValue([]);
    listDatasourcesMock.mockResolvedValue(page([ds({ review_plan_id: 'abcdef12-3456' })]));
    renderPage();
    expect(await screen.findByText('abcdef12')).toBeInTheDocument();
  });

  it('filters by name and shows the empty state when nothing matches', async () => {
    listDatasourcesMock.mockResolvedValue(page([ds({}), ds({ id: 'ds-2', name: 'legacy' })]));
    renderPage();
    await screen.findByText('orders-prod');
    const search = screen.getByPlaceholderText('Search datasources…');
    fireEvent.change(search, { target: { value: 'LEG' } });
    expect(screen.queryByText('orders-prod')).toBeNull();
    expect(screen.getByText('legacy')).toBeInTheDocument();
    fireEvent.change(search, { target: { value: 'zzz' } });
    expect(screen.getByText('No datasources yet — add one to get started.')).toBeInTheDocument();
  });

  it('offers a retry when the list fails to load', async () => {
    listDatasourcesMock.mockRejectedValueOnce(new Error('down'));
    renderPage();
    expect(await screen.findByText('Failed to load datasources')).toBeInTheDocument();
    listDatasourcesMock.mockResolvedValue(page([ds({})]));
    fireEvent.click(screen.getByText('Retry'));
    await waitFor(() => expect(screen.getByText('orders-prod')).toBeInTheDocument());
  });
});
