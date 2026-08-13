import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { EmptyState } from '../EmptyState';

describe('EmptyState', () => {
  it('renders title, description and action at the default size', () => {
    render(
      <EmptyState
        title="Nothing here"
        description="Try adding something."
        action={<button type="button">Add</button>}
      />,
    );
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
    expect(screen.getByText('Try adding something.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
  });

  it('renders the compact variant with reduced padding', () => {
    const { container } = render(<EmptyState title="Empty" size="sm" />);
    const root = container.firstElementChild as HTMLElement;
    expect(root.style.padding).toBe('20px 16px');
  });

  it('renders the roomy default padding when size is omitted', () => {
    const { container } = render(<EmptyState title="Empty" />);
    const root = container.firstElementChild as HTMLElement;
    expect(root.style.padding).toBe('60px 24px');
  });
});
