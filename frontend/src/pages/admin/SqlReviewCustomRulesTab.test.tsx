import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import type { SqlReviewCustomRule } from '@/types/api';

const api = vi.hoisted(() => ({
  listSqlReviewCustomRules: vi.fn(),
  updateSqlReviewCustomRule: vi.fn(),
  deleteSqlReviewCustomRule: vi.fn(),
}));

vi.mock('@/api/sqlReviewRules', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/sqlReviewRules')>('@/api/sqlReviewRules');
  return { ...actual, ...api };
});

const { SqlReviewCustomRulesTab } = await import('./SqlReviewCustomRulesTab');

function rule(over: Partial<SqlReviewCustomRule> = {}): SqlReviewCustomRule {
  return {
    id: 'r-1',
    organization_id: 'org-1',
    rule_id: 'custom_billing_delete',
    name: 'Billing delete',
    description: 'No unbounded billing deletes',
    message: 'm',
    category: 'DATA_PROTECTION',
    default_severity: 'BLOCK',
    enabled: true,
    condition: { type: 'and', children: [{ type: 'has_where', expected: false }] },
    created_at: '2026-10-01T10:00:00Z',
    updated_at: '2026-10-02T10:00:00Z',
    ...over,
  };
}

function problem(status: number, data: Record<string, unknown>): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('failed', undefined, undefined, undefined, response);
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

async function confirmDelete() {
  // AntD's confirm renders its title twice (modal title + confirm title).
  expect((await screen.findAllByText('Delete custom rule?')).length).toBeGreaterThan(0);
  const deletes = screen.getAllByText('Delete');
  fireEvent.click(deletes[deletes.length - 1]!);
}

describe('SqlReviewCustomRulesTab (#1011)', () => {
  beforeEach(() => {
    Object.values(api).forEach((m) => m.mockReset());
  });

  it('lists the custom rules with their id, category and severity', async () => {
    api.listSqlReviewCustomRules.mockResolvedValue([
      rule(),
      rule({ id: 'r-2', rule_id: 'custom_paused', name: 'Paused', default_severity: 'OFF', enabled: false, description: undefined }),
    ]);
    render(wrap(<SqlReviewCustomRulesTab onEdit={vi.fn()} />));
    expect(await screen.findByText('Billing delete')).toBeInTheDocument();
    expect(screen.getByText('custom_billing_delete')).toBeInTheDocument();
    expect(screen.getByText('No unbounded billing deletes')).toBeInTheDocument();
    expect(screen.getAllByText('Data protection')).toHaveLength(2);
    expect(screen.getByText('Block')).toBeInTheDocument();
    expect(screen.getByText('Off')).toBeInTheDocument();
  });

  it('shows the empty and error states', async () => {
    api.listSqlReviewCustomRules.mockResolvedValueOnce([]);
    const { unmount } = render(wrap(<SqlReviewCustomRulesTab onEdit={vi.fn()} />));
    expect(await screen.findByText('No custom rules yet')).toBeInTheDocument();
    unmount();
    api.listSqlReviewCustomRules.mockRejectedValueOnce(problem(500, { detail: 'boom' }));
    render(wrap(<SqlReviewCustomRulesTab onEdit={vi.fn()} />));
    expect(await screen.findByText('Could not load custom rules')).toBeInTheDocument();
    expect(screen.getByText('boom')).toBeInTheDocument();
  });

  it('opens a rule for editing', async () => {
    const onEdit = vi.fn();
    api.listSqlReviewCustomRules.mockResolvedValue([rule()]);
    render(wrap(<SqlReviewCustomRulesTab onEdit={onEdit} />));
    fireEvent.click(await screen.findByLabelText(/Edit Billing delete/));
    expect(onEdit.mock.calls[0]?.[0]).toMatchObject({ id: 'r-1' });
  });

  it('toggles enabled with a full-replace PUT and reports failures', async () => {
    api.listSqlReviewCustomRules.mockResolvedValue([rule()]);
    api.updateSqlReviewCustomRule.mockRejectedValue(
      problem(422, { error: 'SQL_REVIEW_RULE_INVALID', detail: 'too deep' }),
    );
    render(wrap(<SqlReviewCustomRulesTab onEdit={vi.fn()} />));
    fireEvent.click(await screen.findByLabelText('Enable Billing delete'));
    await waitFor(() => expect(api.updateSqlReviewCustomRule).toHaveBeenCalled());
    expect(api.updateSqlReviewCustomRule.mock.calls[0]?.[0]).toBe('r-1');
    expect(api.updateSqlReviewCustomRule.mock.calls[0]?.[1]).toMatchObject({
      rule_id: 'custom_billing_delete',
      enabled: false,
      condition: { type: 'and', children: [{ type: 'has_where', expected: false }] },
    });
    expect(await screen.findByText('too deep')).toBeInTheDocument();
  });

  it('deletes a rule after confirmation', async () => {
    api.listSqlReviewCustomRules.mockResolvedValue([rule()]);
    api.deleteSqlReviewCustomRule.mockResolvedValue(undefined);
    render(wrap(<SqlReviewCustomRulesTab onEdit={vi.fn()} />));
    fireEvent.click(await screen.findByLabelText(/Delete Billing delete/));
    await confirmDelete();
    await waitFor(() => expect(api.deleteSqlReviewCustomRule.mock.calls[0]?.[0]).toBe('r-1'));
    expect(await screen.findByText('Custom rule deleted')).toBeInTheDocument();
  });

  it('reports a failed delete', async () => {
    api.listSqlReviewCustomRules.mockResolvedValue([rule()]);
    api.deleteSqlReviewCustomRule.mockRejectedValue(
      problem(404, { error: 'SQL_REVIEW_RULE_NOT_FOUND' }),
    );
    render(wrap(<SqlReviewCustomRulesTab onEdit={vi.fn()} />));
    fireEvent.click(await screen.findByLabelText(/Delete Billing delete/));
    await confirmDelete();
    expect(await screen.findByText('Custom SQL review rule not found.')).toBeInTheDocument();
  });
});
