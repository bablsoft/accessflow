import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';

const listApiConnectors = vi.fn();
const createApiConnector = vi.fn();
const listReviewPlans = vi.fn();

vi.mock('@/api/apiConnectors', () => ({
  listApiConnectors: (...args: unknown[]) => listApiConnectors(...args),
  createApiConnector: (...args: unknown[]) => createApiConnector(...args),
  deleteApiConnector: vi.fn(),
  testApiConnector: vi.fn(),
  apiConnectorKeys: {
    all: ['api-connectors'] as const,
    lists: () => ['api-connectors', 'list'] as const,
    list: (filters: unknown) => ['api-connectors', 'list', filters] as const,
    detail: (id: string) => ['api-connectors', 'detail', id] as const,
  },
}));

vi.mock('@/api/admin', () => ({
  listAiConfigs: () => Promise.resolve([]),
  aiConfigKeys: { all: ['aiConfig'] as const, lists: () => ['aiConfig', 'list'] as const },
}));

vi.mock('@/api/reviewPlans', () => ({
  listReviewPlans: (...args: unknown[]) => listReviewPlans(...args),
  reviewPlanKeys: { all: ['reviewPlans'] as const, lists: () => ['reviewPlans', 'list'] as const },
}));

const ApiConnectorsListPage = (await import('./ApiConnectorsListPage')).default;

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ApiConnectorsListPage — create modal review plan (AF-579)', () => {
  beforeEach(() => {
    listApiConnectors.mockReset();
    createApiConnector.mockReset();
    listReviewPlans.mockReset();
    listApiConnectors.mockResolvedValue({
      content: [],
      page: 0,
      size: 100,
      total_elements: 0,
      total_pages: 0,
    });
    listReviewPlans.mockResolvedValue([{ id: 'plan-1', name: 'Two approvals' }]);
  });

  it('offers a review plan selector in the create modal', async () => {
    render(wrap(<ApiConnectorsListPage />));

    fireEvent.click(screen.getByRole('button', { name: /New connector/i }));

    expect(await screen.findByLabelText('Review plan')).toBeInTheDocument();
    expect(
      screen.getByText('Without a plan, requests routed to review need a single approval'),
    ).toBeInTheDocument();
  });

  it('submits the selected review plan in the create payload', async () => {
    createApiConnector.mockResolvedValue({ id: 'conn-1' });

    render(wrap(<ApiConnectorsListPage />));
    fireEvent.click(screen.getByRole('button', { name: /New connector/i }));

    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Petstore' } });
    fireEvent.change(screen.getByLabelText('Base URL'), {
      target: { value: 'https://petstore.example.com' },
    });

    // AI analysis defaults to on, which makes the AI config select required —
    // switch it off so the submit passes validation without an AI config.
    fireEvent.click(screen.getByRole('switch', { name: 'AI analysis' }));

    fireEvent.mouseDown(screen.getByLabelText('Review plan'));
    fireEvent.click(await screen.findByTitle('Two approvals'));

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    // TanStack Query invokes mutationFn with (variables, context) — match both.
    await waitFor(() =>
      expect(createApiConnector).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Petstore',
          base_url: 'https://petstore.example.com',
          review_plan_id: 'plan-1',
        }),
        expect.anything(),
      ),
    );
  });
});
