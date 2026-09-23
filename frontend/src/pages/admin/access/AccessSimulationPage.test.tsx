import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { AccessSimulationResult } from '@/types/api';
import type { Permission } from '@/utils/permissions';

const { simulateMock, effectiveMock, listUsersMock, listDatasourcesMock, schemaMock } = vi.hoisted(
  () => ({
    simulateMock: vi.fn(),
    effectiveMock: vi.fn(),
    listUsersMock: vi.fn(),
    listDatasourcesMock: vi.fn(),
    schemaMock: vi.fn(),
  }),
);

vi.mock('@/api/accessSimulations', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/accessSimulations')>('@/api/accessSimulations');
  return { ...actual, simulateAccess: simulateMock, getEffectiveAccess: effectiveMock };
});
vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, listUsers: listUsersMock };
});
vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, listDatasources: listDatasourcesMock, getDatasourceSchema: schemaMock };
});
vi.mock('@/components/editor/SqlEditor', () => ({
  SqlEditor: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <textarea data-testid="sql-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

const { default: AccessSimulationPage } = await import('./AccessSimulationPage');

function page<T>(content: T[]) {
  return { content, page: 0, size: 100, total_elements: content.length, total_pages: 1 };
}

function setPermissions(permissions: Permission[]) {
  useAuthStore.setState({
    user: {
      id: 'admin-1',
      email: 'admin@x.io',
      display_name: 'Admin',
      role: 'ADMIN',
      role_id: null,
      permissions,
      auth_provider: 'LOCAL',
      totp_enabled: false,
      platform_admin: false,
      preferred_language: null,
      governs_apis: true,
      governs_deployments: true,
    },
    accessToken: 't',
  });
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function selectOption(text: string) {
  const option = [...document.querySelectorAll('.ant-select-item-option-content')].find(
    (o) => o.textContent === text,
  );
  expect(option).toBeDefined();
  fireEvent.click(option!);
}

async function pick(label: string | RegExp, option: string) {
  fireEvent.mouseDown(screen.getByLabelText(label));
  await waitFor(() =>
    expect(
      [...document.querySelectorAll('.ant-select-item-option-content')].some(
        (o) => o.textContent === option,
      ),
    ).toBe(true),
  );
  selectOption(option);
}

async function fillRequired() {
  await pick('Simulated user', 'Ann Analyst (ann@x.io)');
  await pick('Datasource', 'Orders DB');
  fireEvent.change(screen.getByTestId('sql-editor'), {
    target: { value: 'UPDATE orders SET x = 1 WHERE id = 1' },
  });
}

const RESULT: AccessSimulationResult = {
  resulting_status: 'REJECTED',
  caveats: ['CLIENT_CONTEXT_ABSENT'],
  steps: [
    { step: 'DATASOURCE_GATES', outcome: 'ALLOW', reason: 'Datasource is active', details: {} },
    { step: 'QUOTA', outcome: 'SKIP', reason: 'No quota applies', details: {} },
    {
      step: 'ROUTING_POLICIES',
      outcome: 'MATCH',
      reason: 'Block updates matched',
      details: {
        policies: [
          { policy_id: 'p-1', name: 'Block updates', priority: 0, action: 'AUTO_REJECT', matched: true, decisive: true },
        ],
      },
    },
  ],
  evaluated_context: { query_type: 'UPDATE', has_where_clause: true },
};

describe('AccessSimulationPage', () => {
  beforeEach(() => {
    simulateMock.mockReset();
    effectiveMock.mockReset();
    listUsersMock.mockReset();
    listDatasourcesMock.mockReset();
    schemaMock.mockReset();
    listUsersMock.mockResolvedValue(
      page([
        { id: 'u-1', email: 'ann@x.io', display_name: 'Ann Analyst', active: true },
        { id: 'u-2', email: 'gone@x.io', display_name: 'Gone', active: false },
      ]),
    );
    listDatasourcesMock.mockResolvedValue(page([{ id: 'd-1', name: 'Orders DB', db_type: 'POSTGRESQL' }]));
    schemaMock.mockResolvedValue({ schemas: [] });
    setPermissions(['DATASOURCE_PERMISSION_MANAGE']);
  });

  afterEach(() => {
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('fires no trace on mount and labels the action "Trace", not "Submit"', async () => {
    render(wrap(<AccessSimulationPage />));
    expect(await screen.findByText('Query trace')).toBeInTheDocument();
    expect(screen.getByText('Who has access')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Trace' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /submit|run/i })).not.toBeInTheDocument();
    expect(simulateMock).not.toHaveBeenCalled();
  });

  it('renders the trace, the routing policy and the resulting status', async () => {
    simulateMock.mockResolvedValue(RESULT);
    render(wrap(<AccessSimulationPage />));
    await fillRequired();
    fireEvent.click(screen.getByRole('button', { name: 'Trace' }));

    expect(await screen.findByTestId('decision-trace')).toBeInTheDocument();
    expect(simulateMock.mock.calls[0]![0]).toEqual({
      user_id: 'u-1',
      datasource_id: 'd-1',
      sql: 'UPDATE orders SET x = 1 WHERE id = 1',
      ai_outcome: 'SKIPPED',
    });
    expect(screen.getByTestId('decision-trace-status')).toHaveTextContent('Rejected');
    expect(screen.getByText('Block updates')).toBeInTheDocument();
    expect(screen.getByText('No quota applies')).toBeInTheDocument();
    expect(screen.getByText('Evaluated routing context')).toBeInTheDocument();
    expect(screen.queryByText('Gone (gone@x.io)')).not.toBeInTheDocument();
  });

  it('renders the server detail when the trace fails', async () => {
    simulateMock.mockRejectedValue({
      isAxiosError: true,
      response: { status: 404, data: { detail: 'Datasource not found in this organization' } },
    });
    render(wrap(<AccessSimulationPage />));
    await fillRequired();
    fireEvent.click(screen.getByRole('button', { name: 'Trace' }));

    expect(await screen.findByText('The trace could not be computed')).toBeInTheDocument();
    expect(screen.getByText('Datasource not found in this organization')).toBeInTheDocument();
  });

  it('refuses a risk level without a score client-side', async () => {
    render(wrap(<AccessSimulationPage />));
    await fillRequired();
    await pick(/^Risk level/, 'High');
    fireEvent.click(screen.getByRole('button', { name: 'Trace' }));

    expect(
      (await screen.findAllByText('Set both the risk level and the risk score, or neither')).length,
    ).toBeGreaterThan(0);
    expect(simulateMock).not.toHaveBeenCalled();
  });

  it('sends the risk level and score together', async () => {
    simulateMock.mockResolvedValue(RESULT);
    render(wrap(<AccessSimulationPage />));
    await fillRequired();
    await pick(/^Risk level/, 'High');
    fireEvent.change(screen.getByLabelText(/^Risk score/), { target: { value: '80' } });
    fireEvent.click(screen.getByRole('button', { name: 'Trace' }));

    await waitFor(() => expect(simulateMock).toHaveBeenCalledTimes(1));
    expect(simulateMock.mock.calls[0]![0]).toMatchObject({ risk_level: 'HIGH', risk_score: 80 });
  });

  it('shows the loading skeleton while the trace runs', async () => {
    simulateMock.mockReturnValue(new Promise(() => {}));
    render(wrap(<AccessSimulationPage />));
    await fillRequired();
    fireEvent.click(screen.getByRole('button', { name: 'Trace' }));
    await waitFor(() => expect(document.querySelector('.ant-skeleton')).not.toBeNull());
  });

  it('gives an auditor the reverse index only', async () => {
    setPermissions(['ACCESS_USAGE_REPORT_VIEW']);
    render(wrap(<AccessSimulationPage />));
    expect(await screen.findByText('Who has access')).toBeInTheDocument();
    expect(screen.queryByText('Query trace')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Trace' })).not.toBeInTheDocument();
  });
});
