import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { AxiosError, AxiosHeaders } from 'axios';
import '@/i18n';
import type { SchemaChangeSet } from '@/types/api';

const api = vi.hoisted(() => ({
  listSchemaChangeSets: vi.fn(),
  listSchemaChangePipelines: vi.fn(),
  createSchemaChangeSet: vi.fn(),
  getSchemaChangeLadder: vi.fn(),
  navigate: vi.fn(),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => api.navigate };
});

vi.mock('@/api/schemaChange', async () => {
  const actual = await vi.importActual<typeof import('@/api/schemaChange')>('@/api/schemaChange');
  return {
    schemaChangeKeys: actual.schemaChangeKeys,
    listSchemaChangeSets: api.listSchemaChangeSets,
    listSchemaChangePipelines: api.listSchemaChangePipelines,
    createSchemaChangeSet: api.createSchemaChangeSet,
    getSchemaChangeLadder: api.getSchemaChangeLadder,
  };
});

const Page = (await import('./SchemaChangeSetListPage')).default;

const set: SchemaChangeSet = {
  id: 's-1',
  pipeline_id: 'p-1',
  name: 'orders-archive',
  status: 'ACTIVE',
  created_at: '2026-09-22T10:00:00Z',
  updated_at: '2026-09-22T10:00:00Z',
  statements: [{ id: 'st-1', sequence_order: 0, sql_text: 'ALTER TABLE t ADD c INT', query_type: 'DDL', created_at: 'x' }],
};

const page = (content: SchemaChangeSet[]) => ({
  content,
  page: 0,
  size: 20,
  total_elements: content.length,
  total_pages: content.length ? 1 : 0,
});

function problem(status: number, data: unknown) {
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: 'x',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data,
  });
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter>
          <Page />
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>,
  );
}

describe('SchemaChangeSetListPage', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.listSchemaChangePipelines.mockResolvedValue([
      { id: 'p-1', name: 'orders-pipeline', active: true, environments: [] },
    ]);
    api.getSchemaChangeLadder.mockResolvedValue({
      change_set_id: 's-1',
      pipeline_id: 'p-1',
      rungs: [
        { environment_id: 'e-1', environment_name: 'dev', sort_order: 0, datasource_id: 'd', state: 'APPLIED' },
        {
          environment_id: 'e-2',
          environment_name: 'prod',
          sort_order: 1,
          datasource_id: 'd',
          state: 'BLOCKED',
          blocker: 'FREEZE_ACTIVE',
          freeze_behavior: 'REJECT',
        },
      ],
    });
  });

  it('shows a skeleton while loading', () => {
    api.listSchemaChangeSets.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId('schema-change-list-loading')).toBeTruthy();
  });

  it('shows the server detail when the list fails', async () => {
    api.listSchemaChangeSets.mockRejectedValue(problem(500, { detail: 'Database unavailable' }));
    renderPage();
    expect(await screen.findByText('Database unavailable')).toBeTruthy();
  });

  it('shows the empty state with a create action', async () => {
    api.listSchemaChangeSets.mockResolvedValue(page([]));
    renderPage();
    expect(await screen.findByText('No schema change sets yet')).toBeTruthy();
  });

  it('renders rows with pipeline name, statement count and a ladder strip', async () => {
    api.listSchemaChangeSets.mockResolvedValue(page([set]));
    renderPage();
    expect(await screen.findByText('orders-archive')).toBeTruthy();
    expect(await screen.findByText('orders-pipeline', { selector: 'td' })).toBeTruthy();
    expect(await screen.findByLabelText('dev: Applied')).toBeTruthy();
    expect(screen.getByLabelText('prod: Blocked')).toBeTruthy();
    fireEvent.click(screen.getByText('orders-archive'));
    expect(api.navigate).toHaveBeenCalledWith('/schema-change-sets/s-1');
  });

  it('filters by status and says so when nothing matches', async () => {
    api.listSchemaChangeSets.mockResolvedValueOnce(page([set])).mockResolvedValue(page([]));
    renderPage();
    await screen.findByText('orders-archive');
    fireEvent.mouseDown(screen.getByLabelText('Status'));
    fireEvent.click(await screen.findByTitle('Archived'));
    expect(await screen.findByText('No change sets match these filters')).toBeTruthy();
    expect(api.listSchemaChangeSets).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ARCHIVED' }));
  });

  it('validates and creates a change set, then opens it', async () => {
    api.listSchemaChangeSets.mockResolvedValue(page([set]));
    api.createSchemaChangeSet.mockResolvedValue({ ...set, id: 's-2' });
    renderPage();
    await screen.findByText('orders-archive');
    fireEvent.click(screen.getByText('New change set'));
    fireEvent.click(await screen.findByText('Create'));
    expect(await screen.findByText('Choose a pipeline')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'ab' } });
    expect(await screen.findByText('Use between 3 and 255 characters')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '  orders-v2  ' } });
    fireEvent.mouseDown(screen.getByLabelText('Pipeline', { selector: 'input#schema-change-create_pipeline_id' }));
    const options = await screen.findAllByTitle('orders-pipeline');
    fireEvent.click(options[options.length - 1] as HTMLElement);
    fireEvent.click(screen.getByText('Create'));

    await waitFor(() =>
      expect(api.createSchemaChangeSet).toHaveBeenCalledWith(
        { pipeline_id: 'p-1', name: 'orders-v2', description: null },
        expect.anything(),
      ),
    );
    await waitFor(() => expect(api.navigate).toHaveBeenCalledWith('/schema-change-sets/s-2'));
  });

  it('surfaces the server detail when creation is refused', async () => {
    api.listSchemaChangeSets.mockResolvedValue(page([]));
    api.createSchemaChangeSet.mockRejectedValue(
      problem(409, { detail: 'A change set named orders-v2 already exists under this pipeline' }),
    );
    renderPage();
    fireEvent.click((await screen.findAllByText('New change set'))[0] as HTMLElement);
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'orders-v2' } });
    fireEvent.mouseDown(screen.getByLabelText('Pipeline', { selector: 'input#schema-change-create_pipeline_id' }));
    const options = await screen.findAllByTitle('orders-pipeline');
    fireEvent.click(options[options.length - 1] as HTMLElement);
    fireEvent.click(screen.getByText('Create'));
    expect(
      await screen.findByText('A change set named orders-v2 already exists under this pipeline'),
    ).toBeTruthy();
  });
});
