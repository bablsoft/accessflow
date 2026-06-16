import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { OptimizationSuggestion } from '@/types/api';
import '@/i18n';

const { OptimizationCard } = await import('./OptimizationCard');

const indexSuggestion: OptimizationSuggestion = {
  type: 'INDEX',
  title: 'Add index on users(email)',
  rationale: 'The WHERE clause filters on email.',
  sql: 'CREATE INDEX idx_users_email ON users(email)',
};

function wrap(node: ReactNode) {
  return <>{node}</>;
}

describe('OptimizationCard', () => {
  it('shows the type label and title, and reveals rationale + SQL on expand', () => {
    render(wrap(<OptimizationCard optimization={indexSuggestion} />));

    expect(screen.getByText('Index')).toBeInTheDocument();
    expect(screen.getByText('Add index on users(email)')).toBeInTheDocument();
    // Collapsed: rationale and SQL are not yet rendered.
    expect(screen.queryByText('The WHERE clause filters on email.')).toBeNull();

    fireEvent.click(screen.getByText('Add index on users(email)'));

    expect(screen.getByText('The WHERE clause filters on email.')).toBeInTheDocument();
    expect(screen.getByText('CREATE INDEX idx_users_email ON users(email)')).toBeInTheDocument();
  });

  it('calls onApply with the suggestion SQL when Apply is clicked', () => {
    const onApply = vi.fn();
    render(wrap(<OptimizationCard optimization={indexSuggestion} onApply={onApply} />));

    fireEvent.click(screen.getByText('Add index on users(email)'));
    fireEvent.click(screen.getByRole('button', { name: /Apply as draft/i }));

    expect(onApply).toHaveBeenCalledWith('CREATE INDEX idx_users_email ON users(email)');
  });

  it('hides the Apply button when onApply is not provided', () => {
    render(wrap(<OptimizationCard optimization={indexSuggestion} />));

    fireEvent.click(screen.getByText('Add index on users(email)'));

    expect(screen.queryByRole('button', { name: /Apply as draft/i })).toBeNull();
  });
});
