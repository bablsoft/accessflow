import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';

vi.mock('@/components/deployments/DeploymentMetricsPanel', () => ({
  DeploymentMetricsPanel: ({ pipelineId }: { pipelineId?: string }) => (
    <div>panel {pipelineId ?? 'org-wide'}</div>
  ),
}));

const { default: DeploymentMetricsPage } = await import('./DeploymentMetricsPage');

describe('DeploymentMetricsPage', () => {
  it('renders the header and the org-wide panel', () => {
    render(
      <MemoryRouter>
        <DeploymentMetricsPage />
      </MemoryRouter>,
    );
    expect(screen.getByText('Deployment metrics')).toBeInTheDocument();
    expect(screen.getByText('panel org-wide')).toBeInTheDocument();
  });
});
