import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import '@/i18n';
import type { AuthUser } from '@/api/auth';
import { SYSTEM_ROLE_PERMISSIONS } from '@/mocks/systemRolePermissions';

const {
  listPendingReviewsMock,
  listPendingApiReviewsMock,
  listDeploymentReviewsMock,
  listDeploymentRollbackReviewsMock,
  listApiConnectorsMock,
} = vi.hoisted(() => ({
  listPendingReviewsMock: vi.fn(),
  listPendingApiReviewsMock: vi.fn(),
  listDeploymentReviewsMock: vi.fn(),
  listDeploymentRollbackReviewsMock: vi.fn(),
  listApiConnectorsMock: vi.fn(),
}));

vi.mock('@/api/reviews', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviews')>('@/api/reviews');
  return { ...actual, listPendingReviews: listPendingReviewsMock };
});
vi.mock('@/api/apiRequests', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiRequests')>('@/api/apiRequests');
  return { ...actual, listPendingApiReviews: listPendingApiReviewsMock };
});
vi.mock('@/api/apiConnectors', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return { ...actual, listApiConnectors: listApiConnectorsMock };
});
vi.mock('@/api/deploymentReviews', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/deploymentReviews')>('@/api/deploymentReviews');
  return {
    ...actual,
    listDeploymentReviews: listDeploymentReviewsMock,
    listDeploymentRollbackReviews: listDeploymentRollbackReviewsMock,
  };
});
vi.mock('@/components/review/PushApprovalsToggle', () => ({
  PushApprovalsToggle: () => <button type="button">push-toggle-mock</button>,
}));

const { useAuthStore } = await import('@/store/authStore');
const { ReviewHubPage } = await import('./ReviewHubPage');

function user(permissions: string[]): AuthUser {
  return {
    id: 'u-reviewer',
    email: 'reviewer@example.com',
    display_name: 'Rev',
    role: 'REVIEWER',
    role_id: null,
    permissions,
    auth_provider: 'LOCAL',
    totp_enabled: false,
    platform_admin: false,
    preferred_language: 'en',
  };
}

function page(total: number) {
  return { content: [], page: 0, size: 1, total_elements: total, total_pages: total > 0 ? 1 : 0 };
}

function LocationProbe() {
  const location = useLocation();
  return (
    <div data-testid="location">
      {location.pathname}
      {location.search}
    </div>
  );
}

function renderHub(url: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <AntdApp>
          <Routes>
            <Route
              path="/reviews"
              element={
                <>
                  <ReviewHubPage />
                  <LocationProbe />
                </>
              }
            />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const tabNames = () => screen.getAllByRole('tab').map((el) => el.textContent);

describe('ReviewHubPage (#772)', () => {
  beforeEach(() => {
    listPendingReviewsMock.mockReset().mockResolvedValue(page(3));
    listPendingApiReviewsMock.mockReset().mockResolvedValue(page(2));
    listDeploymentReviewsMock.mockReset().mockResolvedValue(page(1));
    listDeploymentRollbackReviewsMock.mockReset().mockResolvedValue(page(4));
    listApiConnectorsMock.mockReset().mockResolvedValue({
      content: [],
      page: 0,
      size: 100,
      total_elements: 0,
      total_pages: 0,
    });
    useAuthStore.setState({
      user: user(SYSTEM_ROLE_PERMISSIONS.REVIEWER),
      accessToken: 'token',
    });
  });

  it('shows every tab with its pending count for a user holding all three review permissions', async () => {
    renderHub('/reviews');
    expect(screen.getByRole('heading', { name: 'Review queue' })).toBeInTheDocument();
    expect(await screen.findByRole('tab', { name: 'Queries · 3' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'API requests · 2' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Deployments · 1' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Rollbacks · 4' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Queries · 3' })).toHaveAttribute('aria-selected', 'true');
    // A bare /reviews is left alone — the URL is not rewritten to ?tab=queries.
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/reviews$/);
    // The push-approvals toggle belongs to the Queries tab.
    expect(screen.getByRole('button', { name: 'push-toggle-mock' })).toBeInTheDocument();
  });

  it('renders only the Queries tab for a QUERY_REVIEW-only user', async () => {
    useAuthStore.setState({ user: user(['QUERY_SUBMIT_SELECT', 'QUERY_REVIEW']), accessToken: 't' });
    renderHub('/reviews');
    await screen.findByRole('tab', { name: 'Queries · 3' });
    expect(tabNames()).toEqual(['Queries · 3']);
    expect(listPendingApiReviewsMock).not.toHaveBeenCalled();
    expect(listDeploymentReviewsMock).not.toHaveBeenCalled();
  });

  it('renders the two deployment tabs, defaulting to Deployments, for a DEPLOYMENT_REVIEW-only user', async () => {
    useAuthStore.setState({ user: user(['DEPLOYMENT_REVIEW']), accessToken: 't' });
    renderHub('/reviews');
    await screen.findByRole('tab', { name: 'Deployments · 1' });
    expect(tabNames()).toEqual(['Deployments · 1', 'Rollbacks · 4']);
    expect(screen.getByRole('tab', { name: 'Deployments · 1' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    // No query-only header actions on a non-query tab.
    expect(screen.queryByRole('button', { name: 'push-toggle-mock' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Refresh/ })).not.toBeInTheDocument();
    // The Deployments tab body mounts (lazily) and lists the queue with its own page size.
    expect(await screen.findByText('No deployments waiting for review')).toBeInTheDocument();
    expect(listDeploymentReviewsMock).toHaveBeenCalledWith({
      pipeline_id: undefined,
      page: 0,
      size: 20,
    });
  });

  it('mounts the API tab for ?tab=api and hides the query-only header actions', async () => {
    renderHub('/reviews?tab=api');
    expect(await screen.findByRole('tab', { name: 'API requests · 2' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(await screen.findByPlaceholderText('Search by connector, path')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'push-toggle-mock' })).not.toBeInTheDocument();
    // Only the count probe and the tab's own page were requested — never the other queues' pages.
    expect(listDeploymentReviewsMock).toHaveBeenCalledTimes(1);
    expect(listDeploymentReviewsMock).toHaveBeenCalledWith({ size: 1 });
  });

  it('replaces an unpermitted ?tab= with the first tab the viewer may see', async () => {
    useAuthStore.setState({ user: user(['API_REQUEST_REVIEW']), accessToken: 't' });
    renderHub('/reviews?tab=rollbacks');
    await screen.findByRole('tab', { name: 'API requests · 2' });
    expect(screen.getByTestId('location')).toHaveTextContent('/reviews?tab=api');
    expect(tabNames()).toEqual(['API requests · 2']);
  });

  it('replaces an unknown ?tab= with the first visible tab', async () => {
    renderHub('/reviews?tab=bogus');
    await screen.findByRole('tab', { name: 'Queries · 3' });
    expect(screen.getByTestId('location')).toHaveTextContent('/reviews?tab=queries');
  });

  it('switching tabs updates ?tab= and mounts only the selected queue', async () => {
    renderHub('/reviews?tab=deployments');
    await screen.findByText('No deployments waiting for review');
    expect(listDeploymentRollbackReviewsMock).toHaveBeenCalledTimes(1); // the count probe only

    await act(async () => {
      fireEvent.click(screen.getByRole('tab', { name: 'Rollbacks · 4' }));
    });

    expect(screen.getByTestId('location')).toHaveTextContent('/reviews?tab=rollbacks');
    expect(await screen.findByText('No rollback reviews')).toBeInTheDocument();
    expect(listDeploymentRollbackReviewsMock).toHaveBeenCalledWith({
      status: 'PENDING_REVIEW',
      page: 0,
      size: 20,
    });
    // The pending-deployments body is unmounted, so its empty state is gone from the DOM.
    expect(screen.queryByText('No deployments waiting for review')).not.toBeInTheDocument();
  });

  it('returns nothing for a user holding no review permission at all', () => {
    useAuthStore.setState({ user: user(['QUERY_SUBMIT_SELECT']), accessToken: 't' });
    const { container } = renderHub('/reviews');
    expect(container.querySelector('.af-page-header')).toBeNull();
    expect(screen.queryAllByRole('tab')).toHaveLength(0);
  });
});
