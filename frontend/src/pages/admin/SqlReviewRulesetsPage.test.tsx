import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import type { SqlReviewRule, SqlReviewRuleset } from '@/types/api';

const {
  listSqlReviewRulesetsMock,
  getSqlReviewRulesMock,
  createSqlReviewRulesetMock,
  updateSqlReviewRulesetMock,
  deleteSqlReviewRulesetMock,
} = vi.hoisted(() => ({
  listSqlReviewRulesetsMock: vi.fn(),
  getSqlReviewRulesMock: vi.fn(),
  createSqlReviewRulesetMock: vi.fn(),
  updateSqlReviewRulesetMock: vi.fn(),
  deleteSqlReviewRulesetMock: vi.fn(),
}));

vi.mock('@/api/sqlReview', async () => {
  const actual = await vi.importActual<typeof import('@/api/sqlReview')>('@/api/sqlReview');
  return {
    ...actual,
    listSqlReviewRulesets: listSqlReviewRulesetsMock,
    getSqlReviewRules: getSqlReviewRulesMock,
    createSqlReviewRuleset: createSqlReviewRulesetMock,
    updateSqlReviewRuleset: updateSqlReviewRulesetMock,
    deleteSqlReviewRuleset: deleteSqlReviewRulesetMock,
  };
});

const { SqlReviewRulesetsPage } = await import('./SqlReviewRulesetsPage');

const catalog: SqlReviewRule[] = [
  {
    rule_id: 'select_star',
    category: 'PERFORMANCE',
    default_severity: 'WARN',
    name: 'SELECT *',
    description: 'The select list is a bare *.',
    params: [],
  },
  {
    rule_id: 'protected_table',
    category: 'DATA_PROTECTION',
    default_severity: 'BLOCK',
    name: 'Protected table',
    description: 'Touches a protected table.',
    params: [{ key: 'globs', required: true, defaults: [], value_pattern: '[A-Za-z0-9_$*.-]+' }],
  },
];

function ruleset(over: Partial<SqlReviewRuleset> = {}): SqlReviewRuleset {
  return {
    id: 'rs-1',
    organization_id: 'org-1',
    name: 'Prod rules',
    description: 'Payroll is off limits',
    environment: 'PRODUCTION',
    enabled: true,
    rules: [{ rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*'] } }],
    created_at: '2026-09-11T10:00:00Z',
    updated_at: '2026-09-11T10:00:00Z',
    ...over,
  };
}

function conflict(code: string): AxiosError {
  const response = {
    data: { error: code },
    status: 409,
    statusText: '',
    headers: {},
    config: {} as never,
  } as AxiosResponse;
  return new AxiosError('Conflict', undefined, undefined, undefined, response);
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

async function pickOption(combobox: HTMLElement, label: string) {
  fireEvent.mouseDown(combobox);
  await waitFor(() =>
    expect([...document.querySelectorAll('.ant-select-item-option-content')].length).toBeGreaterThan(0),
  );
  const option = [...document.querySelectorAll('.ant-select-item-option-content')].find(
    (o) => o.textContent === label,
  );
  expect(option).toBeDefined();
  fireEvent.click(option!);
}

describe('SqlReviewRulesetsPage (#865)', () => {
  beforeEach(() => {
    listSqlReviewRulesetsMock.mockReset();
    getSqlReviewRulesMock.mockReset();
    createSqlReviewRulesetMock.mockReset();
    updateSqlReviewRulesetMock.mockReset();
    deleteSqlReviewRulesetMock.mockReset();
    getSqlReviewRulesMock.mockResolvedValue(catalog);
  });

  it('lists rulesets with their environment and effective-severity summary', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([
      ruleset({
        rules: [
          { rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*'] } },
          { rule_id: 'select_star', severity: 'BLOCK', params: {} },
        ],
      }),
      ruleset({ id: 'rs-2', name: 'Everyone', description: undefined, environment: undefined, rules: [] }),
    ]);

    render(wrap(<SqlReviewRulesetsPage />));

    expect(await screen.findByText('Prod rules')).toBeInTheDocument();
    expect(screen.getByText('Payroll is off limits')).toBeInTheDocument();
    expect(screen.getByText('Organization default')).toBeInTheDocument();
    const summaries = screen.getAllByTestId('sql-review-rules-summary');
    expect(summaries[0]).toHaveTextContent('2 block · 0 warn · 0 off');
    expect(summaries[1]).toHaveTextContent('1 block · 1 warn · 0 off');
  });

  it('shows the empty state and the load error', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([]);
    const { unmount } = render(wrap(<SqlReviewRulesetsPage />));
    expect(await screen.findByText(/No rulesets yet/)).toBeInTheDocument();
    unmount();

    listSqlReviewRulesetsMock.mockRejectedValue(new Error('boom'));
    render(wrap(<SqlReviewRulesetsPage />));
    expect(await screen.findByText('Could not load SQL review rulesets.')).toBeInTheDocument();
  });

  it('creates a ruleset from the modal, sending only rules that differ from their default', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([]);
    createSqlReviewRulesetMock.mockResolvedValue(ruleset({ name: 'Staging', environment: 'STAGING' }));

    render(wrap(<SqlReviewRulesetsPage />));
    await waitFor(() => expect(listSqlReviewRulesetsMock).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /Add ruleset/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Add ruleset', { selector: '.ant-modal-title' })).toBeInTheDocument();

    // The rule rows come from the catalog, with their built-in defaults pre-selected.
    expect(within(dialog).getByText('SELECT *')).toBeInTheDocument();
    expect(within(dialog).getByText('Protected table')).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Staging' } });
    await pickOption(within(dialog).getByRole('combobox', { name: 'Environment' }), 'Staging');
    await pickOption(within(dialog).getByRole('combobox', { name: 'Severity for SELECT *' }), 'Block');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Create ruleset' }));

    await waitFor(() => {
      expect(createSqlReviewRulesetMock).toHaveBeenCalledWith({
        name: 'Staging',
        environment: 'STAGING',
        enabled: true,
        rules: [{ rule_id: 'select_star', severity: 'BLOCK' }],
      });
    });
    expect(await screen.findByText('Ruleset created.')).toBeInTheDocument();
  });

  it('blocks submission on a missing name', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([]);

    render(wrap(<SqlReviewRulesetsPage />));
    await waitFor(() => expect(listSqlReviewRulesetsMock).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /Add ruleset/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create ruleset' }));

    await waitFor(() => {
      expect(document.querySelectorAll('.ant-form-item-explain-error').length).toBeGreaterThan(0);
    });
    expect(createSqlReviewRulesetMock).not.toHaveBeenCalled();
  });

  it('edits a ruleset with the stored severities and params pre-filled', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([ruleset()]);
    updateSqlReviewRulesetMock.mockResolvedValue(ruleset({ name: 'Prod rules v2' }));

    render(wrap(<SqlReviewRulesetsPage />));
    await screen.findByText('Prod rules');

    fireEvent.click(screen.getByRole('button', { name: /Edit/ }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText('Edit ruleset · Prod rules', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Name')).toHaveValue('Prod rules');
    expect(within(dialog).getByText('payroll.*')).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Prod rules v2' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(updateSqlReviewRulesetMock).toHaveBeenCalledWith('rs-1', {
        name: 'Prod rules v2',
        description: 'Payroll is off limits',
        environment: 'PRODUCTION',
        enabled: true,
        rules: [{ rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*'] } }],
      });
    });
    expect(await screen.findByText('Ruleset updated.')).toBeInTheDocument();
  });

  it('toggles enabled from the list with a full-replace body', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([ruleset()]);
    updateSqlReviewRulesetMock.mockResolvedValue(ruleset({ enabled: false }));

    render(wrap(<SqlReviewRulesetsPage />));
    await screen.findByText('Prod rules');

    fireEvent.click(screen.getByRole('switch', { name: 'Enabled' }));

    await waitFor(() => {
      expect(updateSqlReviewRulesetMock).toHaveBeenCalledWith('rs-1', {
        name: 'Prod rules',
        description: 'Payroll is off limits',
        environment: 'PRODUCTION',
        enabled: false,
        rules: [{ rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*'] } }],
      });
    });
  });

  it('surfaces the environment conflict from the server on save', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([ruleset()]);
    updateSqlReviewRulesetMock.mockRejectedValue(conflict('SQL_REVIEW_RULESET_ENVIRONMENT_CONFLICT'));

    render(wrap(<SqlReviewRulesetsPage />));
    await screen.findByText('Prod rules');

    fireEvent.click(screen.getByRole('switch', { name: 'Enabled' }));

    expect(
      await screen.findByText('Another ruleset is already bound to that environment.'),
    ).toBeInTheDocument();
  });

  it('deletes a ruleset after confirmation', async () => {
    listSqlReviewRulesetsMock.mockResolvedValue([ruleset()]);
    deleteSqlReviewRulesetMock.mockResolvedValue(undefined);

    render(wrap(<SqlReviewRulesetsPage />));
    await screen.findByText('Prod rules');

    fireEvent.click(screen.getByRole('button', { name: /Delete/ }));
    // AntD's confirm renders its title twice (modal title + confirm title).
    expect((await screen.findAllByText('Delete ruleset?')).length).toBeGreaterThan(0);
    const allDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(allDeletes[allDeletes.length - 1]!);

    await waitFor(() => expect(deleteSqlReviewRulesetMock).toHaveBeenCalledWith('rs-1'));
    expect(await screen.findByText('Ruleset deleted.')).toBeInTheDocument();
  });
});
