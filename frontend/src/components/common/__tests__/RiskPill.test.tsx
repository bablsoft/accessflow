import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { RiskPill } from '../RiskPill';

describe('RiskPill', () => {
  it('renders level and score for a successful analysis', () => {
    render(<RiskPill level="MEDIUM" score={42} />);
    expect(screen.getByText(/MEDIUM/)).toBeInTheDocument();
    expect(screen.getByText(/42/)).toBeInTheDocument();
  });

  it('renders just the level when score is missing', () => {
    render(<RiskPill level="HIGH" />);
    expect(screen.getByText('HIGH')).toBeInTheDocument();
    expect(screen.queryByText(/·/)).not.toBeInTheDocument();
  });

  it('renders an "AI N/A" neutral variant when failed=true', () => {
    render(<RiskPill level="CRITICAL" score={100} failed />);
    expect(screen.getByText('AI N/A')).toBeInTheDocument();
    expect(screen.queryByText('CRITICAL')).not.toBeInTheDocument();
    expect(screen.queryByText(/100/)).not.toBeInTheDocument();
  });
});
