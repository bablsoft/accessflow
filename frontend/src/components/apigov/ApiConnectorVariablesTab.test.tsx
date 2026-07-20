import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiConnectorVariable } from '@/types/api';

const {
  listApiConnectorVariables,
  createApiConnectorVariable,
  updateApiConnectorVariable,
  deleteApiConnectorVariable,
  reorderApiConnectorVariables,
} = vi.hoisted(() => ({
  listApiConnectorVariables: vi.fn(),
  createApiConnectorVariable: vi.fn(),
  updateApiConnectorVariable: vi.fn(),
  deleteApiConnectorVariable: vi.fn(),
  reorderApiConnectorVariables: vi.fn(),
}));

vi.mock('@/api/apiConnectorVariables', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/apiConnectorVariables')>(
      '@/api/apiConnectorVariables',
    );
  return {
    ...actual,
    listApiConnectorVariables,
    createApiConnectorVariable,
    updateApiConnectorVariable,
    deleteApiConnectorVariable,
    reorderApiConnectorVariables,
  };
});

const { ApiConnectorVariablesTab } = await import('./ApiConnectorVariablesTab');

function variable(overrides: Partial<ApiConnectorVariable> = {}): ApiConnectorVariable {
  return {
    id: 'v-1',
    connector_id: 'c-1',
    name: 'signature',
    kind: 'HMAC',
    expression: '{{request.body}}',
    algorithm: 'HMAC_SHA256',
    encoding: 'HEX',
    has_secret: true,
    target: 'header:X-Signature',
    overridable: false,
    description: 'Vendor signature',
    sort_order: 0,
    created_at: '2026-07-20T10:15:00Z',
    updated_at: '2026-07-20T10:15:00Z',
    ...overrides,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

describe('ApiConnectorVariablesTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listApiConnectorVariables.mockResolvedValue([variable()]);
  });

  it('renders each variable as its placeholder form', async () => {
    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));

    expect(await screen.findByText('{{signature}}')).toBeInTheDocument();
    expect(screen.getByText('header:X-Signature')).toBeInTheDocument();
  });

  it('shows the empty state when the connector has no variables', async () => {
    listApiConnectorVariables.mockResolvedValue([]);

    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));

    expect(await screen.findByText(/No variables yet/i)).toBeInTheDocument();
  });

  it('flags a stored secret and an overridable variable', async () => {
    listApiConnectorVariables.mockResolvedValue([
      variable(),
      variable({ id: 'v-2', name: 'nonce', kind: 'RANDOM_HEX', has_secret: false, overridable: true }),
    ]);

    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));

    expect(await screen.findByText('Secret')).toBeInTheDocument();
    expect(screen.getByText('Overridable')).toBeInTheDocument();
  });

  it('reorders by sending the full id list in the new order', async () => {
    listApiConnectorVariables.mockResolvedValue([
      variable({ id: 'a', name: 'first' }),
      variable({ id: 'b', name: 'second' }),
    ]);
    reorderApiConnectorVariables.mockResolvedValue([]);

    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));
    await screen.findByText('{{first}}');

    fireEvent.click(screen.getAllByRole('button', { name: /move later/i })[0] as HTMLElement);

    await waitFor(() => expect(reorderApiConnectorVariables).toHaveBeenCalled());
    expect(reorderApiConnectorVariables.mock.calls[0]?.[1]).toEqual(['b', 'a']);
  });

  it('opens the create modal with the name and kind fields', async () => {
    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));
    await screen.findByText('{{signature}}');

    fireEvent.click(screen.getByRole('button', { name: /add variable/i }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
  });

  /**
   * Mirrors the server rule and the DB CHECK constraint: a variable holding a secret can never be
   * marked overridable, so the switch is disabled rather than merely rejected on save.
   */
  it('disables the overridable switch for the secret-bearing HMAC kind', async () => {
    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));
    await screen.findByText('{{signature}}');

    fireEvent.click(screen.getAllByRole('button', { name: /^edit$/i })[0] as HTMLElement);
    await screen.findByText('Edit variable');

    const overridable = screen.getByRole('switch');
    expect(overridable).toBeDisabled();
  });

  it('creates a variable from the form values', async () => {
    listApiConnectorVariables.mockResolvedValue([]);
    createApiConnectorVariable.mockResolvedValue(variable());

    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));
    fireEvent.click(screen.getByRole('button', { name: /add variable/i }));
    await screen.findByRole('dialog');

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'requestId' } });
    // CONSTANT is the default kind and requires an expression.
    fireEvent.change(screen.getByLabelText('Expression'), { target: { value: 'v1' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(createApiConnectorVariable).toHaveBeenCalled());
    expect(createApiConnectorVariable.mock.calls[0]?.[1]).toMatchObject({
      name: 'requestId',
      kind: 'CONSTANT',
      expression: 'v1',
      overridable: false,
    });
  });

  it('surfaces a save failure without closing the modal', async () => {
    listApiConnectorVariables.mockResolvedValue([]);
    createApiConnectorVariable.mockRejectedValue(new Error('nope'));

    render(wrap(<ApiConnectorVariablesTab connectorId="c-1" />));
    fireEvent.click(screen.getByRole('button', { name: /add variable/i }));
    await screen.findByRole('dialog');

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'x' } });
    fireEvent.change(screen.getByLabelText('Expression'), { target: { value: 'v' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(createApiConnectorVariable).toHaveBeenCalled());
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
