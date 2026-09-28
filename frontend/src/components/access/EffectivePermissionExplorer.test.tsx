import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@/i18n';
import type { EffectivePermission } from '@/types/api';

const getEffectivePermission = vi.fn();

vi.mock('@/api/accessSimulations', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/accessSimulations')>();
  return {
    effectiveAccessKeys: original.effectiveAccessKeys,
    getEffectivePermission: (...args: unknown[]) => getEffectivePermission(...args),
  };
});

// The shared pickers fetch users and datasources; a plain input stands in for each.
vi.mock('@/components/policies/SimulationUserSelect', () => ({
  SimulationUserSelect: ({ onChange }: { onChange?: (v: string) => void }) => (
    <input aria-label="user-picker" onChange={(e) => onChange?.(e.target.value)} />
  ),
}));
vi.mock('@/components/policies/SimulationDatasourceSelect', () => ({
  SimulationDatasourceSelect: ({ onChange }: { onChange?: (v: string) => void }) => (
    <input aria-label="datasource-picker" onChange={(e) => onChange?.(e.target.value)} />
  ),
}));

const { EffectivePermissionExplorer } = await import('./EffectivePermissionExplorer');

function explanation(over: Partial<EffectivePermission> = {}): EffectivePermission {
  return {
    user: { id: 'u-1', email: 'dana@example.com', display_name: 'Dana' },
    datasource: { id: 'ds-1', name: 'Prod', db_type: 'POSTGRESQL' },
    has_grant: true,
    query_admin: false,
    expires_at: null,
    grants: [
      { grant_id: 'g-direct', source_kind: 'DIRECT', row_limit_override: 5000 },
      {
        grant_id: 'g-group',
        source_kind: 'GROUP',
        group_id: 'grp-1',
        group_name: 'analysts',
        row_limit_override: 50,
        expires_at: '2026-10-06T00:00:00Z',
      },
    ],
    capabilities: [
      { capability: 'READ', granted: true, grant_ids: ['g-direct', 'g-group'] },
      { capability: 'WRITE', granted: false, grant_ids: [] },
      { capability: 'DDL', granted: false, grant_ids: [] },
      { capability: 'BREAK_GLASS', granted: false, grant_ids: [] },
    ],
    allowed_schemas: { unrestricted: true, entries: [] },
    allowed_tables: {
      unrestricted: false,
      entries: [{ value: 'public.orders', grant_ids: ['g-group'] }],
    },
    restricted_columns: [],
    denied_columns: [{ value: 'public.users.ssn', grant_ids: ['g-direct'] }],
    denied_schemas: [],
    denied_tables: [],
    denied_shapes: [{ value: 'CTE', grant_ids: ['g-unknown'] }],
    row_cap: {
      value: 50,
      source: 'OVERRIDE',
      override: 50,
      datasource_cap: 1000,
      global_ceiling: 10000,
      grant_ids: ['g-group'],
    },
    bytes_scanned_limit: { value: 2_000_000_000, grant_ids: ['g-direct'] },
    table_row_limits: [
      {
        policy_id: 'p-1',
        schema_name: 'public',
        table_name: 'orders',
        max_rows: 10,
        matched_by: [{ kind: 'EVERYONE' }],
      },
      { policy_id: 'p-2', table_name: 'items', max_rows: 3, matched_by: [{ kind: 'USER', ref: 'u-1' }] },
    ],
    masked_columns: [{ policy_id: 'm-1', column_ref: 'public.users.email', strategy: 'PARTIAL' }],
    revealed_masks: [
      {
        policy_id: 'm-2',
        column_ref: 'public.users.phone',
        strategy: 'FULL',
        revealed_by: [{ kind: 'ROLE', ref: 'ADMIN' }],
      },
    ],
    row_security: [
      {
        policy_id: 'r-1',
        table_ref: 'orders',
        column_name: 'region',
        operator: 'EQUALS',
        values: ['EU'],
        value_type: 'VARIABLE',
        value_expression: 'user.region',
        matched_by: [{ kind: 'GROUP', ref: 'grp-1', name: 'analysts' }],
      },
      {
        policy_id: 'r-2',
        table_ref: 'orders',
        column_name: 'tenant',
        operator: 'IN',
        values: [],
        value_type: 'VARIABLE',
        value_expression: 'user.tenant',
        matched_by: [{ kind: 'EVERYONE' }],
      },
      {
        policy_id: 'r-3',
        table_ref: 'items',
        column_name: 'tier',
        operator: 'IN',
        values: ['gold', 'silver'],
        value_type: 'LITERAL',
        value_expression: 'gold',
        matched_by: [{ kind: 'GROUP', ref: 'grp-9' }],
      },
    ],
    ...over,
  };
}

function renderExplorer(props: { userId?: string; datasourceId?: string }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <EffectivePermissionExplorer {...props} />
    </QueryClientProvider>,
  );
}

describe('EffectivePermissionExplorer', () => {
  beforeEach(() => {
    getEffectivePermission.mockReset();
  });

  it('asks for the missing side before fetching', () => {
    renderExplorer({ datasourceId: 'ds-1' });

    expect(screen.getByText(/Pick a user and a datasource/)).toBeInTheDocument();
    expect(screen.queryByLabelText('datasource-picker')).not.toBeInTheDocument();
    expect(getEffectivePermission).not.toHaveBeenCalled();
  });

  it('fetches once the user is picked on the datasource side', async () => {
    getEffectivePermission.mockResolvedValue(explanation());
    renderExplorer({ datasourceId: 'ds-1' });

    fireEvent.change(screen.getByLabelText('user-picker'), { target: { value: 'u-1' } });

    await screen.findByTestId('effective-permission');
    expect(getEffectivePermission).toHaveBeenCalledWith('u-1', 'ds-1');
  });

  it('fetches once the datasource is picked on the user side', async () => {
    getEffectivePermission.mockResolvedValue(explanation());
    renderExplorer({ userId: 'u-1' });

    fireEvent.change(screen.getByLabelText('datasource-picker'), { target: { value: 'ds-9' } });

    await screen.findByTestId('effective-permission');
    expect(getEffectivePermission).toHaveBeenCalledWith('u-1', 'ds-9');
  });

  it('names the group that set a row cap and lists every configured override', async () => {
    getEffectivePermission.mockResolvedValue(explanation());
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    const card = await screen.findByTestId('explorer-row-cap');
    expect(within(card).getByTestId('explorer-row-cap-value')).toHaveTextContent('50');
    expect(within(card).getByTestId('explorer-row-cap-source')).toHaveAttribute(
      'data-source',
      'OVERRIDE',
    );
    expect(within(card).getByTestId('explorer-row-cap-explanation')).toHaveTextContent(
      'Group: analysts',
    );
    const contributions = within(card).getByTestId('explorer-row-cap-contributions');
    expect(contributions).toHaveTextContent('Direct grant: 5,000');
    expect(contributions).toHaveTextContent('Group: analysts: 50');
    expect(within(card).getByText(/Table row-limit policies below/)).toBeInTheDocument();
  });

  it('attributes a row cap set by the direct grant', async () => {
    getEffectivePermission.mockResolvedValue(
      explanation({
        grants: [{ grant_id: 'g-direct', source_kind: 'DIRECT', row_limit_override: 5 }],
        row_cap: {
          value: 5,
          source: 'OVERRIDE',
          override: 5,
          datasource_cap: 1000,
          global_ceiling: 10000,
          grant_ids: ['g-direct'],
        },
        table_row_limits: [],
      }),
    );
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    const card = await screen.findByTestId('explorer-row-cap');
    expect(within(card).getByTestId('explorer-row-cap-explanation')).toHaveTextContent(
      'Direct grant',
    );
    expect(within(card).queryByTestId('explorer-row-cap-contributions')).not.toBeInTheDocument();
  });

  it('explains an override clamped to the datasource cap', async () => {
    getEffectivePermission.mockResolvedValue(
      explanation({
        grants: [{ grant_id: 'g-direct', source_kind: 'DIRECT', row_limit_override: 5000 }],
        row_cap: {
          value: 1000,
          source: 'DATASOURCE_CAP',
          override: 5000,
          datasource_cap: 1000,
          global_ceiling: 10000,
          grant_ids: [],
        },
      }),
    );
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    const card = await screen.findByTestId('explorer-row-cap');
    expect(within(card).getByTestId('explorer-row-cap-source')).toHaveTextContent('Datasource cap');
    expect(within(card).getByTestId('explorer-row-cap-explanation')).toHaveTextContent(
      'max rows per query',
    );
    expect(within(card).getByTestId('explorer-row-cap-clamped')).toHaveTextContent(
      'override of 5,000 has no effect',
    );
    expect(within(card).getByTestId('explorer-row-cap-contributions')).toHaveTextContent(
      'Direct grant: 5,000',
    );
  });

  it('attributes a cap to the global ceiling', async () => {
    getEffectivePermission.mockResolvedValue(
      explanation({
        has_grant: false,
        query_admin: true,
        grants: [],
        row_cap: {
          value: 10000,
          source: 'GLOBAL_CEILING',
          override: null,
          datasource_cap: 50000,
          global_ceiling: 10000,
          grant_ids: [],
        },
      }),
    );
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    const card = await screen.findByTestId('explorer-row-cap');
    expect(within(card).getByTestId('explorer-row-cap-explanation')).toHaveTextContent(
      'ACCESSFLOW_PROXY_EXECUTION_MAX_ROWS',
    );
    expect(screen.getByTestId('explorer-no-grant')).toBeInTheDocument();
    expect(screen.getByText(/holds Query admin/)).toBeInTheDocument();
    expect(screen.queryByTestId('explorer-expiry')).not.toBeInTheDocument();
  });

  it('renders row-security predicates readably, with their value source and targeting', async () => {
    getEffectivePermission.mockResolvedValue(explanation());
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    const rls = await screen.findByTestId('explorer-row-security');
    expect(rls).toHaveTextContent("region = 'EU'");
    expect(rls).toHaveTextContent('value from user.region');
    expect(rls).toHaveTextContent('applies to group analysts');
    expect(rls).toHaveTextContent('user.tenant does not resolve for this user');
    expect(rls).toHaveTextContent("tier IN ('gold', 'silver')");
    expect(rls).toHaveTextContent('applies to group grp-9');
    expect(rls).toHaveTextContent('applies to everyone');
  });

  it('shows capabilities, scope, masking and table limits with their sources', async () => {
    getEffectivePermission.mockResolvedValue(explanation({ expires_at: '2026-12-01T00:00:00Z' }));
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    await screen.findByTestId('effective-permission');
    expect(screen.getByTestId('explorer-expiry')).toHaveTextContent('Access expires');
    expect(screen.getByText('All schemas')).toBeInTheDocument();
    // Once as an allowed table, once as a table row-limit policy.
    expect(screen.getAllByText('public.orders')).toHaveLength(2);
    expect(screen.getByText('public.users.ssn')).toBeInTheDocument();
    expect(screen.getByText('Unknown grant')).toBeInTheDocument();
    expect(screen.getByTestId('explorer-masked')).toHaveTextContent('public.users.email');
    expect(screen.getByText(/revealed to role ADMIN/)).toBeInTheDocument();
    expect(screen.getByText(/applies to this user directly/)).toBeInTheDocument();
    expect(screen.getByText('2 GB')).toBeInTheDocument();
    expect(screen.getAllByText(/expires/).length).toBeGreaterThan(0);
  });

  it('renders the empty sections for a user with no policies', async () => {
    getEffectivePermission.mockResolvedValue(
      explanation({
        grants: [],
        allowed_tables: { unrestricted: true, entries: [] },
        denied_columns: [],
        denied_shapes: [],
        bytes_scanned_limit: null,
        table_row_limits: [],
        masked_columns: [],
        revealed_masks: [],
        row_security: [],
        capabilities: [{ capability: 'READ', granted: false, grant_ids: [] }],
      }),
    );
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    await screen.findByTestId('effective-permission');
    expect(screen.getByText('All tables')).toBeInTheDocument();
    expect(screen.getByText('No column is masked for this user.')).toBeInTheDocument();
    expect(screen.getByText('No row-security policy applies to this user.')).toBeInTheDocument();
    expect(screen.getByText('No table row-limit policy applies to this user.')).toBeInTheDocument();
    expect(screen.getByText('Access never expires')).toBeInTheDocument();
  });

  it('shows the server error detail', async () => {
    getEffectivePermission.mockRejectedValue(new Error('boom'));
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    expect(await screen.findByText('boom')).toBeInTheDocument();
    expect(screen.getByText('Could not load effective access')).toBeInTheDocument();
  });

  it('shows the lifecycle directives that apply to everyone, and the budget caveat', async () => {
    getEffectivePermission.mockResolvedValue(
      explanation({
        denied_shapes: [{ value: 'SOMETHING_NEW', grant_ids: [] }],
        row_cap: {
          value: 30,
          source: 'ROW_LIMIT_POLICY',
          datasource_cap: 1000,
          global_ceiling: 10000,
          grant_ids: [],
        },
        retention_masks: [{ policy_id: 'lm-1', column_ref: 'public.users.ssn', strategy: 'HASH' }],
        soft_delete_filters: [
          { policy_id: 'sd-1', table_ref: 'public.orders', column_name: 'deleted_at' },
        ],
        row_security: [],
      }),
    );
    renderExplorer({ userId: 'u-1', datasourceId: 'ds-1' });

    expect(await screen.findByTestId('explorer-retention-masks')).toHaveTextContent(
      'public.users.ssn',
    );
    expect(screen.getByTestId('explorer-soft-delete')).toHaveTextContent('deleted_at IS NULL');
    expect(screen.getByText(/A data budget can cut a result shorter/)).toBeInTheDocument();
    expect(screen.getByTestId('explorer-row-cap-source')).toHaveTextContent('Row-limit policy');
    // An unknown shape from a newer server renders raw instead of a missing i18n key.
    expect(screen.getByText('SOMETHING_NEW')).toBeInTheDocument();
  });
});
