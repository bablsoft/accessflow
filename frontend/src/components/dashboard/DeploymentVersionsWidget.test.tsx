import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type {
  DeploymentEnvironmentVersion,
  DeploymentEnvironmentVersionPage,
} from '@/types/api';

const { listVersions } = vi.hoisted(() => ({ listVersions: vi.fn() }));

vi.mock('@/api/deploymentVersions', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/deploymentVersions')>('@/api/deploymentVersions');
  return { ...actual, listDeploymentEnvironmentVersions: listVersions };
});

const { DeploymentVersionsWidget } = await import('./DeploymentVersionsWidget');

function row(overrides: Partial<DeploymentEnvironmentVersion> = {}): DeploymentEnvironmentVersion {
  return {
    pipeline_id: 'p-1',
    pipeline_name: 'Checkout',
    environment: { id: 'e-1', name: 'staging', tags: [], sort_order: 0 },
    current_version: '1.4.0',
    current_request_id: 'd-1',
    deployed_at: '2026-06-18T10:00:00Z',
    previous_version: '1.3.9',
    last_outcome: 'SUCCEEDED',
    drift: {
      latest_version: '1.4.2',
      latest_deployed_at: '2026-06-20T10:00:00Z',
      drifted: true,
      days_behind: 2,
      deployments_behind: 1,
    },
    ...overrides,
  };
}

function page(content: DeploymentEnvironmentVersion[]): DeploymentEnvironmentVersionPage {
  return { content, page: 0, size: 5, total_elements: content.length, total_pages: 1 };
}

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<DeploymentVersionsWidget />, { wrapper });
}

describe('DeploymentVersionsWidget (#926)', () => {
  beforeEach(() => {
    listVersions.mockReset();
  });

  it('asks only for the drifted environments', async () => {
    listVersions.mockResolvedValue(page([]));
    renderWidget();
    await waitFor(() =>
      expect(listVersions).toHaveBeenCalledWith({ drifted: true, size: 5 }),
    );
  });

  it('renders the pipeline, environment, running version and drift badge', async () => {
    listVersions.mockResolvedValue(page([row()]));
    renderWidget();

    expect(await screen.findByText('Checkout')).toBeInTheDocument();
    expect(screen.getByText('staging')).toBeInTheDocument();
    expect(screen.getByText('1.4.0')).toBeInTheDocument();
    expect(screen.getByText(/behind/i)).toBeInTheDocument();
  });

  it('renders an em dash for an environment with no known running version', async () => {
    listVersions.mockResolvedValue(page([row({ current_version: null })]));
    renderWidget();
    expect(await screen.findByText('—')).toBeInTheDocument();
  });

  it('shows the empty state and the link to the full matrix when nothing has drifted', async () => {
    listVersions.mockResolvedValue(page([]));
    renderWidget();
    expect(
      await screen.findByText('Every environment is on the latest version'),
    ).toBeInTheDocument();
  });

  it('links the footer to the org-wide version matrix', async () => {
    listVersions.mockResolvedValue(page([row()]));
    renderWidget();
    expect(await screen.findByRole('link', { name: 'View all' })).toHaveAttribute(
      'href',
      '/deployment-versions',
    );
  });

  it('surfaces a retryable error when the inventory read fails', async () => {
    listVersions.mockRejectedValue(new Error('boom'));
    renderWidget();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument();
  });
});
