import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { Datasource, RequestGroup, RequestGroupItem } from '@/types/api';

const {
  listDatasourcesMock,
  listApiConnectorsMock,
  getRequestGroupMock,
  createRequestGroupMock,
  updateRequestGroupMock,
} = vi.hoisted(() => ({
  listDatasourcesMock: vi.fn(),
  listApiConnectorsMock: vi.fn(),
  getRequestGroupMock: vi.fn(),
  createRequestGroupMock: vi.fn(),
  updateRequestGroupMock: vi.fn(),
}));

vi.mock('@/api/datasources', () => ({
  listDatasources: listDatasourcesMock,
  datasourceKeys: {
    list: (filters: unknown) => ['datasources', 'list', filters] as const,
  },
}));

vi.mock('@/api/apiConnectors', () => ({
  listApiConnectors: listApiConnectorsMock,
  apiConnectorKeys: {
    list: (filters: unknown) => ['api-connectors', 'list', filters] as const,
  },
}));

vi.mock('@/api/requestGroups', async () => {
  const actual = await vi.importActual<typeof import('@/api/requestGroups')>('@/api/requestGroups');
  return {
    ...actual,
    getRequestGroup: getRequestGroupMock,
    createRequestGroup: createRequestGroupMock,
    updateRequestGroup: updateRequestGroupMock,
    submitRequestGroup: vi.fn(),
  };
});

// The drawer mounts the full editor surfaces; stub it and surface its props for assertions.
vi.mock('./GroupMemberEditDrawer', () => ({
  GroupMemberEditDrawer: ({
    member,
    datasources,
  }: {
    member: { key: string } | null;
    datasources: { name: string }[];
  }) =>
    member ? (
      <div data-testid="drawer-stub">{datasources.map((d) => d.name).join(',')}</div>
    ) : null,
}));

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const GroupBuilderPage = (await import('./GroupBuilderPage')).default;

const datasources = [
  { id: 'ds-1', name: 'prod-db', db_type: 'POSTGRESQL', active: true },
  { id: 'ds-2', name: 'legacy-db', db_type: 'MYSQL', active: false },
] as Datasource[];

function queryItem(): RequestGroupItem {
  return {
    id: 'i-1',
    sequence_order: 0,
    target_kind: 'QUERY',
    datasource_id: 'ds-1',
    datasource_name: 'prod-db',
    sql_text: 'SELECT 1',
    query_type: 'SELECT',
    transactional: false,
    api_connector_id: null,
    api_connector_name: null,
    operation_id: null,
    verb: null,
    request_path: null,
    request_headers: null,
    query_params: null,
    body_type: null,
    request_content_type: null,
    request_body: null,
    form_fields: null,
    binary_filename: null,
    ai_analysis_id: null,
    ai_risk_level: null,
    ai_risk_score: null,
    ai_analysis: null,
    status: 'PENDING',
    response_status_code: null,
    rows_affected: null,
    error_message: null,
    duration_ms: null,
    executed_at: null,
  };
}

function apiItem(): RequestGroupItem {
  return {
    ...queryItem(),
    id: 'i-2',
    sequence_order: 1,
    target_kind: 'API_CALL',
    datasource_id: null,
    datasource_name: null,
    sql_text: null,
    query_type: null,
    transactional: null,
    api_connector_id: 'c-1',
    api_connector_name: 'CRM',
    verb: 'POST',
    request_path: '/v1/tickets',
    request_headers: { 'X-Trace': '1' },
    query_params: { dryRun: 'true' },
    body_type: 'RAW',
    request_content_type: 'application/json',
    request_body: '{"a":1}',
    form_fields: [],
  };
}

function group(overrides: Partial<RequestGroup> = {}): RequestGroup {
  return {
    id: 'g-1',
    organization_id: 'org-1',
    submitted_by_user_id: 'u-1',
    submitted_by_display_name: 'Dana',
    name: 'nightly bundle',
    description: 'desc',
    status: 'DRAFT',
    continue_on_error: true,
    scheduled_for: null,
    ai_risk_level: null,
    ai_risk_score: null,
    required_approvals: null,
    current_review_stage: null,
    error_message: null,
    execution_started_at: null,
    execution_completed_at: null,
    created_at: '2026-07-01T10:00:00Z',
    updated_at: '2026-07-01T10:00:00Z',
    items: [queryItem(), apiItem()],
    ...overrides,
  };
}

function pageOf<T>(content: T[]) {
  return { content, page: 0, size: 100, total_elements: content.length, total_pages: 1 };
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App>
          <Routes>
            <Route path="/request-groups/new" element={<GroupBuilderPage />} />
            <Route path="/request-groups/:id/edit" element={<GroupBuilderPage />} />
          </Routes>
        </App>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('GroupBuilderPage (#559)', () => {
  beforeEach(() => {
    listDatasourcesMock.mockReset().mockResolvedValue(pageOf(datasources));
    listApiConnectorsMock.mockReset().mockResolvedValue(pageOf([]));
    getRequestGroupMock.mockReset();
    createRequestGroupMock.mockReset();
    updateRequestGroupMock.mockReset();
    navigateMock.mockReset();
    useAuthStore.setState({
      user: { id: 'u-1', email: 'u@x.io', display_name: 'Dana', role: 'ANALYST' } as never,
    });
  });

  it('adds a step and opens the edit drawer with active-only datasources', async () => {
    renderAt('/request-groups/new');

    fireEvent.mouseOver(await screen.findByRole('button', { name: /Add step/i }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /Database query/i }));

    const drawer = await screen.findByTestId('drawer-stub');
    // The inactive datasource is filtered out before it reaches the drawer (#559).
    expect(drawer).toHaveTextContent('prod-db');
    expect(drawer).not.toHaveTextContent('legacy-db');
    expect(screen.getByTestId('group-member-0')).toBeInTheDocument();
  });

  it('hydrates an owned DRAFT for editing and saves via PUT', async () => {
    getRequestGroupMock.mockResolvedValue(group());
    updateRequestGroupMock.mockResolvedValue(group());

    renderAt('/request-groups/g-1/edit');

    const nameInput = await screen.findByDisplayValue('nightly bundle');
    expect(nameInput).toBeInTheDocument();
    expect(screen.getByTestId('group-member-0')).toBeInTheDocument();
    expect(screen.getByTestId('group-member-1')).toBeInTheDocument();
    // The API step summary shows its authored call shape.
    expect(screen.getByText('POST /v1/tickets')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Save draft/i }));

    await waitFor(() => expect(updateRequestGroupMock).toHaveBeenCalled());
    const [savedId, payload] = updateRequestGroupMock.mock.calls[0];
    expect(savedId).toBe('g-1');
    expect(createRequestGroupMock).not.toHaveBeenCalled();
    // The saved API composition round-trips (regression: body was dropped on hydrate).
    expect(payload.items[1]).toEqual(
      expect.objectContaining({
        target_kind: 'API_CALL',
        request_body: '{"a":1}',
        request_headers: { 'X-Trace': '1' },
        query_params: { dryRun: 'true' },
      }),
    );
  });

  it('redirects non-DRAFT groups back to the detail page', async () => {
    getRequestGroupMock.mockResolvedValue(group({ status: 'PENDING_REVIEW' }));

    renderAt('/request-groups/g-1/edit');

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/request-groups/g-1', { replace: true });
    });
  });

  it("redirects other users' drafts back to the detail page", async () => {
    getRequestGroupMock.mockResolvedValue(group({ submitted_by_user_id: 'someone-else' }));

    renderAt('/request-groups/g-1/edit');

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/request-groups/g-1', { replace: true });
    });
  });
});
