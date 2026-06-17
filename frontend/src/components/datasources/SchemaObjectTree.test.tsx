import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import i18n from '@/i18n';
import { SchemaObjectTree } from './SchemaObjectTree';
import type { SchemaNamespace } from '@/types/api';

const schemas: SchemaNamespace[] = [
  {
    name: 'public',
    tables: [
      {
        name: 'users',
        columns: [
          { name: 'id', type: 'uuid', nullable: false, primary_key: true },
          { name: 'email', type: 'varchar', nullable: false, primary_key: false },
        ],
        foreign_keys: [],
      },
      {
        name: 'orders',
        columns: [{ name: 'total', type: 'numeric', nullable: false, primary_key: false }],
        foreign_keys: [],
      },
    ],
  },
];

function renderTree(onPreview = vi.fn()) {
  render(
    <I18nextProvider i18n={i18n}>
      <SchemaObjectTree schemas={schemas} onPreview={onPreview} />
    </I18nextProvider>,
  );
  return onPreview;
}

describe('SchemaObjectTree', () => {
  it('lists all tables when unfiltered', () => {
    renderTree();
    expect(screen.getByText('users')).toBeInTheDocument();
    expect(screen.getByText('orders')).toBeInTheDocument();
  });

  it('filters tables by a column-name query across the hierarchy', () => {
    renderTree();
    fireEvent.change(screen.getByPlaceholderText(/filter/i), { target: { value: 'email' } });
    expect(screen.getByText('users')).toBeInTheDocument();
    expect(screen.queryByText('orders')).not.toBeInTheDocument();
  });

  it('shows an empty message when nothing matches', () => {
    renderTree();
    fireEvent.change(screen.getByPlaceholderText(/filter/i), { target: { value: 'zzz' } });
    expect(screen.queryByText('users')).not.toBeInTheDocument();
    expect(screen.getByText(/no objects match/i)).toBeInTheDocument();
  });

  it('invokes onPreview with the schema and table when the preview action is clicked', () => {
    const onPreview = renderTree();
    const previewButtons = screen.getAllByRole('button', { name: /preview data/i });
    fireEvent.click(previewButtons[0]!);
    expect(onPreview).toHaveBeenCalledWith('public', 'users');
  });
});
