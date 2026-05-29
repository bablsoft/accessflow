import { describe, expect, it, vi, beforeEach } from 'vitest';
import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';

const { createMock } = vi.hoisted(() => ({ createMock: vi.fn() }));

vi.mock('@/api/queryTemplates', () => ({
  createQueryTemplate: createMock,
  queryTemplateKeys: {
    all: ['queryTemplates'] as const,
    lists: () => ['queryTemplates', 'list'] as const,
    list: () => ['queryTemplates', 'list', {}] as const,
    detail: (id: string) => ['queryTemplates', 'detail', id] as const,
  },
}));

import { SaveTemplateModal } from './SaveTemplateModal';

function withProviders(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{ui}</App>
    </QueryClientProvider>
  );
}

describe('SaveTemplateModal', () => {
  beforeEach(() => {
    createMock.mockReset();
  });

  it('submits with the current SQL, owner-private visibility default, and pinned datasource', async () => {
    createMock.mockResolvedValue({});
    const onClose = vi.fn();

    render(
      withProviders(
        <SaveTemplateModal
          open
          sql="SELECT 1"
          datasourceId="ds-1"
          onClose={onClose}
        />,
      ),
    );

    fireEvent.change(screen.getByLabelText('Name'), {
      target: { value: 'Top users' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Top users',
          body: 'SELECT 1',
          visibility: 'PRIVATE',
          datasource_id: 'ds-1',
        }),
      );
    });
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('refuses to submit without a name', async () => {
    createMock.mockResolvedValue({});

    render(
      withProviders(
        <SaveTemplateModal
          open
          sql="SELECT 1"
          datasourceId="ds-1"
          onClose={() => {}}
        />,
      ),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      expect(screen.getByText('Template name is required')).toBeInTheDocument();
    });
    expect(createMock).not.toHaveBeenCalled();
  });
});
