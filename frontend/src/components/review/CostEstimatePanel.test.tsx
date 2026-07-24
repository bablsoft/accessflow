import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { CostEstimateDetail } from '@/types/api';
import { CostEstimatePanel } from './CostEstimatePanel';

function estimate(overrides: Partial<CostEstimateDetail> = {}): CostEstimateDetail {
  return {
    id: 'e1',
    engine_id: 'postgresql',
    query_type: 'DELETE',
    supported: true,
    estimated_rows: 2400000,
    affected_row_count: null,
    scan_type: 'Seq Scan',
    estimated_cost: 44543.5,
    plan: { operation: 'Seq Scan', target: 'users', estimated_rows: 2400000, children: [] },
    raw_plan: null,
    unsupported_reason: null,
    failed: false,
    error_message: null,
    duration_ms: 12,
    ...overrides,
  };
}

describe('CostEstimatePanel', () => {
  it('shows the pending text while the query is still in PENDING_AI', () => {
    render(<CostEstimatePanel estimate={null} status="PENDING_AI" />);
    expect(screen.getByText(/computing the cost estimate/i)).toBeInTheDocument();
  });

  it('shows the unavailable fallback when no estimate exists past PENDING_AI', () => {
    render(<CostEstimatePanel estimate={null} status="PENDING_REVIEW" />);
    expect(screen.getByText(/no cost estimate is available/i)).toBeInTheDocument();
  });

  it('renders the failure state with the error message', () => {
    render(
      <CostEstimatePanel
        estimate={estimate({ supported: false, failed: true, error_message: 'timeout' })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByText(/cost estimation failed/i)).toBeInTheDocument();
    expect(screen.getByText(/timeout/)).toBeInTheDocument();
  });

  it('renders the unsupported reason for engines without a plan', () => {
    render(
      <CostEstimatePanel
        estimate={estimate({
          supported: false,
          plan: null,
          unsupported_reason: 'Dry-run is not supported for the redis engine',
        })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByText(/not supported for the redis engine/i)).toBeInTheDocument();
  });

  it('renders estimated rows, scan type, cost, and the plan tree', () => {
    render(<CostEstimatePanel estimate={estimate()} status="PENDING_REVIEW" />);
    expect(screen.getAllByText('Seq Scan').length).toBeGreaterThan(0);
    expect(screen.getByText('2,400,000')).toBeInTheDocument();
    expect(screen.getByText('44,543.5')).toBeInTheDocument();
  });

  it('renders the exact affected-row count for writes when present', () => {
    render(
      <CostEstimatePanel
        estimate={estimate({ affected_row_count: 90 })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByText(/affected rows \(exact\)/i)).toBeInTheDocument();
    expect(screen.getByText('90')).toBeInTheDocument();
  });

  it('shows the affected-row count even when the plan itself is unsupported', () => {
    render(
      <CostEstimatePanel
        estimate={estimate({
          supported: false,
          plan: null,
          estimated_rows: null,
          scan_type: null,
          estimated_cost: null,
          affected_row_count: 7,
        })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('falls back to the raw plan when no structured plan is present', () => {
    render(
      <CostEstimatePanel
        estimate={estimate({ plan: null, raw_plan: 'Seq Scan on users' })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByText('Seq Scan on users')).toBeInTheDocument();
  });
});
