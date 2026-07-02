import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { useState, type ReactNode } from 'react';
import type { ApiConnector } from '@/types/api';
import { newComposition } from '@/utils/apiRequestComposition';
import '@/i18n';

const { listApiOperationsMock, analyzeApiCallMock, generateApiCallMock } = vi.hoisted(() => ({
  listApiOperationsMock: vi.fn(),
  analyzeApiCallMock: vi.fn(),
  generateApiCallMock: vi.fn(),
}));

vi.mock('@/api/apiConnectors', () => ({
  listApiOperations: listApiOperationsMock,
  apiConnectorKeys: {
    operations: (id: string) => ['api-connectors', 'detail', id, 'operations'] as const,
  },
}));

vi.mock('@/api/apiRequests', () => ({
  analyzeApiCall: analyzeApiCallMock,
  generateApiCall: generateApiCallMock,
}));

vi.mock('@/components/apigov/ApiRequestComposer', () => ({
  ApiRequestComposer: () => <div data-testid="composer-stub" />,
}));

const { ApiAuthoringPanel } = await import('./ApiAuthoringPanel');
const { useApiAuthoring } = await import('./useApiAuthoring');
type ApiAuthoringValue = import('./useApiAuthoring').ApiAuthoringValue;

const baseConnector = {
  id: 'c-1',
  name: 'CRM',
  schema_present: true,
  text_to_api_enabled: true,
  default_headers: {},
} as unknown as ApiConnector;

function Harness({ connector }: { connector: ApiConnector | null }) {
  const [value, setValue] = useState<ApiAuthoringValue>({
    operationId: null,
    verb: 'GET',
    requestPath: '',
    composition: newComposition(),
  });
  const authoring = useApiAuthoring({ connector, value, onChange: setValue });
  return (
    <>
      <button onClick={authoring.analyze}>host-analyze</button>
      <div data-testid="verb-path">{`${value.verb} ${value.requestPath}`}</div>
      <div data-testid="raw-body">{value.composition.rawBody}</div>
      <ApiAuthoringPanel
        authoring={authoring}
        connector={connector}
        value={value}
        onChange={setValue}
      />
    </>
  );
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

describe('ApiAuthoringPanel', () => {
  beforeEach(() => {
    listApiOperationsMock.mockReset();
    analyzeApiCallMock.mockReset();
    generateApiCallMock.mockReset();
  });

  it('hides the operation picker and text-to-API card for schema-less connectors', () => {
    const schemaless = {
      ...baseConnector,
      schema_present: false,
      text_to_api_enabled: false,
    } as ApiConnector;
    render(wrap(<Harness connector={schemaless} />));

    expect(screen.queryByText('Operation')).toBeNull();
    expect(screen.queryByText('Text-to-API')).toBeNull();
    expect(listApiOperationsMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('composer-stub')).toBeInTheDocument();
  });

  it('auto-fills verb and path when an operation is picked', async () => {
    listApiOperationsMock.mockResolvedValue([
      { operation_id: 'createTicket', verb: 'POST', path: '/v1/tickets', write: true },
      { operation_id: 'listTickets', verb: 'GET', path: '/v1/tickets', write: false },
    ]);
    render(wrap(<Harness connector={baseConnector} />));

    const picker = await screen.findByRole('combobox');
    fireEvent.mouseDown(picker);
    await waitFor(() => {
      expect(
        [...document.querySelectorAll('.ant-select-item-option-content')].some((o) =>
          o.textContent?.includes('POST /v1/tickets'),
        ),
      ).toBe(true);
    });
    const option = [...document.querySelectorAll('.ant-select-item-option-content')].find((o) =>
      o.textContent?.includes('POST /v1/tickets'),
    );
    fireEvent.click(option!);

    await waitFor(() => {
      expect(screen.getByTestId('verb-path')).toHaveTextContent('POST /v1/tickets');
    });
  });

  it('renders the AI risk preview after analyze', async () => {
    listApiOperationsMock.mockResolvedValue([]);
    analyzeApiCallMock.mockResolvedValue({
      risk_score: 61,
      risk_level: 'HIGH',
      summary: 'Writes CRM data.',
      issues: ['Bulk write'],
    });
    render(wrap(<Harness connector={baseConnector} />));

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'host-analyze' }));
    });

    await waitFor(() => {
      expect(screen.getByText('Writes CRM data.')).toBeInTheDocument();
    });
    expect(screen.getByText('Bulk write')).toBeInTheDocument();
    expect(analyzeApiCallMock).toHaveBeenCalledWith(
      expect.objectContaining({ connector_id: 'c-1', verb: 'GET' }),
    );
  });

  it('generates a draft body via text-to-API and patches the composition', async () => {
    listApiOperationsMock.mockResolvedValue([]);
    generateApiCallMock.mockResolvedValue({ draft: '{"subject":"Hi"}' });
    render(wrap(<Harness connector={baseConnector} />));

    const prompt = await screen.findByPlaceholderText(/text-to-API is enabled/i);
    fireEvent.change(prompt, { target: { value: 'create a ticket' } });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Generate/i }));
    });

    await waitFor(() => {
      expect(screen.getByTestId('raw-body')).toHaveTextContent('{"subject":"Hi"}');
    });
    expect(generateApiCallMock).toHaveBeenCalledWith({
      connector_id: 'c-1',
      prompt: 'create a ticket',
    });
  });
});
