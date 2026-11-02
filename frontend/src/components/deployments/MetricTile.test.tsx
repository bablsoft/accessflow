import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MetricTile } from './MetricTile';

describe('MetricTile', () => {
  it('renders the label, value and caption', () => {
    render(<MetricTile label="Lead time" value="1h 12m" testId="tile" caption={<span>p95 3h</span>} />);
    expect(screen.getByText('Lead time')).toBeInTheDocument();
    expect(screen.getByTestId('tile-value')).toHaveTextContent('1h 12m');
    expect(screen.getByText('p95 3h')).toBeInTheDocument();
  });

  it('omits the caption row when there is none', () => {
    const { container } = render(<MetricTile label="Break-glass" value={2} testId="bg" />);
    expect(screen.getByTestId('bg-value')).toHaveTextContent('2');
    expect(container.querySelectorAll('.muted')).toHaveLength(1);
  });
});
