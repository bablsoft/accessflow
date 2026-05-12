import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { ApprovalTimeline, type TimelineStage } from './ApprovalTimeline';

function dotStyleAt(container: HTMLElement, index: number): string {
  const dots = container.querySelectorAll<HTMLElement>(
    'div[style*="border-radius: 50%"]',
  );
  const dot = dots[index];
  if (!dot) throw new Error(`No dot at index ${index}`);
  return dot.getAttribute('style') ?? '';
}

describe('ApprovalTimeline', () => {
  it('renders a stage label and who-line', () => {
    const stages: TimelineStage[] = [
      { label: 'Submitted', who: 'alice', done: true },
    ];
    const { getByText } = render(<ApprovalTimeline stages={stages} />);
    expect(getByText('Submitted')).toBeInTheDocument();
    expect(getByText('alice')).toBeInTheDocument();
  });

  it('uses risk-low color for the dot of a done stage with LOW riskLevel', () => {
    const stages: TimelineStage[] = [
      { label: 'AI analysis', who: 'anthropic', done: true, riskLevel: 'LOW' },
    ];
    const { container } = render(<ApprovalTimeline stages={stages} />);
    expect(dotStyleAt(container, 0)).toContain('--risk-low');
  });

  it('uses risk-high color for the dot of a done stage with HIGH riskLevel', () => {
    const stages: TimelineStage[] = [
      { label: 'AI analysis', who: 'anthropic', done: true, riskLevel: 'HIGH' },
    ];
    const { container } = render(<ApprovalTimeline stages={stages} />);
    expect(dotStyleAt(container, 0)).toContain('--risk-high');
  });

  it('uses risk-crit color for CRITICAL riskLevel', () => {
    const stages: TimelineStage[] = [
      { label: 'AI analysis', who: 'anthropic', done: true, riskLevel: 'CRITICAL' },
    ];
    const { container } = render(<ApprovalTimeline stages={stages} />);
    expect(dotStyleAt(container, 0)).toContain('--risk-crit');
  });

  it('rejected/failed takes precedence over riskLevel', () => {
    const stages: TimelineStage[] = [
      {
        label: 'AI analysis',
        who: 'anthropic',
        done: true,
        rejected: true,
        riskLevel: 'LOW',
      },
    ];
    const { container } = render(<ApprovalTimeline stages={stages} />);
    expect(dotStyleAt(container, 0)).toContain('--risk-crit');
  });

  it('falls back to risk-low when no riskLevel is supplied on a done stage', () => {
    const stages: TimelineStage[] = [
      { label: 'Submitted', who: 'alice', done: true },
    ];
    const { container } = render(<ApprovalTimeline stages={stages} />);
    expect(dotStyleAt(container, 0)).toContain('--risk-low');
  });
});
