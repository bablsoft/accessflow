import { describe, expect, it, vi, beforeEach } from 'vitest';
import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { QueryTemplate, QueryTemplatePage } from '@/types/api';

const { listMock, deleteMock } = vi.hoisted(() => ({
  listMock: vi.fn(),
  deleteMock: vi.fn(),
}));

vi.mock('@/api/queryTemplates', () => ({
  listQueryTemplates: listMock,
  deleteQueryTemplate: deleteMock,
  queryTemplateKeys: {
    all: ['queryTemplates'] as const,
    lists: () => ['queryTemplates', 'list'] as const,
    list: (filters: unknown) => ['queryTemplates', 'list', filters] as const,
    detail: (id: string) => ['queryTemplates', 'detail', id] as const,
  },
}));

import { QueryTemplatesDrawer } from './QueryTemplatesDrawer';

function withProviders(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{ui}</App>
    </QueryClientProvider>
  );
}

function tpl(over: Partial<QueryTemplate>): QueryTemplate {
  return {
    id: 't-1',
    organization_id: 'org-1',
    owner_id: 'u-1',
    owner_display_name: 'Alice',
    datasource_id: 'ds-1',
    name: 'Top users',
    body: 'SELECT 1',
    description: null,
    tags: [],
    visibility: 'PRIVATE',
    editable: true,
    created_at: '2026-05-01T00:00:00Z',
    updated_at: '2026-05-01T00:00:00Z',
    ...over,
  };
}

function page(items: QueryTemplate[]): QueryTemplatePage {
  return {
    content: items,
    page: 0,
    size: 25,
    total_elements: items.length,
    total_pages: 1,
  };
}

describe('QueryTemplatesDrawer', () => {
  beforeEach(() => {
    listMock.mockReset();
    deleteMock.mockReset();
  });

  it('lists templates and triggers onOpen when Open is clicked', async () => {
    const template = tpl({ name: 'Test query' });
    listMock.mockResolvedValue(page([template]));
    const onOpen = vi.fn();

    render(
      withProviders(
        <QueryTemplatesDrawer
          open
          currentDatasourceId="ds-1"
          onClose={() => {}}
          onOpen={onOpen}
        />,
      ),
    );

    expect(await screen.findByText('Test query')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(onOpen).toHaveBeenCalledWith(template);
  });

  it('shows the empty state when there are no templates', async () => {
    listMock.mockResolvedValue(page([]));

    render(
      withProviders(
        <QueryTemplatesDrawer
          open
          currentDatasourceId={null}
          onClose={() => {}}
          onOpen={() => {}}
        />,
      ),
    );

    await waitFor(() => {
      expect(
        screen.getByText(/No templates yet/i),
      ).toBeInTheDocument();
    });
  });

  it('hides the Delete button on non-editable templates', async () => {
    listMock.mockResolvedValue(page([tpl({ editable: false, name: 'Shared' })]));

    render(
      withProviders(
        <QueryTemplatesDrawer
          open
          currentDatasourceId={null}
          onClose={() => {}}
          onOpen={() => {}}
        />,
      ),
    );

    await screen.findByText('Shared');
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });
});
