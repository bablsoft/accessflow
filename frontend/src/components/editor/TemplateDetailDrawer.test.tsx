import { describe, expect, it, vi, beforeEach } from 'vitest';
import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { QueryTemplate, QueryTemplateVersion, QueryTemplateVersionPage } from '@/types/api';

const { listVersionsMock, restoreMock } = vi.hoisted(() => ({
  listVersionsMock: vi.fn(),
  restoreMock: vi.fn(),
}));

vi.mock('@/api/queryTemplates', () => ({
  listTemplateVersions: listVersionsMock,
  restoreTemplateVersion: restoreMock,
  queryTemplateKeys: {
    all: ['queryTemplates'] as const,
    detail: (id: string) => ['queryTemplates', 'detail', id] as const,
    versions: (id: string) => ['queryTemplates', 'detail', id, 'versions'] as const,
  },
}));

vi.mock('./SqlEditor', () => ({ SqlEditor: () => <div data-testid="sql-editor" /> }));
vi.mock('./SqlDiffView', () => ({
  SqlDiffView: (props: { oldValue: string; newValue: string }) => (
    <div data-testid="sql-diff" data-old={props.oldValue} data-new={props.newValue} />
  ),
}));

import { TemplateDetailDrawer } from './TemplateDetailDrawer';

function withProviders(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{ui}</App>
    </QueryClientProvider>
  );
}

function tpl(over: Partial<QueryTemplate> = {}): QueryTemplate {
  return {
    id: 't-1',
    organization_id: 'org-1',
    owner_id: 'u-1',
    owner_display_name: 'Alice',
    datasource_id: null,
    name: 'Top users',
    body: 'SELECT 2',
    description: 'desc',
    tags: ['billing'],
    visibility: 'PRIVATE',
    editable: true,
    created_at: '2026-05-01T00:00:00Z',
    updated_at: '2026-05-02T00:00:00Z',
    ...over,
  };
}

function version(over: Partial<QueryTemplateVersion>): QueryTemplateVersion {
  return {
    id: 'v-1',
    template_id: 't-1',
    version_number: 1,
    datasource_id: null,
    name: 'Top users',
    body: 'SELECT 1',
    description: 'desc',
    tags: ['billing'],
    visibility: 'PRIVATE',
    change_type: 'CREATED',
    author_id: 'u-1',
    author_display_name: 'Alice',
    created_at: '2026-05-01T00:00:00Z',
    ...over,
  };
}

function page(items: QueryTemplateVersion[]): QueryTemplateVersionPage {
  return { content: items, page: 0, size: 50, total_elements: items.length, total_pages: 1 };
}

describe('TemplateDetailDrawer', () => {
  beforeEach(() => {
    listVersionsMock.mockReset();
    restoreMock.mockReset();
  });

  it('renders the diff between the two newest versions by default', async () => {
    listVersionsMock.mockResolvedValue(
      page([
        version({ id: 'v-2', version_number: 2, change_type: 'UPDATED', body: 'SELECT 2' }),
        version({ id: 'v-1', version_number: 1, change_type: 'CREATED', body: 'SELECT 1' }),
      ]),
    );

    render(
      withProviders(
        <TemplateDetailDrawer
          open
          onClose={() => {}}
          template={tpl()}
          currentDatasourceId={null}
          defaultTab="history"
        />,
      ),
    );

    const diff = await screen.findByTestId('sql-diff');
    // base (older) = v1 on the left, target (newer) = v2 on the right.
    expect(diff).toHaveAttribute('data-old', 'SELECT 1');
    expect(diff).toHaveAttribute('data-new', 'SELECT 2');
  });

  it('restores a version through the confirm flow', async () => {
    restoreMock.mockResolvedValue(tpl());
    listVersionsMock.mockResolvedValue(
      page([version({ id: 'v-1', version_number: 1, change_type: 'CREATED', body: 'SELECT 1' })]),
    );

    render(
      withProviders(
        <TemplateDetailDrawer
          open
          onClose={() => {}}
          template={tpl()}
          currentDatasourceId={null}
          defaultTab="history"
        />,
      ),
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Restore this version' }));
    fireEvent.click(await screen.findByRole('button', { name: 'OK' }));

    await waitFor(() => expect(restoreMock).toHaveBeenCalledWith('t-1', 'v-1'));
  });

  it('shows the empty state when there is no history', async () => {
    listVersionsMock.mockResolvedValue(page([]));

    render(
      withProviders(
        <TemplateDetailDrawer
          open
          onClose={() => {}}
          template={tpl()}
          currentDatasourceId={null}
          defaultTab="history"
        />,
      ),
    );

    expect(await screen.findByText(/No version history yet/i)).toBeInTheDocument();
  });
});
