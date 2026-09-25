import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@/i18n';
import type { DataBudget } from '@/types/api';

const listDataBudgets = vi.fn();
const createDataBudget = vi.fn();
const updateDataBudget = vi.fn();
const deleteDataBudget = vi.fn();

vi.mock('@/api/dataBudgets', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/dataBudgets')>();
  return {
    dataBudgetKeys: original.dataBudgetKeys,
    listDataBudgets: (...args: unknown[]) => listDataBudgets(...args),
    createDataBudget: (...args: unknown[]) => createDataBudget(...args),
    updateDataBudget: (...args: unknown[]) => updateDataBudget(...args),
    deleteDataBudget: (...args: unknown[]) => deleteDataBudget(...args),
  };
});

vi.mock('@/api/admin', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/admin')>();
  return {
    ...original,
    listUsers: () =>
      Promise.resolve({ content: [], page: 0, size: 100, total_elements: 0, total_pages: 0 }),
  };
});

vi.mock('@/api/groups', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/groups')>();
  return { ...original, listAllGroups: () => Promise.resolve([]) };
});

vi.mock('@/api/roles', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/roles')>();
  return { ...original, listRoles: () => Promise.resolve([]) };
});

const { DataBudgetTab } = await import('./DataBudgetTab');

const budget: DataBudget = {
  id: 'b-1',
  datasource_id: 'ds-1',
  name: 'Analysts daily',
  max_rows: 100000,
  max_bytes: 5_000_000_000,
  window_minutes: 1440,
  breach_action: 'REQUIRE_REVIEW',
  warn_threshold_percent: 80,
  applies_to_roles: ['ANALYST'],
  applies_to_group_ids: [],
  applies_to_user_ids: ['u-1', 'u-2'],
  enabled: true,
  created_at: '2026-09-01T10:00:00Z',
  updated_at: '2026-09-01T10:00:00Z',
};

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <DataBudgetTab dsId="ds-1" />
      </AntdApp>
    </QueryClientProvider>,
  );
}

async function openModal(trigger: HTMLElement) {
  fireEvent.click(trigger);
  return waitFor(() => {
    const el = document.querySelector('.ant-modal') as HTMLElement;
    expect(el).toBeTruthy();
    return el;
  });
}

describe('DataBudgetTab', () => {
  beforeEach(() => {
    listDataBudgets.mockReset().mockResolvedValue([budget]);
    createDataBudget.mockReset().mockResolvedValue(budget);
    updateDataBudget.mockReset().mockResolvedValue(budget);
    deleteDataBudget.mockReset().mockResolvedValue(undefined);
  });

  it('renders the empty state when there are no budgets', async () => {
    listDataBudgets.mockResolvedValue([]);
    renderTab();

    expect(await screen.findByText('No data budgets')).toBeInTheDocument();
  });

  it('lists budgets with limits, window, action and scope', async () => {
    listDataBudgets.mockResolvedValue([
      budget,
      { ...budget, id: 'b-2', name: 'Weekly', max_bytes: null, window_minutes: 10080,
        breach_action: 'REJECT', applies_to_roles: [], applies_to_user_ids: [], enabled: false },
    ]);
    renderTab();

    expect(await screen.findByText('Analysts daily')).toBeInTheDocument();
    expect(screen.getByText('1 day')).toBeInTheDocument();
    expect(screen.getByText('7 days')).toBeInTheDocument();
    expect(screen.getByText('Send to human review')).toBeInTheDocument();
    expect(screen.getByText('Reject')).toBeInTheDocument();
    expect(screen.getByText('1 role · 2 users')).toBeInTheDocument();
    expect(screen.getByText('Everyone')).toBeInTheDocument();
  });

  it('creates a row budget with the default window, action and threshold', async () => {
    listDataBudgets.mockResolvedValue([]);
    renderTab();
    const modal = await openModal(await screen.findByText('Add budget'));

    fireEvent.change(within(modal).getByLabelText('Name'), { target: { value: 'Daily cap' } });
    fireEvent.change(within(modal).getByLabelText('Row limit'), { target: { value: '5000' } });
    fireEvent.click(within(modal).getByText('Save'));

    await waitFor(() => expect(createDataBudget).toHaveBeenCalled());
    expect(createDataBudget.mock.calls[0]?.[0]).toBe('ds-1');
    expect(createDataBudget.mock.calls[0]?.[1]).toEqual({
      name: 'Daily cap',
      max_rows: 5000,
      max_bytes: null,
      window_minutes: 1440,
      breach_action: 'REQUIRE_REVIEW',
      warn_threshold_percent: 80,
      applies_to_roles: [],
      applies_to_group_ids: [],
      applies_to_user_ids: [],
      enabled: true,
    });
  });

  it('requires a name and at least one limit', async () => {
    listDataBudgets.mockResolvedValue([]);
    renderTab();
    const modal = await openModal(await screen.findByText('Add budget'));

    fireEvent.click(within(modal).getByText('Save'));

    expect(await within(modal).findByText('Enter a name')).toBeInTheDocument();
    expect(
      (await within(modal).findAllByText('Set a row limit or a result-size limit')).length,
    ).toBeGreaterThan(0);
    expect(createDataBudget).not.toHaveBeenCalled();
  });

  it('rejects a window outside one hour to 31 days', async () => {
    listDataBudgets.mockResolvedValue([]);
    renderTab();
    const modal = await openModal(await screen.findByText('Add budget'));

    fireEvent.change(within(modal).getByLabelText('Name'), { target: { value: 'Too long' } });
    fireEvent.change(within(modal).getByLabelText('Row limit'), { target: { value: '10' } });
    fireEvent.change(within(modal).getByLabelText('Rolling window'), { target: { value: '40' } });
    fireEvent.click(within(modal).getByText('Save'));

    expect(
      await within(modal).findByText('The window must be between 1 hour and 31 days'),
    ).toBeInTheDocument();
    expect(createDataBudget).not.toHaveBeenCalled();
  });

  it('edits an existing budget through update, keeping its window', async () => {
    renderTab();
    const modal = await openModal(await screen.findByLabelText('Edit budget'));
    await waitFor(() =>
      expect(within(modal).getByLabelText('Name')).toHaveValue('Analysts daily'),
    );

    fireEvent.change(within(modal).getByLabelText('Name'), { target: { value: 'Renamed' } });
    fireEvent.click(within(modal).getByText('Save'));

    await waitFor(() => expect(updateDataBudget).toHaveBeenCalled());
    expect(updateDataBudget.mock.calls[0]?.[1]).toBe('b-1');
    expect(updateDataBudget.mock.calls[0]?.[2]).toMatchObject({
      name: 'Renamed',
      max_rows: 100000,
      max_bytes: 5_000_000_000,
      window_minutes: 1440,
      applies_to_roles: ['ANALYST'],
      applies_to_user_ids: ['u-1', 'u-2'],
    });
  });

  it('deletes a budget after confirmation', async () => {
    renderTab();
    fireEvent.click(await screen.findByLabelText('Delete budget'));
    const [confirm] = await screen.findAllByText('Delete this data budget?');
    const confirmDialog = confirm?.closest('.ant-modal') as HTMLElement;
    fireEvent.click(within(confirmDialog).getByRole('button', { name: 'Delete budget' }));

    await waitFor(() => expect(deleteDataBudget).toHaveBeenCalled());
    expect(deleteDataBudget.mock.calls[0]).toEqual(['ds-1', 'b-1']);
  });

  it('surfaces a save failure', async () => {
    listDataBudgets.mockResolvedValue([]);
    createDataBudget.mockRejectedValue(new Error('Budget rejected'));
    renderTab();
    const modal = await openModal(await screen.findByText('Add budget'));
    fireEvent.change(within(modal).getByLabelText('Name'), { target: { value: 'X' } });
    fireEvent.change(within(modal).getByLabelText('Row limit'), { target: { value: '1' } });
    fireEvent.click(within(modal).getByText('Save'));

    expect(await screen.findByText('Budget rejected')).toBeInTheDocument();
  });
});
