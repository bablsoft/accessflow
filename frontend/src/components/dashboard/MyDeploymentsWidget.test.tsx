import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { MyDeploymentsWidget } from './MyDeploymentsWidget';
import type { DashboardRecentDeployment } from '@/types/api';

function item(overrides: Partial<DashboardRecentDeployment> = {}): DashboardRecentDeployment {
  return {
    id: 'd-1',
    pipeline_id: 'p-1',
    pipeline_name: 'Checkout',
    environment_id: 'e-1',
    environment_name: 'prod',
    version: '1.4.2',
    status: 'EXECUTED',
    ai_risk_level: 'LOW',
    ai_risk_score: 12,
    outcome: 'SUCCEEDED',
    created_at: '2026-06-20T14:00:00Z',
    ...overrides,
  };
}

function renderWidget(props: Partial<Parameters<typeof MyDeploymentsWidget>[0]> = {}) {
  return render(
    <MemoryRouter>
      <MyDeploymentsWidget items={[item()]} loading={false} {...props} />
    </MemoryRouter>,
  );
}

describe('MyDeploymentsWidget (#926)', () => {
  it('renders the version, pipeline, environment, status and outcome', () => {
    renderWidget();
    expect(screen.getByText('1.4.2')).toBeInTheDocument();
    expect(screen.getByText('Checkout')).toBeInTheDocument();
    expect(screen.getByText('prod')).toBeInTheDocument();
    expect(screen.getByText('Executed')).toBeInTheDocument();
    expect(screen.getByText('succeeded')).toBeInTheDocument();
  });

  it('omits the outcome tag while CI has not reported back', () => {
    renderWidget({ items: [item({ status: 'PENDING_REVIEW', outcome: null })] });
    expect(screen.queryByText('succeeded')).not.toBeInTheDocument();
    expect(screen.getByText('Pending review')).toBeInTheDocument();
  });

  it('survives an outcome the payload omits entirely rather than sends as null', () => {
    // The backend runs Jackson with `default-property-inclusion: non_null`, so a null field is
    // dropped from the JSON and reaches the component as `undefined`, not `null`. A strict
    // `=== null` guard let it through to `deploymentOutcomeColor` — whose switch has no default
    // — and reading `.fg` off the resulting `undefined` unmounted the entire app, taking every
    // page down with it. Build the row the way the wire actually does.
    const { outcome: _omitted, ...withoutOutcome } = item({ status: 'PENDING_REVIEW' });
    renderWidget({ items: [withoutOutcome as DashboardRecentDeployment] });

    expect(screen.getByText('Checkout')).toBeInTheDocument();
    expect(screen.getByText('Pending review')).toBeInTheDocument();
    expect(screen.queryByText('succeeded')).not.toBeInTheDocument();
  });

  it('survives every nullable field being omitted, as the wire sends them', () => {
    const full = item({ status: 'PENDING_REVIEW' });
    const {
      outcome: _o,
      pipeline_name: _p,
      environment_name: _e,
      ai_risk_level: _rl,
      ai_risk_score: _rs,
      ...sparse
    } = full;
    renderWidget({ items: [sparse as DashboardRecentDeployment] });

    expect(screen.getByText('1.4.2')).toBeInTheDocument();
    expect(screen.getAllByText('—')).toHaveLength(2);
  });

  it('links each row to its deployment and the footer to the deployment list', () => {
    renderWidget();
    expect(screen.getByRole('link', { name: 'View' })).toHaveAttribute('href', '/deployments/d-1');
    expect(screen.getByRole('link', { name: 'View all' })).toHaveAttribute('href', '/deployments');
  });

  it('shows the empty state when the user has triggered nothing', () => {
    renderWidget({ items: [] });
    expect(screen.getByText("You haven't triggered any deployments yet")).toBeInTheDocument();
  });

  it('shows a skeleton while loading and a retryable error on failure', () => {
    const { container, rerender } = renderWidget({ items: [], loading: true });
    expect(container.querySelector('.ant-skeleton')).not.toBeNull();

    const onRetry = vi.fn();
    rerender(
      <MemoryRouter>
        <MyDeploymentsWidget
          items={[]}
          loading={false}
          error={new Error('boom')}
          onRetry={onRetry}
        />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }));
    expect(onRetry).toHaveBeenCalled();
  });
});
