import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@/i18n';
import type { DataBudgetStatus, User } from '@/types/api';

const getUserDataBudgetUsage = vi.fn();

vi.mock('@/api/dataBudgets', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/dataBudgets')>();
  return {
    dataBudgetKeys: original.dataBudgetKeys,
    getUserDataBudgetUsage: (...args: unknown[]) => getUserDataBudgetUsage(...args),
  };
});

const { UserDataBudgetDrawer } = await import('./UserDataBudgetDrawer');

const user = {
  id: 'u-1',
  email: 'ana@example.com',
  display_name: 'Ana Analyst',
  active: true,
} as User;

const status: DataBudgetStatus = {
  datasource_id: 'ds-1',
  datasource_name: 'Warehouse',
  exhausted: true,
  breach_action: 'REJECT',
  budgets: [
    {
      id: 'b-1',
      name: 'Daily rows',
      max_rows: 1000,
      max_bytes: 2_000_000_000,
      window_minutes: 1440,
      breach_action: 'REJECT',
      used_rows: 1000,
      used_bytes: 500_000_000,
      remaining_rows: 0,
      remaining_bytes: 1_500_000_000,
      used_percent: 100,
      exhausted: true,
    },
  ],
};

function renderDrawer(u: User | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <UserDataBudgetDrawer user={u} onClose={() => undefined} />
    </QueryClientProvider>,
  );
}

describe('UserDataBudgetDrawer', () => {
  beforeEach(() => {
    getUserDataBudgetUsage.mockReset();
  });

  it('does not load anything while closed', () => {
    renderDrawer(null);
    expect(getUserDataBudgetUsage).not.toHaveBeenCalled();
  });

  it('shows usage per datasource and budget', async () => {
    getUserDataBudgetUsage.mockResolvedValue([status]);
    renderDrawer(user);

    expect(await screen.findByText('Warehouse')).toBeInTheDocument();
    expect(screen.getByText('Data usage — Ana Analyst')).toBeInTheDocument();
    expect(screen.getByText('Used up')).toBeInTheDocument();
    expect(screen.getByText('Daily rows')).toBeInTheDocument();
    expect(screen.getByText(/1,000 of 1,000 rows/)).toBeInTheDocument();
    expect(getUserDataBudgetUsage).toHaveBeenCalledWith('u-1');
  });

  it('says when no budget applies', async () => {
    getUserDataBudgetUsage.mockResolvedValue([]);
    renderDrawer(user);

    expect(await screen.findByText('No data budget applies to this user.')).toBeInTheDocument();
  });

  it('reports a load failure', async () => {
    getUserDataBudgetUsage.mockRejectedValue(new Error('boom'));
    renderDrawer(user);

    await waitFor(() =>
      expect(screen.getByText('Could not load this user’s data usage')).toBeInTheDocument(),
    );
  });
});
