import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { BreakGlassEvent, BreakGlassEventPage, DatasourcePage } from '@/types/api';

const { listBreakGlassEventsMock, acknowledgeBreakGlassEventMock, listDatasourcesMock } =
  vi.hoisted(() => ({
    listBreakGlassEventsMock: vi.fn(),
    acknowledgeBreakGlassEventMock: vi.fn(),
    listDatasourcesMock: vi.fn(),
  }));

vi.mock('@/api/breakGlass', async () => {
  const actual = await vi.importActual<typeof import('@/api/breakGlass')>('@/api/breakGlass');
  return {
    ...actual,
    listBreakGlassEvents: listBreakGlassEventsMock,
    acknowledgeBreakGlassEvent: acknowledgeBreakGlassEventMock,
  };
});

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, listDatasources: listDatasourcesMock };
});

const { default: BreakGlassLogPage } = await import('./BreakGlassLogPage');

const CURRENT_USER = 'u-me';

function event(overrides: Partial<BreakGlassEvent> = {}): BreakGlassEvent {
  return {
    id: 'bg-1',
    query_request_id: 'q-1',
    api_request_id: null,
    deployment_request_id: null,
    datasource_id: 'ds-1',
    datasource_name: 'Prod',
    connector_id: null,
    pipeline_id: null,
    submitted_by_user_id: 'u-agent',
    on_behalf_of_user_id: null,
    submitted_by_display_name: 'Agent',
    submitted_by_email: 'agent@example.com',
    sql_text: 'SELECT 1',
    execution_status: 'EXECUTED',
    justification: 'prod is down',
    status: 'PENDING_REVIEW',
    reviewed_by_user_id: null,
    reviewed_by_display_name: null,
    review_comment: null,
    reviewed_at: null,
    created_at: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function pageOf(content: BreakGlassEvent[]): BreakGlassEventPage {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

function emptyDatasources(): DatasourcePage {
  return { content: [], page: 0, size: 100, total_elements: 0, total_pages: 0, last: true };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>
          <BreakGlassLogPage />
        </App>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function acknowledgeButton(id: string) {
  await waitFor(() => expect(screen.getByTestId(`acknowledge-${id}`)).toBeInTheDocument());
  return screen.getByTestId(`acknowledge-${id}`);
}

describe('BreakGlassLogPage acknowledge action', () => {
  beforeEach(() => {
    listDatasourcesMock.mockResolvedValue(emptyDatasources());
    useAuthStore.setState({
      user: {
        id: CURRENT_USER,
        email: 'me@example.com',
        display_name: 'Me',
        role: 'ADMIN',
        role_id: null,
        permissions: [],
        auth_provider: 'LOCAL',
        totp_enabled: false,
        platform_admin: false,
        preferred_language: null,
      },
      accessToken: 'token',
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('is enabled for a pending event someone else submitted', async () => {
    listBreakGlassEventsMock.mockResolvedValue(pageOf([event()]));
    renderPage();
    expect(await acknowledgeButton('bg-1')).toBeEnabled();
  });

  it('is disabled for the submitter', async () => {
    listBreakGlassEventsMock.mockResolvedValue(
      pageOf([event({ submitted_by_user_id: CURRENT_USER })]),
    );
    renderPage();
    expect(await acknowledgeButton('bg-1')).toBeDisabled();
  });

  it('is disabled for the human the agent broke glass on behalf of', async () => {
    listBreakGlassEventsMock.mockResolvedValue(
      pageOf([event({ on_behalf_of_user_id: CURRENT_USER })]),
    );
    renderPage();
    expect(await acknowledgeButton('bg-1')).toBeDisabled();
  });

  it('is disabled once the event is reviewed', async () => {
    listBreakGlassEventsMock.mockResolvedValue(pageOf([event({ status: 'REVIEWED' })]));
    renderPage();
    expect(await acknowledgeButton('bg-1')).toBeDisabled();
  });
});
