import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import type { SqlReviewCustomRule } from '@/types/api';

const api = vi.hoisted(() => ({
  createSqlReviewCustomRule: vi.fn(),
  updateSqlReviewCustomRule: vi.fn(),
  testSqlReviewCustomRule: vi.fn(),
}));

vi.mock('@/api/sqlReviewRules', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/sqlReviewRules')>('@/api/sqlReviewRules');
  return { ...actual, ...api };
});

vi.mock('@/components/editor/SqlEditor', () => ({
  SqlEditor: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <textarea aria-label="test sql" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

const { SqlReviewCustomRuleDrawer } = await import('./SqlReviewCustomRuleDrawer');

const stored: SqlReviewCustomRule = {
  id: 'r-1',
  organization_id: 'org-1',
  rule_id: 'custom_billing_delete',
  name: 'Billing delete',
  message: 'Unbounded write on {tables}',
  category: 'DATA_PROTECTION',
  default_severity: 'BLOCK',
  enabled: true,
  condition: { type: 'and', children: [{ type: 'has_where', expected: false }] },
  created_at: '2026-10-01T10:00:00Z',
  updated_at: '2026-10-01T10:00:00Z',
};

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

function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

describe('SqlReviewCustomRuleDrawer (#1011)', () => {
  beforeEach(() => {
    Object.values(api).forEach((m) => m.mockReset());
  });

  it('creates a rule from the form with the fixed custom_ prefix', async () => {
    api.createSqlReviewCustomRule.mockResolvedValue(stored);
    const onClose = vi.fn();
    render(wrap(<SqlReviewCustomRuleDrawer open rule={null} onClose={onClose} />));
    fill('Identifier', 'billing_delete');
    fill('Name', 'Billing delete');
    fill('Finding message', 'Unbounded write on {tables}');
    fireEvent.click(screen.getByText('Create rule'));
    await waitFor(() => expect(api.createSqlReviewCustomRule).toHaveBeenCalled());
    expect(api.createSqlReviewCustomRule.mock.calls[0]?.[0]).toEqual({
      rule_id: 'custom_billing_delete',
      name: 'Billing delete',
      message: 'Unbounded write on {tables}',
      category: 'STATEMENT_SAFETY',
      default_severity: 'WARN',
      enabled: true,
      condition: { type: 'and', children: [{ type: 'query_type', any_of: ['DELETE'] }] },
    });
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('rejects a malformed slug before calling the API', async () => {
    render(wrap(<SqlReviewCustomRuleDrawer open rule={null} onClose={vi.fn()} />));
    fill('Identifier', 'Bad-Slug');
    fireEvent.click(screen.getByText('Create rule'));
    expect(
      await screen.findByText('Use 3–61 lowercase letters, digits or underscores, starting with a letter.'),
    ).toBeInTheDocument();
    expect(api.createSqlReviewCustomRule).not.toHaveBeenCalled();
  });

  it('edits a stored rule with an immutable identifier', async () => {
    api.updateSqlReviewCustomRule.mockResolvedValue(stored);
    render(wrap(<SqlReviewCustomRuleDrawer open rule={stored} onClose={vi.fn()} />));
    expect(screen.getByLabelText('Identifier')).toBeDisabled();
    expect(screen.getByLabelText('Identifier')).toHaveValue('billing_delete');
    fill('Name', 'Renamed');
    fireEvent.click(screen.getByText('Save changes'));
    await waitFor(() => expect(api.updateSqlReviewCustomRule).toHaveBeenCalled());
    expect(api.updateSqlReviewCustomRule.mock.calls[0]?.[0]).toBe('r-1');
    expect(api.updateSqlReviewCustomRule.mock.calls[0]?.[1]).toMatchObject({
      rule_id: 'custom_billing_delete',
      name: 'Renamed',
      condition: { type: 'and', children: [{ type: 'has_where', expected: false }] },
    });
  });

  it('surfaces the server detail when a save fails', async () => {
    api.createSqlReviewCustomRule.mockRejectedValue(
      problem(409, { error: 'SQL_REVIEW_RULE_CONFLICT', detail: 'custom_billing_delete exists' }),
    );
    render(wrap(<SqlReviewCustomRuleDrawer open rule={null} onClose={vi.fn()} />));
    fill('Identifier', 'billing_delete');
    fill('Name', 'Billing delete');
    fill('Finding message', 'm');
    fireEvent.click(screen.getByText('Create rule'));
    expect(await screen.findByText('custom_billing_delete exists')).toBeInTheDocument();
  });

  it('warns when the stored tree is nested deeper than the builder', () => {
    render(
      wrap(
        <SqlReviewCustomRuleDrawer
          open
          rule={{ ...stored, condition: { type: 'or', children: [{ type: 'and', children: [] }] } }}
          onClose={vi.fn()}
        />,
      ),
    );
    expect(screen.getByText(/nested deeper than the builder can show/)).toBeInTheDocument();
  });

  it('tests the unsaved draft against SQL and lists the findings', async () => {
    api.testSqlReviewCustomRule.mockResolvedValue({
      findings: [
        { rule_id: 'custom_billing_delete', severity: 'BLOCK', statement_index: 0, line_number: 1, message: 'Hit billing' },
      ],
    });
    render(wrap(<SqlReviewCustomRuleDrawer open rule={stored} onClose={vi.fn()} />));
    fill('test sql', 'DELETE FROM billing.invoices');
    fireEvent.click(screen.getByText('Run test'));
    expect(await screen.findByText('Hit billing')).toBeInTheDocument();
    expect(api.testSqlReviewCustomRule.mock.calls[0]?.[0]).toMatchObject({
      sql: 'DELETE FROM billing.invoices',
      dialect: 'POSTGRESQL',
      rule: { rule_id: 'custom_billing_delete', default_severity: 'BLOCK' },
    });
    expect(api.createSqlReviewCustomRule).not.toHaveBeenCalled();
    expect(api.updateSqlReviewCustomRule).not.toHaveBeenCalled();
  });

  it('reports no findings and test errors inline', async () => {
    api.testSqlReviewCustomRule
      .mockResolvedValueOnce({ findings: [] })
      .mockRejectedValueOnce(problem(422, { error: 'INVALID_SQL', detail: 'line 1: unexpected token' }));
    render(wrap(<SqlReviewCustomRuleDrawer open rule={stored} onClose={vi.fn()} />));
    fill('test sql', 'SELECT 1');
    fireEvent.click(screen.getByText('Run test'));
    expect(await screen.findByText('No findings: the rule does not match this SQL.')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Run test'));
    expect(await screen.findByText('line 1: unexpected token')).toBeInTheDocument();
  });

  it('does not run a test while the draft or the SQL is invalid', async () => {
    render(wrap(<SqlReviewCustomRuleDrawer open rule={null} onClose={vi.fn()} />));
    fireEvent.click(screen.getByText('Run test'));
    await waitFor(() => expect(screen.getAllByText(/required|Please enter/i).length).toBeGreaterThan(0));
    expect(api.testSqlReviewCustomRule).not.toHaveBeenCalled();
  });

  it('reseeds the form for each rule it is reopened with (no values carried over)', async () => {
    const other: SqlReviewCustomRule = {
      ...stored,
      id: 'r-2',
      rule_id: 'custom_other_rule',
      name: 'Other rule',
    };
    const { rerender } = render(wrap(<SqlReviewCustomRuleDrawer open rule={stored} onClose={vi.fn()} />));
    expect(screen.getByLabelText('Name')).toHaveValue('Billing delete');
    fill('Name', 'Edited but not saved');
    rerender(wrap(<SqlReviewCustomRuleDrawer open={false} rule={null} onClose={vi.fn()} />));
    await waitFor(() => expect(screen.queryByLabelText('Name')).not.toBeInTheDocument());
    rerender(wrap(<SqlReviewCustomRuleDrawer open rule={other} onClose={vi.fn()} />));
    expect(await screen.findByLabelText('Name')).toHaveValue('Other rule');
    expect(screen.getByLabelText('Identifier')).toHaveValue('other_rule');
    rerender(wrap(<SqlReviewCustomRuleDrawer open={false} rule={null} onClose={vi.fn()} />));
    await waitFor(() => expect(screen.queryByLabelText('Name')).not.toBeInTheDocument());
    rerender(wrap(<SqlReviewCustomRuleDrawer open rule={null} onClose={vi.fn()} />));
    expect(await screen.findByLabelText('Name')).toHaveValue('');
    expect(screen.getByLabelText('Identifier')).toHaveValue('');
  });
});
