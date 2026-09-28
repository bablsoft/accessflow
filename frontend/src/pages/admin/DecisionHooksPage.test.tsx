import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import type { DecisionHook } from '@/types/api';

const {
  listDecisionHooksMock,
  createDecisionHookMock,
  updateDecisionHookMock,
  deleteDecisionHookMock,
  testDecisionHookMock,
  listDatasourcesMock,
} = vi.hoisted(() => ({
  listDecisionHooksMock: vi.fn(),
  createDecisionHookMock: vi.fn(),
  updateDecisionHookMock: vi.fn(),
  deleteDecisionHookMock: vi.fn(),
  testDecisionHookMock: vi.fn(),
  listDatasourcesMock: vi.fn(),
}));

vi.mock('@/api/decisionHooks', async () => {
  const actual = await vi.importActual<typeof import('@/api/decisionHooks')>('@/api/decisionHooks');
  return {
    ...actual,
    listDecisionHooks: listDecisionHooksMock,
    createDecisionHook: createDecisionHookMock,
    updateDecisionHook: updateDecisionHookMock,
    deleteDecisionHook: deleteDecisionHookMock,
    testDecisionHook: testDecisionHookMock,
  };
});

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, listDatasources: listDatasourcesMock };
});

const { DecisionHooksPage } = await import('./DecisionHooksPage');

const SECRET = '0123456789abcdef0123456789abcdef';

function hook(over: Partial<DecisionHook> = {}): DecisionHook {
  return {
    id: 'dh-1',
    organization_id: 'org-1',
    datasource_id: null,
    name: 'OPA gate',
    endpoint_url: 'https://opa.example.com/v1/data',
    timeout_ms: 2000,
    include_sql: false,
    enabled: true,
    secret_configured: true,
    version: 0,
    created_at: '2026-09-28T10:00:00Z',
    updated_at: '2026-09-28T10:00:00Z',
    ...over,
  };
}

function problem(status: number, data: unknown): AxiosError {
  const response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: {} as never,
  } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
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

async function openCreate() {
  await waitFor(() => expect(listDecisionHooksMock).toHaveBeenCalled());
  fireEvent.click(screen.getByText('Add hook'));
  return screen.findByRole('dialog');
}

describe('DecisionHooksPage (#945)', () => {
  beforeEach(() => {
    listDecisionHooksMock.mockReset();
    createDecisionHookMock.mockReset();
    updateDecisionHookMock.mockReset();
    deleteDecisionHookMock.mockReset();
    testDecisionHookMock.mockReset();
    listDatasourcesMock.mockReset();
    listDatasourcesMock.mockResolvedValue({
      content: [{ id: 'ds-1', name: 'prod-orders' }],
      page: 0,
      size: 200,
      total_elements: 1,
      total_pages: 1,
    });
  });

  it('lists hooks with their scope and explains the trust model', async () => {
    listDecisionHooksMock.mockResolvedValue([
      hook(),
      hook({ id: 'dh-2', name: 'Orders gate', datasource_id: 'ds-1', include_sql: true }),
    ]);

    render(wrap(<DecisionHooksPage />));

    expect(await screen.findByText('OPA gate')).toBeInTheDocument();
    expect(screen.getByText('Organization default')).toBeInTheDocument();
    expect(await screen.findByText('prod-orders')).toBeInTheDocument();
    expect(screen.getByText('A hook can add friction, never remove it')).toBeInTheDocument();
  });

  it('shows the empty state and the load error', async () => {
    listDecisionHooksMock.mockResolvedValue([]);
    const { unmount } = render(wrap(<DecisionHooksPage />));
    expect(await screen.findByText(/No decision hooks yet/)).toBeInTheDocument();
    unmount();

    listDecisionHooksMock.mockRejectedValue(new Error('boom'));
    render(wrap(<DecisionHooksPage />));
    expect(await screen.findByText('Could not load decision hooks.')).toBeInTheDocument();
  });

  it('creates an organization-default hook with the secret', async () => {
    listDecisionHooksMock.mockResolvedValue([]);
    createDecisionHookMock.mockResolvedValue(hook());

    render(wrap(<DecisionHooksPage />));
    const dialog = await openCreate();

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'OPA gate' } });
    fireEvent.change(within(dialog).getByLabelText('Endpoint URL'), {
      target: { value: 'https://opa.example.com/v1/data' },
    });
    fireEvent.change(within(dialog).getByLabelText('Signing secret'), { target: { value: SECRET } });
    fireEvent.click(within(dialog).getByText('Create hook'));

    await waitFor(() => {
      expect(createDecisionHookMock).toHaveBeenCalledWith({
        name: 'OPA gate',
        datasource_id: null,
        endpoint_url: 'https://opa.example.com/v1/data',
        timeout_ms: 2000,
        secret: SECRET,
        include_sql: false,
        enabled: true,
      });
    });
    expect(await screen.findByText('Decision hook created.')).toBeInTheDocument();
  });

  it('requires a long enough secret on create', async () => {
    listDecisionHooksMock.mockResolvedValue([]);

    render(wrap(<DecisionHooksPage />));
    const dialog = await openCreate();
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'OPA' } });
    fireEvent.change(within(dialog).getByLabelText('Endpoint URL'), {
      target: { value: 'https://opa.example.com' },
    });
    fireEvent.change(within(dialog).getByLabelText('Signing secret'), { target: { value: 'short' } });
    fireEvent.click(within(dialog).getByText('Create hook'));

    await waitFor(() => {
      expect(document.querySelectorAll('.ant-form-item-explain-error').length).toBeGreaterThan(0);
    });
    expect(createDecisionHookMock).not.toHaveBeenCalled();
  });

  it('warns before the SQL text is sent', async () => {
    listDecisionHooksMock.mockResolvedValue([]);

    render(wrap(<DecisionHooksPage />));
    const dialog = await openCreate();
    const switches = within(dialog).getAllByRole('switch');
    fireEvent.click(switches[0]!);

    expect(await within(dialog).findByText(/literal values/)).toBeInTheDocument();
  });

  it('edits a hook without resending the secret', async () => {
    listDecisionHooksMock.mockResolvedValue([hook()]);
    updateDecisionHookMock.mockResolvedValue(hook({ name: 'Renamed' }));

    render(wrap(<DecisionHooksPage />));
    await screen.findByText('OPA gate');
    fireEvent.click(screen.getByLabelText('Edit'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Leave empty to keep the current secret.')).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Renamed' } });
    fireEvent.click(within(dialog).getByText('Save changes'));

    await waitFor(() => {
      expect(updateDecisionHookMock).toHaveBeenCalledWith('dh-1', {
        name: 'Renamed',
        datasource_id: null,
        endpoint_url: 'https://opa.example.com/v1/data',
        timeout_ms: 2000,
        include_sql: false,
        enabled: true,
      });
    });
  });

  it('toggles a hook from the list', async () => {
    listDecisionHooksMock.mockResolvedValue([hook()]);
    updateDecisionHookMock.mockResolvedValue(hook({ enabled: false }));

    render(wrap(<DecisionHooksPage />));
    await screen.findByText('OPA gate');
    fireEvent.click(screen.getByLabelText('Enabled'));

    await waitFor(() => {
      expect(updateDecisionHookMock).toHaveBeenCalledWith(
        'dh-1',
        expect.objectContaining({ enabled: false }),
      );
    });
    expect(updateDecisionHookMock.mock.calls[0]?.[1]).not.toHaveProperty('secret');
  });

  it('reports a test that the hook answered', async () => {
    listDecisionHooksMock.mockResolvedValue([hook()]);
    testDecisionHookMock.mockResolvedValue({ outcome: 'ESCALATE', latency_ms: 12 });

    render(wrap(<DecisionHooksPage />));
    await screen.findByText('OPA gate');
    fireEvent.click(screen.getByLabelText('Send a test request'));

    expect(await screen.findByText('The hook answered Escalate in 12 ms.')).toBeInTheDocument();
  });

  it('reports a test that failed', async () => {
    listDecisionHooksMock.mockResolvedValue([hook()]);
    testDecisionHookMock.mockResolvedValue({
      outcome: 'FAILED',
      failure: 'SIGNATURE_MISMATCH',
      latency_ms: 30,
    });

    render(wrap(<DecisionHooksPage />));
    await screen.findByText('OPA gate');
    fireEvent.click(screen.getByLabelText('Send a test request'));

    expect(await screen.findByText(/The hook failed: Bad signature/)).toBeInTheDocument();
  });

  it('shows the server detail when the scope is taken', async () => {
    listDecisionHooksMock.mockResolvedValue([]);
    createDecisionHookMock.mockRejectedValue(
      problem(409, { error: 'DECISION_HOOK_SCOPE_CONFLICT', detail: 'Default already exists' }),
    );

    render(wrap(<DecisionHooksPage />));
    const dialog = await openCreate();
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'OPA' } });
    fireEvent.change(within(dialog).getByLabelText('Endpoint URL'), {
      target: { value: 'https://opa.example.com' },
    });
    fireEvent.change(within(dialog).getByLabelText('Signing secret'), { target: { value: SECRET } });
    fireEvent.click(within(dialog).getByText('Create hook'));

    expect(await screen.findByText('Default already exists')).toBeInTheDocument();
  });

  it('deletes a hook after confirmation', async () => {
    listDecisionHooksMock.mockResolvedValue([hook()]);
    deleteDecisionHookMock.mockResolvedValue(undefined);

    render(wrap(<DecisionHooksPage />));
    await screen.findByText('OPA gate');
    fireEvent.click(screen.getByRole('button', { name: /Delete/ }));
    // AntD's confirm renders its title twice (modal title + confirm title).
    expect((await screen.findAllByText('Delete this decision hook?')).length).toBeGreaterThan(0);
    const allDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(allDeletes[allDeletes.length - 1]!);

    await waitFor(() => expect(deleteDecisionHookMock).toHaveBeenCalledWith('dh-1'));
    expect(await screen.findByText('Decision hook deleted.')).toBeInTheDocument();
  });
});
