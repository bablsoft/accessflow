import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import type { DataBudgetConsumption, DataBudgetStatus } from '@/types/api';

const { getMyDataBudgetStatusMock } = vi.hoisted(() => ({
  getMyDataBudgetStatusMock: vi.fn(),
}));

vi.mock('@/api/dataBudgets', () => ({
  getMyDataBudgetStatus: getMyDataBudgetStatusMock,
  dataBudgetKeys: { mine: (id: string) => ['data-budgets', 'me', id] as const },
}));

import { DataBudgetIndicator } from './DataBudgetIndicator';

function withClient(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>;
}

const budget = (overrides: Partial<DataBudgetConsumption> = {}): DataBudgetConsumption => ({
  id: 'b-1',
  name: 'Analysts daily',
  max_rows: 1000,
  max_bytes: 5_000_000_000,
  window_minutes: 1440,
  breach_action: 'REQUIRE_REVIEW',
  warn_threshold_percent: 80,
  used_rows: 300,
  used_bytes: 1_000_000_000,
  remaining_rows: 700,
  remaining_bytes: 4_000_000_000,
  used_percent: 30,
  exhausted: false,
  ...overrides,
});

const status = (overrides: Partial<DataBudgetStatus> = {}): DataBudgetStatus => ({
  datasource_id: 'ds-1',
  exhausted: false,
  budgets: [budget()],
  ...overrides,
});

describe('DataBudgetIndicator', () => {
  beforeEach(() => {
    getMyDataBudgetStatusMock.mockReset();
  });

  it('renders nothing when no budget applies', async () => {
    getMyDataBudgetStatusMock.mockResolvedValue(status({ budgets: [] }));
    const { container } = render(withClient(<DataBudgetIndicator dsId="ds-1" />));
    await waitFor(() => expect(getMyDataBudgetStatusMock).toHaveBeenCalledWith('ds-1'));
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the standing cannot be read', async () => {
    getMyDataBudgetStatusMock.mockRejectedValue(new Error('404'));
    const { container } = render(withClient(<DataBudgetIndicator dsId="ds-1" />));
    await waitFor(() => expect(getMyDataBudgetStatusMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the remaining rows and bytes for each budget', async () => {
    getMyDataBudgetStatusMock.mockResolvedValue(status());
    render(withClient(<DataBudgetIndicator dsId="ds-1" />));
    expect(await screen.findByText('Analysts daily')).toBeInTheDocument();
    expect(screen.getByTestId('data-budget-indicator')).toHaveTextContent('700');
    expect(screen.getByTestId('data-budget-indicator')).toHaveTextContent('1,000');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('warns once a budget passes its threshold', async () => {
    getMyDataBudgetStatusMock.mockResolvedValue(
      status({ budgets: [budget({ used_percent: 85, max_bytes: null, remaining_bytes: null })] }),
    );
    render(withClient(<DataBudgetIndicator dsId="ds-1" />));
    expect(await screen.findByText('You have used most of your data budget on this datasource.')).toBeInTheDocument();
  });

  it('explains what happens once a review budget is used up', async () => {
    getMyDataBudgetStatusMock.mockResolvedValue(
      status({
        exhausted: true,
        breach_action: 'REQUIRE_REVIEW',
        budgets: [budget({ used_percent: 120, exhausted: true, max_rows: null })],
      }),
    );
    render(withClient(<DataBudgetIndicator dsId="ds-1" />));
    expect(await screen.findByText('Data budget used up')).toBeInTheDocument();
    expect(screen.getByText(/need a reviewer’s approval/)).toBeInTheDocument();
  });

  it('explains a rejecting budget that is used up', async () => {
    getMyDataBudgetStatusMock.mockResolvedValue(
      status({
        exhausted: true,
        breach_action: 'REJECT',
        budgets: [budget({ used_percent: 100, exhausted: true, remaining_rows: null })],
      }),
    );
    render(withClient(<DataBudgetIndicator dsId="ds-1" />));
    expect(await screen.findByText(/are refused until earlier reads/)).toBeInTheDocument();
  });
});
