import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@/i18n';
import type { RowLimitPolicy } from '@/types/api';

const listRowLimitPolicies = vi.fn();
const createRowLimitPolicy = vi.fn();
const updateRowLimitPolicy = vi.fn();
const deleteRowLimitPolicy = vi.fn();
const getDatasourceSchema = vi.fn();

vi.mock('@/api/rowLimitPolicies', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/rowLimitPolicies')>();
  return {
    rowLimitPolicyKeys: original.rowLimitPolicyKeys,
    listRowLimitPolicies: (...args: unknown[]) => listRowLimitPolicies(...args),
    createRowLimitPolicy: (...args: unknown[]) => createRowLimitPolicy(...args),
    updateRowLimitPolicy: (...args: unknown[]) => updateRowLimitPolicy(...args),
    deleteRowLimitPolicy: (...args: unknown[]) => deleteRowLimitPolicy(...args),
  };
});

vi.mock('@/api/datasources', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/datasources')>();
  return {
    ...original,
    getDatasourceSchema: (...args: unknown[]) => getDatasourceSchema(...args),
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

const { RowLimitTab } = await import('./RowLimitTab');

const policy: RowLimitPolicy = {
  id: 'rlp-1',
  datasource_id: 'ds-1',
  schema_name: 'crm',
  table_name: 'customer',
  max_rows: 1000,
  applies_to_roles: ['ANALYST', 'AUDITOR'],
  applies_to_group_ids: [],
  applies_to_user_ids: ['u-1'],
  enabled: true,
  created_at: '2026-09-01T10:00:00Z',
  updated_at: '2026-09-01T10:00:00Z',
};

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <RowLimitTab dsId="ds-1" />
      </AntdApp>
    </QueryClientProvider>,
  );
}

function dialog() {
  return document.querySelector('.ant-modal') as HTMLElement;
}

describe('RowLimitTab', () => {
  beforeEach(() => {
    listRowLimitPolicies.mockReset().mockResolvedValue([policy]);
    createRowLimitPolicy.mockReset().mockResolvedValue(policy);
    updateRowLimitPolicy.mockReset().mockResolvedValue(policy);
    deleteRowLimitPolicy.mockReset().mockResolvedValue(undefined);
    getDatasourceSchema.mockReset().mockResolvedValue({
      schemas: [{ name: 'crm', tables: [{ name: 'customer', columns: [] }] }],
    });
  });

  it('renders the empty state when there are no policies', async () => {
    listRowLimitPolicies.mockResolvedValue([]);
    renderTab();

    expect(await screen.findByText('No row-limit policies')).toBeInTheDocument();
  });

  it('lists policies with the qualified table, cap and scope summary', async () => {
    listRowLimitPolicies.mockResolvedValue([
      policy,
      { ...policy, id: 'rlp-2', schema_name: undefined, table_name: 'orders', max_rows: 200,
        applies_to_roles: [], applies_to_user_ids: [], enabled: false },
    ]);
    renderTab();

    expect(await screen.findByText('crm.customer')).toBeInTheDocument();
    expect(screen.getByText('orders')).toBeInTheDocument();
    expect(screen.getByText((1000).toLocaleString())).toBeInTheDocument();
    expect(screen.getByText('2 roles · 1 users')).toBeInTheDocument();
    expect(screen.getByText('Everyone')).toBeInTheDocument();
  });

  it('creates a policy, sending a null schema when none is given', async () => {
    listRowLimitPolicies.mockResolvedValue([]);
    renderTab();
    fireEvent.click(await screen.findByText('Add policy'));

    const modal = await waitFor(() => {
      const el = dialog();
      expect(el).toBeTruthy();
      return el;
    });
    fireEvent.change(within(modal).getByLabelText('Table'), { target: { value: 'orders' } });
    fireEvent.change(within(modal).getByLabelText('Max rows'), { target: { value: '200' } });
    fireEvent.click(within(modal).getByText('Save'));

    await waitFor(() => expect(createRowLimitPolicy).toHaveBeenCalled());
    expect(createRowLimitPolicy.mock.calls[0]?.[0]).toBe('ds-1');
    expect(createRowLimitPolicy.mock.calls[0]?.[1]).toEqual({
      schema_name: null,
      table_name: 'orders',
      max_rows: 200,
      applies_to_roles: [],
      applies_to_group_ids: [],
      applies_to_user_ids: [],
      enabled: true,
    });
  });

  it('requires a table and max rows before saving', async () => {
    listRowLimitPolicies.mockResolvedValue([]);
    renderTab();
    fireEvent.click(await screen.findByText('Add policy'));
    const modal = await waitFor(() => {
      const el = dialog();
      expect(el).toBeTruthy();
      return el;
    });

    fireEvent.click(within(modal).getByText('Save'));

    expect(await within(modal).findByText('Table is required')).toBeInTheDocument();
    expect(within(modal).getByText('Max rows is required')).toBeInTheDocument();
    expect(createRowLimitPolicy).not.toHaveBeenCalled();
  });

  it('edits an existing policy through update', async () => {
    renderTab();
    fireEvent.click(await screen.findByLabelText('Edit policy'));
    const modal = await waitFor(() => {
      const el = dialog();
      expect(el).toBeTruthy();
      return el;
    });
    await waitFor(() =>
      expect(within(modal).getByLabelText('Table')).toHaveValue('customer'),
    );
    fireEvent.change(within(modal).getByLabelText('Max rows'), { target: { value: '50' } });
    fireEvent.click(within(modal).getByText('Save'));

    await waitFor(() => expect(updateRowLimitPolicy).toHaveBeenCalled());
    expect(updateRowLimitPolicy.mock.calls[0]?.[1]).toBe('rlp-1');
    expect(updateRowLimitPolicy.mock.calls[0]?.[2]).toMatchObject({
      schema_name: 'crm',
      table_name: 'customer',
      max_rows: 50,
      applies_to_roles: ['ANALYST', 'AUDITOR'],
      applies_to_user_ids: ['u-1'],
    });
  });

  it('deletes a policy after confirmation', async () => {
    renderTab();
    fireEvent.click(await screen.findByLabelText('Delete policy'));
    const [confirm] = await screen.findAllByText('Delete this row-limit policy?');
    const confirmDialog = confirm?.closest('.ant-modal') as HTMLElement;
    fireEvent.click(within(confirmDialog).getByRole('button', { name: 'Delete policy' }));

    await waitFor(() => expect(deleteRowLimitPolicy).toHaveBeenCalled());
    expect(deleteRowLimitPolicy.mock.calls[0]).toEqual(['ds-1', 'rlp-1']);
  });

  it('surfaces a save failure', async () => {
    listRowLimitPolicies.mockResolvedValue([]);
    createRowLimitPolicy.mockRejectedValue(new Error('Row limit policy rejected'));
    renderTab();
    fireEvent.click(await screen.findByText('Add policy'));
    const modal = await waitFor(() => {
      const el = dialog();
      expect(el).toBeTruthy();
      return el;
    });
    fireEvent.change(within(modal).getByLabelText('Schema'), { target: { value: ' crm ' } });
    fireEvent.change(within(modal).getByLabelText('Table'), { target: { value: 'customer' } });
    fireEvent.change(within(modal).getByLabelText('Max rows'), { target: { value: '5' } });
    fireEvent.click(within(modal).getByText('Save'));

    await waitFor(() => expect(createRowLimitPolicy).toHaveBeenCalled());
    expect(createRowLimitPolicy.mock.calls[0]?.[1]).toMatchObject({ schema_name: 'crm' });
    expect(await screen.findByText('Row limit policy rejected')).toBeInTheDocument();
  });
});
