import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { PendingDeploymentApprovalsWidget } from './PendingDeploymentApprovalsWidget';
import type { DashboardPendingDeploymentApproval } from '@/types/api';

function item(
  overrides: Partial<DashboardPendingDeploymentApproval> = {},
): DashboardPendingDeploymentApproval {
  return {
    deployment_request_id: 'd-1',
    pipeline_id: 'p-1',
    pipeline_name: 'Checkout',
    environment_id: 'e-1',
    environment_name: 'prod',
    submitted_by_user_id: 'u-9',
    version: '1.4.3',
    ai_risk_level: 'HIGH',
    ai_risk_score: 88,
    current_stage: 1,
    created_at: '2026-06-20T15:00:00Z',
    ...overrides,
  };
}

function renderWidget(props: Partial<Parameters<typeof PendingDeploymentApprovalsWidget>[0]> = {}) {
  return render(
    <MemoryRouter>
      <PendingDeploymentApprovalsWidget items={[item()]} loading={false} {...props} />
    </MemoryRouter>,
  );
}

describe('PendingDeploymentApprovalsWidget (#926)', () => {
  it('renders the version, pipeline, environment and risk of each pending deployment', () => {
    renderWidget();
    expect(screen.getByText('1.4.3')).toBeInTheDocument();
    expect(screen.getByText('Checkout')).toBeInTheDocument();
    expect(screen.getByText('prod')).toBeInTheDocument();
    expect(screen.getByText(/88/)).toBeInTheDocument();
  });

  it('links each row to the deployment and the footer to the deployments review tab', () => {
    renderWidget();
    expect(screen.getByRole('link', { name: 'Review' })).toHaveAttribute(
      'href',
      '/deployments/d-1',
    );
    expect(screen.getByRole('link', { name: 'View all' })).toHaveAttribute(
      'href',
      '/reviews?tab=deployments',
    );
  });

  it('renders an em dash for a pipeline or environment the payload left null', () => {
    renderWidget({ items: [item({ pipeline_name: null, environment_name: null })] });
    expect(screen.getAllByText('—')).toHaveLength(2);
  });

  it('shows the empty state when nothing is pending', () => {
    renderWidget({ items: [] });
    expect(screen.getByText('No deployments awaiting your review')).toBeInTheDocument();
  });

  it('shows a skeleton while loading and the error state on failure', () => {
    const { container, rerender } = renderWidget({ items: [], loading: true });
    expect(container.querySelector('.ant-skeleton')).not.toBeNull();

    const onRetry = vi.fn();
    rerender(
      <MemoryRouter>
        <PendingDeploymentApprovalsWidget
          items={[]}
          loading={false}
          error={new Error('boom')}
          onRetry={onRetry}
        />
      </MemoryRouter>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }));
    expect(onRetry).toHaveBeenCalled();
  });
});
