import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ScimConfig, ScimToken } from '@/types/api';

const {
  getScimConfigMock,
  updateScimConfigMock,
  listScimTokensMock,
  createScimTokenMock,
  revokeScimTokenMock,
} = vi.hoisted(() => ({
  getScimConfigMock: vi.fn(),
  updateScimConfigMock: vi.fn(),
  listScimTokensMock: vi.fn(),
  createScimTokenMock: vi.fn(),
  revokeScimTokenMock: vi.fn(),
}));

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return {
    ...actual,
    getScimConfig: getScimConfigMock,
    updateScimConfig: updateScimConfigMock,
    listScimTokens: listScimTokensMock,
    createScimToken: createScimTokenMock,
    revokeScimToken: revokeScimTokenMock,
  };
});

const { ScimConfigPage } = await import('./ScimConfigPage');

function config(partial: Partial<ScimConfig> = {}): ScimConfig {
  return {
    id: 'cfg-1',
    organization_id: 'org-1',
    enabled: false,
    attr_email: 'userName',
    attr_display_name: 'displayName',
    default_role: 'ANALYST',
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...partial,
  };
}

function token(partial: Partial<ScimToken> = {}): ScimToken {
  return {
    id: 'tok-1',
    name: 'okta-prod',
    token_prefix: 'af_scim_AbCd',
    created_at: '2026-08-01T00:00:00Z',
    last_used_at: null,
    revoked_at: null,
    ...partial,
  };
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

describe('ScimConfigPage', () => {
  beforeEach(() => {
    getScimConfigMock.mockReset();
    updateScimConfigMock.mockReset();
    listScimTokensMock.mockReset();
    createScimTokenMock.mockReset();
    revokeScimTokenMock.mockReset();
    getScimConfigMock.mockResolvedValue(config());
    listScimTokensMock.mockResolvedValue([]);
  });

  it('renders the config form with values from the server', async () => {
    getScimConfigMock.mockResolvedValue(config({ enabled: true, default_role: 'READONLY' }));

    render(wrap(<ScimConfigPage />));

    expect(await screen.findByText('SCIM Provisioning')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('switch')).toBeChecked());
    // The IdP-facing base URL is built from the API base, never the SPA origin.
    expect(screen.getByText(/\/scim\/v2$/)).toBeInTheDocument();
    expect(screen.queryByText(`${window.location.origin}/scim/v2`)).not.toBeInTheDocument();
  });

  it('saves the configuration', async () => {
    updateScimConfigMock.mockResolvedValue(config({ enabled: true }));

    render(wrap(<ScimConfigPage />));
    await screen.findByText('SCIM Provisioning');

    fireEvent.click(screen.getByRole('switch'));
    fireEvent.click(screen.getByRole('button', { name: /Save/ }));

    await waitFor(() =>
      expect(updateScimConfigMock).toHaveBeenCalledWith({
        enabled: true,
        attr_email: 'userName',
        attr_display_name: 'displayName',
        default_role: 'ANALYST',
      }),
    );
  });

  it('lists tokens with prefix and status', async () => {
    listScimTokensMock.mockResolvedValue([
      token(),
      token({
        id: 'tok-2',
        name: 'old',
        token_prefix: 'af_scim_ZzZz',
        revoked_at: '2026-08-02T00:00:00Z',
      }),
    ]);

    render(wrap(<ScimConfigPage />));

    expect(await screen.findByText('okta-prod')).toBeInTheDocument();
    expect(screen.getByText('af_scim_AbCd…')).toBeInTheDocument();
    expect(screen.getByText('Revoked')).toBeInTheDocument();
  });

  it('creating a token shows the raw value once', async () => {
    createScimTokenMock.mockResolvedValue({
      token: token(),
      raw_token: 'af_scim_raw_value_shown_once',
    });

    render(wrap(<ScimConfigPage />));
    await screen.findByText('SCIM Provisioning');

    fireEvent.click(screen.getByRole('button', { name: 'Create token' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Token name'), {
      target: { value: 'okta-prod' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create token' }));
    // mutationFn receives (variables, context) — assert on the first argument only.
    await waitFor(() =>
      expect(createScimTokenMock.mock.calls[0]?.[0]).toEqual({ name: 'okta-prod' }),
    );

    expect(await screen.findByText('af_scim_raw_value_shown_once')).toBeInTheDocument();
    expect(
      screen.getByText('Copy this token now — it will not be shown again.'),
    ).toBeInTheDocument();
  });

  it('revokes a token after confirmation', async () => {
    listScimTokensMock.mockResolvedValue([token()]);
    revokeScimTokenMock.mockResolvedValue(undefined);

    render(wrap(<ScimConfigPage />));
    await screen.findByText('okta-prod');

    fireEvent.click(screen.getByRole('button', { name: /Revoke token okta-prod/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));

    await waitFor(() => expect(revokeScimTokenMock).toHaveBeenCalledWith('tok-1'));
  });

  it('shows the load-error state', async () => {
    getScimConfigMock.mockRejectedValue(new Error('boom'));

    render(wrap(<ScimConfigPage />));

    expect(await screen.findByText('Failed to load SCIM configuration')).toBeInTheDocument();
  });
});
