import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import i18n from '@/i18n';

const { useSchemaIntrospectMock } = vi.hoisted(() => ({
  useSchemaIntrospectMock: vi.fn(),
}));

vi.mock('@/hooks/useSchemaIntrospect', () => ({
  useSchemaIntrospect: useSchemaIntrospectMock,
}));

vi.mock('./ErDiagram', () => ({
  ErDiagram: ({ helpText }: { helpText?: string }) => (
    <div data-testid="er-diagram-rendered">{helpText}</div>
  ),
}));

import { ErDiagramTab } from './ErDiagramTab';

function renderTab() {
  return render(
    <I18nextProvider i18n={i18n}>
      <ErDiagramTab dsId="ds-1" />
    </I18nextProvider>,
  );
}

describe('ErDiagramTab', () => {
  beforeEach(() => {
    useSchemaIntrospectMock.mockReset();
  });

  it('renders a sized skeleton while the schema is loading', () => {
    useSchemaIntrospectMock.mockReturnValue({ isLoading: true, isError: false, data: undefined });
    renderTab();
    expect(screen.getByTestId('er-diagram-skeleton')).toBeInTheDocument();
  });

  it('renders an error empty state when introspection fails', () => {
    useSchemaIntrospectMock.mockReturnValue({ isLoading: false, isError: true, data: undefined });
    renderTab();
    expect(screen.getByText('Failed to load schema')).toBeInTheDocument();
  });

  it('renders the empty state when no foreign keys exist', () => {
    useSchemaIntrospectMock.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        schemas: [
          {
            name: 'public',
            tables: [
              {
                name: 'users',
                columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
                foreign_keys: [],
              },
            ],
          },
        ],
      },
    });
    renderTab();
    expect(screen.getByText('No foreign keys found')).toBeInTheDocument();
    expect(screen.queryByTestId('er-diagram-rendered')).not.toBeInTheDocument();
  });

  it('renders the ER diagram when at least one foreign key is present', () => {
    useSchemaIntrospectMock.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        schemas: [
          {
            name: 'public',
            tables: [
              {
                name: 'orders',
                columns: [{ name: 'user_id', type: 'uuid', nullable: false, primary_key: false }],
                foreign_keys: [
                  { from_column: 'user_id', to_table: 'users', to_column: 'id' },
                ],
              },
              {
                name: 'users',
                columns: [{ name: 'id', type: 'uuid', nullable: false, primary_key: true }],
                foreign_keys: [],
              },
            ],
          },
        ],
      },
    });
    renderTab();
    const rendered = screen.getByTestId('er-diagram-rendered');
    expect(rendered).toBeInTheDocument();
    expect(rendered).toHaveTextContent(
      'Click a table to highlight its relationships. Click the background to clear.',
    );
  });
});
