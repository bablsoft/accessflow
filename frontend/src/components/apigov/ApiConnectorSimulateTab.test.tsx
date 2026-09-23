import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiCallSimulationResult } from '@/types/api';

const { simulateMock, listUsersMock, listOperationsMock } = vi.hoisted(() => ({
  simulateMock: vi.fn(),
  listUsersMock: vi.fn(),
  listOperationsMock: vi.fn(),
}));

vi.mock('@/api/apiCallSimulations', () => ({ simulateApiCall: simulateMock }));
vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, listUsers: listUsersMock };
});
vi.mock('@/api/apiConnectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return { ...actual, listApiOperations: listOperationsMock };
});

const { ApiConnectorSimulateTab } = await import('./ApiConnectorSimulateTab');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

async function pick(label: string | RegExp, option: string) {
  fireEvent.mouseDown(screen.getByLabelText(label));
  await waitFor(() =>
    expect(
      [...document.querySelectorAll('.ant-select-item-option-content')].some(
        (o) => o.textContent?.startsWith(option),
      ),
    ).toBe(true),
  );
  const el = [...document.querySelectorAll('.ant-select-item-option-content')].find(
    (o) => o.textContent?.startsWith(option),
  );
  fireEvent.click(el!);
}

const RESULT: ApiCallSimulationResult = {
  resulting_status: 'PENDING_REVIEW',
  caveats: ['RESPONSE_SHAPE_ABSENT'],
  steps: [
    { step: 'CONNECTOR_GATES', outcome: 'ALLOW', reason: 'Connector is active', details: {} },
    { step: 'ROUTING_POLICIES', outcome: 'SKIP', reason: 'AI analysis failed', details: {} },
  ],
};

describe('ApiConnectorSimulateTab', () => {
  beforeEach(() => {
    simulateMock.mockReset();
    listUsersMock.mockReset();
    listOperationsMock.mockReset();
    listUsersMock.mockResolvedValue({
      content: [{ id: 'u-1', email: 'bot@x.io', display_name: 'CI bot', active: true, principal_type: 'SERVICE_ACCOUNT' }],
      page: 0,
      size: 100,
      total_elements: 1,
      total_pages: 1,
    });
    listOperationsMock.mockResolvedValue([
      { operation_id: 'listPets', verb: 'GET', path: '/pets', summary: 'List pets', write: false },
      { operation_id: 'deletePet', verb: 'DELETE', path: '/pets/{id}', summary: '', write: true },
    ]);
  });

  it('does not simulate on mount and requires a user', async () => {
    render(wrap(<ApiConnectorSimulateTab connectorId="c-1" />));
    fireEvent.click(await screen.findByRole('button', { name: 'Simulate' }));
    expect(await screen.findByText('Select the user to simulate')).toBeInTheDocument();
    expect(simulateMock).not.toHaveBeenCalled();
  });

  it('simulates the chosen operation and renders the trace', async () => {
    simulateMock.mockResolvedValue(RESULT);
    render(wrap(<ApiConnectorSimulateTab connectorId="c-1" />));
    await pick(/^Simulated user/, 'CI bot (bot@x.io)');
    await pick(/^Operation/, 'DELETE /pets/{id}');
    fireEvent.change(screen.getByLabelText(/^Verb/), { target: { value: '  ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Simulate' }));

    expect(await screen.findByTestId('decision-trace')).toBeInTheDocument();
    expect(simulateMock.mock.calls[0]![0]).toEqual({
      user_id: 'u-1',
      connector_id: 'c-1',
      ai_outcome: 'SKIPPED',
      operation_id: 'deletePet',
    });
    expect(screen.getByText('Connector gates')).toBeInTheDocument();
    expect(screen.getByText('Policy list not evaluated on this path')).toBeInTheDocument();
    expect(screen.getByText(/no response body/)).toBeInTheDocument();
  });

  it('sends a free verb and the risk level, and shows the server detail on failure', async () => {
    simulateMock.mockRejectedValue({
      isAxiosError: true,
      response: { status: 404, data: { detail: 'API connector not found' } },
    });
    render(wrap(<ApiConnectorSimulateTab connectorId="c-1" />));
    await pick(/^Simulated user/, 'CI bot (bot@x.io)');
    fireEvent.change(screen.getByLabelText(/^Verb/), { target: { value: ' POST ' } });
    await pick(/^Risk level/, 'Critical');
    fireEvent.click(screen.getByRole('button', { name: 'Simulate' }));

    expect(await screen.findByText('API connector not found')).toBeInTheDocument();
    expect(simulateMock.mock.calls[0]![0]).toEqual({
      user_id: 'u-1',
      connector_id: 'c-1',
      ai_outcome: 'SKIPPED',
      verb: 'POST',
      risk_level: 'CRITICAL',
    });
  });
});
