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
