import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiDriftFinding, ApiSchema, ApiSchemaRevision } from '@/types/api';

const {
  listApiDriftFindings,
  listApiSchemas,
  listApiSchemaRevisions,
  refreshApiSchema,
  acceptApiSchemaRevision,
  dismissApiDriftFinding,
} = vi.hoisted(() => ({
  listApiDriftFindings: vi.fn(),
  listApiSchemas: vi.fn(),
  listApiSchemaRevisions: vi.fn(),
  refreshApiSchema: vi.fn(),
  acceptApiSchemaRevision: vi.fn(),
  dismissApiDriftFinding: vi.fn(),
}));

vi.mock('@/api/apiConnectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return {
    ...actual,
    listApiDriftFindings,
    listApiSchemas,
    listApiSchemaRevisions,
    refreshApiSchema,
    acceptApiSchemaRevision,
    dismissApiDriftFinding,
  };
});

import { ApiConnectorDriftTab } from './ApiConnectorDriftTab';

const schema: ApiSchema = {
  id: 's-1',
  schema_type: 'OPENAPI',
  source_url: 'https://specs.example.com/petstore.json',
  operation_count: 2,
  total_operation_count: 2,
  operation_filter: null,
  detected_auth_method: null,
  refresh_interval_minutes: 15,
  current_revision_id: 'r-1',
  created_at: '2026-05-01T00:00:00Z',
};

const currentRevision: ApiSchemaRevision = {
  id: 'r-1',
  schema_id: 's-1',
  content_hash: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  operation_count: 2,
  fetched_at: '2026-05-01T00:00:00Z',
  last_seen_at: '2026-05-03T00:00:00Z',
  accepted_at: '2026-05-01T00:00:00Z',
};

const newRevision: ApiSchemaRevision = {
  id: 'r-2',
  schema_id: 's-1',
  content_hash: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
  operation_count: 1,
  fetched_at: '2026-05-04T00:00:00Z',
  last_seen_at: '2026-05-04T00:00:00Z',
};

const removedFinding: ApiDriftFinding = {
  id: 'f-1',
  schema_id: 's-1',
  revision_id: 'r-2',
  kind: 'OPERATION_REMOVED',
  operation_id: 'createPet',
  before: {
    operation_id: 'createPet',
    verb: 'POST',
    path: '/pets',
    summary: null,
    write: true,
  },
  affected_grant_count: 2,
  status: 'OPEN',
  created_at: '2026-05-04T00:00:00Z',
};

const shapeFinding: ApiDriftFinding = {
  id: 'f-2',
  schema_id: 's-1',
  revision_id: 'r-2',
  kind: 'SHAPE_CHANGED',
  operation_id: 'listPets',
  before: { operation_id: 'listPets', verb: 'GET', path: '/pets', summary: null, write: false },
  after: {
    operation_id: 'listPets',
    verb: 'GET',
    path: '/v2/pets',
    summary: null,
    write: false,
    deprecated: true,
  },
  affected_grant_count: 0,
  status: 'OPEN',
  created_at: '2026-05-04T00:00:00Z',
};

function setup(onOpenPermissions = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, 'invalidateQueries');
  const node: ReactNode = (
    <QueryClientProvider client={client}>
      <AntdApp>
        <ApiConnectorDriftTab connectorId="c-1" onOpenPermissions={onOpenPermissions} />
      </AntdApp>
    </QueryClientProvider>
  );
  render(node);
  return { invalidate, onOpenPermissions };
}

describe('ApiConnectorDriftTab (#896)', () => {
  beforeEach(() => {
    listApiDriftFindings.mockReset();
    listApiDriftFindings.mockResolvedValue([removedFinding, shapeFinding]);
    listApiSchemas.mockReset();
    listApiSchemas.mockResolvedValue([schema]);
    listApiSchemaRevisions.mockReset();
    listApiSchemaRevisions.mockResolvedValue([newRevision, currentRevision]);
    refreshApiSchema.mockReset();
    acceptApiSchemaRevision.mockReset();
    dismissApiDriftFinding.mockReset();
  });

  it('renders open findings with labelled kinds and a before → after summary', async () => {
    setup();

    expect(await screen.findByText('Operation removed')).toBeInTheDocument();
    expect(screen.getByText('Shape changed')).toBeInTheDocument();
    expect(screen.getByText('POST /pets (Write)')).toBeInTheDocument();
    expect(
      screen.getByText('GET /pets (Read) → GET /v2/pets (Read · deprecated)'),
    ).toBeInTheDocument();
    expect(listApiDriftFindings.mock.calls[0]).toEqual(['c-1', 'OPEN']);
  });

  it('links stranded grants to the permissions tab', async () => {
    const { onOpenPermissions } = setup();

    fireEvent.click(await screen.findByRole('button', { name: /2 grant\(s\)/ }));

    expect(onOpenPermissions).toHaveBeenCalledTimes(1);
  });

  it('dismisses a finding by id after confirmation', async () => {
    dismissApiDriftFinding.mockResolvedValue(undefined);
    setup();
    await screen.findByText('Operation removed');

    fireEvent.click(screen.getAllByRole('button', { name: 'Dismiss' })[0]!);
    const popover = await screen.findByRole('tooltip');
    fireEvent.click(within(popover).getByRole('button', { name: 'Dismiss' }));

    await waitFor(() => expect(dismissApiDriftFinding).toHaveBeenCalled());
    expect(dismissApiDriftFinding.mock.calls[0]![0]).toBe('c-1');
    expect(dismissApiDriftFinding.mock.calls[0]![1]).toBe('f-1');
  });

  it('shows the resolved status instead of Dismiss for non-open findings', async () => {
    listApiDriftFindings.mockResolvedValue([{ ...shapeFinding, status: 'ACCEPTED' }]);
    setup();

    await screen.findByText('Shape changed');
    expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument();
  });

  it('shows the empty findings state', async () => {
    listApiDriftFindings.mockResolvedValue([]);
    setup();

    expect(await screen.findByText('No drift findings')).toBeInTheDocument();
  });

  it('reports an unchanged refresh', async () => {
    refreshApiSchema.mockResolvedValue({ finding_count: 0, unchanged: true });
    setup();

    fireEvent.click(await screen.findByRole('button', { name: 'Refresh now' }));

    expect(await screen.findByText('The schema is unchanged')).toBeInTheDocument();
    expect(refreshApiSchema.mock.calls[0]).toEqual(['c-1', 's-1']);
  });

  it('reports the finding count of a changed refresh', async () => {
    refreshApiSchema.mockResolvedValue({ revision_id: 'r-3', finding_count: 3, unchanged: false });
    setup();

    fireEvent.click(await screen.findByRole('button', { name: 'Refresh now' }));

    expect(await screen.findByText('New revision fetched: 3 finding(s)')).toBeInTheDocument();
  });

  it('lists revisions with the current badge and a hash prefix', async () => {
    setup();

    expect(await screen.findByText('bbbbbbbbbbbb')).toBeInTheDocument();
    expect(screen.getByText('Current')).toBeInTheDocument();
    expect(screen.getByText('Not accepted')).toBeInTheDocument();
  });

  it('accepts a revision after a confirm naming the delta, and re-reads the catalog', async () => {
    acceptApiSchemaRevision.mockResolvedValue({ ...schema, current_revision_id: 'r-2' });
    const { invalidate } = setup();
    await screen.findByText('Operation removed');

    fireEvent.click(await screen.findByRole('button', { name: 'Accept revision' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Operations: 2 → 1')).toBeInTheDocument();
    expect(within(dialog).getByText('Operation removed: 1')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Accept revision' }));

    await waitFor(() => expect(acceptApiSchemaRevision).toHaveBeenCalled());
    expect(acceptApiSchemaRevision.mock.calls[0]).toEqual(['c-1', 's-1', 'r-2']);
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: ['api-connectors', 'detail', 'c-1', 'operations'],
      }),
    );
  });

  it('offers Accept on every revision but the current one, including an earlier accepted one', async () => {
    listApiSchemaRevisions.mockResolvedValue([
      currentRevision,
      { ...newRevision, accepted_at: '2026-04-01T00:00:00Z' },
    ]);
    setup();

    await screen.findByText('bbbbbbbbbbbb');
    expect(screen.getAllByRole('button', { name: 'Accept revision' })).toHaveLength(1);
  });

  it('says there is nothing to refresh without a URL-sourced schema', async () => {
    listApiSchemas.mockResolvedValue([{ ...schema, source_url: null }]);
    setup();

    expect(
      await screen.findByText('No schema was loaded from a URL, so there is nothing to refresh.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Refresh now' })).not.toBeInTheDocument();
  });

  it('shows a load error instead of the empty "no drift" state when findings fail', async () => {
    listApiDriftFindings.mockRejectedValue(new Error('upstream exploded'));
    setup();

    expect(await screen.findByText('upstream exploded')).toBeInTheDocument();
    expect(screen.queryByText('No drift findings')).not.toBeInTheDocument();
  });

  it('shows a load error instead of "nothing to refresh" when schemas fail', async () => {
    listApiSchemas.mockRejectedValue(new Error('upstream exploded'));
    setup();

    await screen.findAllByText('upstream exploded');
    expect(
      screen.queryByText('No schema was loaded from a URL, so there is nothing to refresh.'),
    ).not.toBeInTheDocument();
  });

  it('shows a load error instead of "no revisions" when revisions fail', async () => {
    listApiSchemaRevisions.mockRejectedValue(new Error('upstream exploded'));
    setup();

    await screen.findByText('upstream exploded');
    expect(screen.queryByText('No revisions yet')).not.toBeInTheDocument();
  });

  it('surfaces the last refresh error on the schema card', async () => {
    listApiSchemas.mockResolvedValue([{ ...schema, last_refresh_error: 'HTTP 503' }]);
    setup();

    expect(await screen.findByText('HTTP 503')).toBeInTheDocument();
  });
});
