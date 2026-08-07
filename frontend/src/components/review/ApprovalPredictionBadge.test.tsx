import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { ApprovalPredictionBadge } from './ApprovalPredictionBadge';

describe('ApprovalPredictionBadge', () => {
  it('renders the probability as a rounded percentage', () => {
    render(<ApprovalPredictionBadge probability={0.784} />);
    expect(screen.getByTestId('approval-prediction-badge')).toHaveTextContent('78%');
  });

  it('renders nothing when the probability is null', () => {
    const { container } = render(<ApprovalPredictionBadge probability={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the probability is absent', () => {
    const { container } = render(<ApprovalPredictionBadge />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders a zero probability rather than treating it as absent', () => {
    render(<ApprovalPredictionBadge probability={0} size="sm" />);
    const badge = screen.getByTestId('approval-prediction-badge');
    expect(badge).toHaveTextContent('0%');
    expect(badge).toHaveClass('af-pill-sm');
  });

  it('uses neutral theme tokens rather than the risk palette', () => {
    render(<ApprovalPredictionBadge probability={0.5} />);
    const badge = screen.getByTestId('approval-prediction-badge');
    expect(badge.getAttribute('style')).toContain('var(--fg-muted)');
    expect(badge.getAttribute('style')).not.toContain('--risk');
  });
});
