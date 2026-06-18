import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { QueryDryRunResult } from '@/types/api';
import { DryRunPanel } from './DryRunPanel';

describe('DryRunPanel', () => {
  it('shows the empty prompt when there is no result', () => {
    render(<DryRunPanel running={false} result={null} />);
    expect(screen.getByText(/run a dry run to preview/i)).toBeInTheDocument();
  });

  it('renders the localized unsupported reason for engines without a plan', () => {
    const result: QueryDryRunResult = {
      supported: false,
      engine_id: 'redis',
      unsupported_reason: 'Dry-run is not supported for the redis engine',
      duration_ms: 0,
    };
    render(<DryRunPanel running={false} result={result} />);
    expect(screen.getByText(/not supported for the redis engine/i)).toBeInTheDocument();
  });

  it('renders the estimated impact and plan tree for a supported result', () => {
    const result: QueryDryRunResult = {
      supported: true,
      engine_id: 'postgresql',
      query_type: 'SELECT',
      estimated_rows: 1000,
      plan: { operation: 'Seq Scan', target: 'users', estimated_rows: 1000, children: [] },
      duration_ms: 12,
    };
    render(<DryRunPanel running={false} result={result} />);
    expect(screen.getByText('Seq Scan')).toBeInTheDocument();
    expect(screen.getByText('1,000')).toBeInTheDocument();
  });

  it('falls back to the raw plan when no structured plan is present', () => {
    const result: QueryDryRunResult = {
      supported: true,
      engine_id: 'elasticsearch',
      query_type: 'SELECT',
      plan: null,
      raw_plan: 'user.email:acme',
      duration_ms: 3,
    };
    render(<DryRunPanel running={false} result={result} />);
    expect(screen.getByText('user.email:acme')).toBeInTheDocument();
  });
});
