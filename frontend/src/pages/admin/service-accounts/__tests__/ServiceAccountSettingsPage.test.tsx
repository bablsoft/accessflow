import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AxiosError, type AxiosResponse } from 'axios';
import '@/i18n';
import type { ServiceAccount } from '@/types/api';
import { HUMANS, MCP_TOOLS, ROLES, account, key, page } from './fixtures';

const getServiceAccount = vi.fn();
const updateServiceAccount = vi.fn();
const deactivateServiceAccount = vi.fn();
const issueServiceAccountKey = vi.fn();
const rotateServiceAccountKey = vi.fn();
const revokeServiceAccountKey = vi.fn();
const listDelegatedPrincipals = vi.fn();
const grantDelegatedPrincipal = vi.fn();
const revokeDelegatedPrincipal = vi.fn();
const listMcpTools = vi.fn();
const listUsers = vi.fn();
const listAuditEvents = vi.fn();

vi.mock('@/api/serviceAccounts', async () => {
  const actual = await vi.importActual<typeof import('@/api/serviceAccounts')>('@/api/serviceAccounts');
  return {
    ...actual,
    getServiceAccount: (...args: unknown[]) => getServiceAccount(...args),
    updateServiceAccount: (...args: unknown[]) => updateServiceAccount(...args),
    deactivateServiceAccount: (...args: unknown[]) => deactivateServiceAccount(...args),
    issueServiceAccountKey: (...args: unknown[]) => issueServiceAccountKey(...args),
    rotateServiceAccountKey: (...args: unknown[]) => rotateServiceAccountKey(...args),
    revokeServiceAccountKey: (...args: unknown[]) => revokeServiceAccountKey(...args),
    listDelegatedPrincipals: (...args: unknown[]) => listDelegatedPrincipals(...args),
    grantDelegatedPrincipal: (...args: unknown[]) => grantDelegatedPrincipal(...args),
    revokeDelegatedPrincipal: (...args: unknown[]) => revokeDelegatedPrincipal(...args),
    listMcpTools: () => listMcpTools(),
  };
});

vi.mock('@/api/roles', () => ({
  listRoles: () => Promise.resolve(ROLES),
  roleKeys: { all: ['roles'] as const, lists: () => ['roles', 'list'] as const },
}));

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return {
    ...actual,
    listUsers: (...args: unknown[]) => listUsers(...args),
    listAuditEvents: (...args: unknown[]) => listAuditEvents(...args),
  };
});

const { ServiceAccountSettingsPage } = await import('../ServiceAccountSettingsPage');

function wrap(node: ReactNode, entry = '/admin/service-accounts/sa-1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <AntdApp>
          <Routes>
            <Route path="/admin/service-accounts" element={<div>list-route</div>} />
            <Route path="/admin/service-accounts/:id" element={node} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const panel = () => screen.getByRole('tabpanel');

function axiosError(status: number, data: unknown): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
}

describe('ServiceAccountSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getServiceAccount.mockResolvedValue(account({ api_keys: [key()] }));
    updateServiceAccount.mockImplementation((_id: string, input: Partial<ServiceAccount>) =>
      Promise.resolve(account({ ...input })),
    );
    listDelegatedPrincipals.mockResolvedValue([]);
    listMcpTools.mockResolvedValue(MCP_TOOLS);
    listUsers.mockResolvedValue(HUMANS);
    listAuditEvents.mockResolvedValue(page([]));
  });

  it('renders the header, defaults to the overview tab and falls back on an unknown ?tab', async () => {
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=nope'));

    expect(await screen.findByRole('heading', { name: 'CI bot' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { selected: true })).toHaveTextContent('Overview');
    expect(within(panel()).getByTestId('overview-email')).toHaveValue('ci-bot@example.com');
    expect(screen.queryByTestId('bootstrap-banner')).not.toBeInTheDocument();
    expect(screen.queryByText(/^(BOOTSTRAP|SERVICE_ACCOUNT|HUMAN|ACTIVE|EXPIRED|REVOKED)$/)).not.toBeInTheDocument();
  });

  it('shows the bootstrap banner and locks the declared fields', async () => {
    getServiceAccount.mockResolvedValue(
      account({ managed_by: 'BOOTSTRAP', api_keys: [key({ name: 'e2e', bootstrap_declared: true })] }),
    );
    render(wrap(<ServiceAccountSettingsPage />));

    expect(await screen.findByTestId('bootstrap-banner')).toHaveTextContent('ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS');
    await waitFor(() => expect(within(panel()).getByLabelText('Display name')).toBeDisabled());
    expect(within(panel()).getByRole('combobox', { name: 'Role' })).toBeDisabled();
    expect(within(panel()).getByLabelText('Description')).not.toBeDisabled();
    fireEvent.click(screen.getByRole('tab', { name: 'API keys' }));
    const keys = await screen.findByRole('tabpanel');
    expect(within(keys).getByTestId('bootstrap-declared-tag')).toHaveTextContent('Declared');
    // Role queries inside a deep AntD tree are pathologically slow in jsdom (getComputedStyle per
    // ancestor), so the disabled buttons are located by their text.
    const actions = within(keys).getByTestId('declared-key-actions');
    expect(within(actions).getByText('Revoke').closest('button')).toBeDisabled();
    expect(within(actions).getByText('Rotate').closest('button')).toBeDisabled();
  });

  it('saves only the changed overview fields, clearing a blanked description', async () => {
    render(wrap(<ServiceAccountSettingsPage />));
    await screen.findByRole('heading', { name: 'CI bot' });
    await waitFor(() => expect(within(panel()).getByLabelText('Display name')).toHaveValue('CI bot'));

    fireEvent.change(within(panel()).getByLabelText('Display name'), { target: { value: 'Renamed' } });
    fireEvent.change(within(panel()).getByLabelText('Description'), { target: { value: '' } });
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save changes' }));

    await waitFor(() =>
      expect(updateServiceAccount).toHaveBeenCalledWith('sa-1', {
        display_name: 'Renamed',
        clear: ['DESCRIPTION'],
      }),
    );
    expect(await screen.findByText('Service account updated')).toBeInTheDocument();
  });

  it('issues a key and shows it exactly once', async () => {
    issueServiceAccountKey.mockResolvedValue({ api_key: key({ id: 'k-2', name: 'ci' }), raw_key: 'af_raw_secret' });
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=api-keys'));
    await screen.findByRole('heading', { name: 'CI bot' });

    fireEvent.click(within(panel()).getByRole('button', { name: 'Issue key' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Key name'), { target: { value: 'ci' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Issue key' }));

    await waitFor(() =>
      expect(issueServiceAccountKey).toHaveBeenCalledWith('sa-1', { name: 'ci', expires_at: null }),
    );
    expect(await screen.findByTestId('issued-raw-key')).toHaveTextContent('af_raw_secret');
    expect(screen.getByText(/only time the key is shown/)).toBeInTheDocument();
  });

  it('rotates a key with a grace window and states until when the old one works', async () => {
    rotateServiceAccountKey.mockResolvedValue({
      api_key: key({ id: 'k-2' }),
      raw_key: 'af_rotated',
      superseded_key: key({ expires_at: '2026-09-20T12:00:00Z' }),
    });
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=api-keys'));
    await screen.findByRole('heading', { name: 'CI bot' });
    fireEvent.click(within(panel()).getByText('Rotate').closest('button')!);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Grace period (hours)'), { target: { value: '12' } });
    fireEvent.click(within(dialog).getByText('Rotate', { selector: 'button > span' }).closest('button')!);

    await waitFor(() =>
      expect(rotateServiceAccountKey).toHaveBeenCalledWith('sa-1', 'k-1', {
        // The old key stays live through the grace, so the suggested name must differ.
        name: `github-actions-${new Date().toISOString().slice(0, 10)}`,
        expires_at: null,
        grace_period: 'PT12H',
      }),
    );
    expect(await screen.findByTestId('issued-raw-key')).toHaveTextContent('af_rotated');
    expect(screen.getByTestId('superseded-key-note')).toHaveTextContent('af_kQ7abcde');
  });

  it('revokes a key through the confirmation', async () => {
    revokeServiceAccountKey.mockResolvedValue(undefined);
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=api-keys'));
    await screen.findByRole('heading', { name: 'CI bot' });

    fireEvent.click(within(panel()).getByText('Revoke').closest('button')!);
    const pop = await screen.findByRole('tooltip');
    fireEvent.click(within(pop).getByText('Revoke', { selector: 'button > span' }).closest('button')!);

    await waitFor(() => expect(revokeServiceAccountKey).toHaveBeenCalledWith('sa-1', 'k-1'));
  });

  it('encodes "every tool" as a clear and an empty restricted set as []', async () => {
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=mcp-tools'));
    await screen.findByRole('heading', { name: 'CI bot' });
    expect(within(panel()).getByTestId('tools-enforcement-note')).toHaveTextContent('tools/list');
    await waitFor(() => expect(within(panel()).getByRole('checkbox', { name: /validate_sql/ })).toBeChecked());

    updateServiceAccount.mockResolvedValueOnce(account({ mcp_tool_allow_list: null }));
    fireEvent.click(within(panel()).getByRole('radio', { name: 'Every tool' }));
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save allow-list' }));
    await waitFor(() =>
      expect(updateServiceAccount).toHaveBeenCalledWith('sa-1', { clear: ['MCP_TOOL_ALLOW_LIST'] }),
    );
    // The saved account (every tool) is written back into the form.
    await waitFor(() => expect(within(panel()).getByRole('radio', { name: 'Every tool' })).toBeChecked());

    fireEvent.click(within(panel()).getByRole('radio', { name: 'Only the selected tools' }));
    expect(await within(panel()).findByTestId('tools-none-warning')).toBeInTheDocument();
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save allow-list' }));
    await waitFor(() =>
      expect(updateServiceAccount).toHaveBeenCalledWith('sa-1', { mcp_tool_allow_list: [] }),
    );
  });

  it('clears a blanked rate limit instead of sending null', async () => {
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=limits'));
    await screen.findByRole('heading', { name: 'CI bot' });
    await waitFor(() => expect(within(panel()).getByLabelText('Requests per minute')).toHaveValue('60'));

    fireEvent.change(within(panel()).getByLabelText('Requests per minute'), { target: { value: '' } });
    fireEvent.change(within(panel()).getByLabelText('Requests per day'), { target: { value: '500' } });
    fireEvent.click(within(panel()).getByRole('button', { name: 'Save limits' }));

    await waitFor(() =>
      expect(updateServiceAccount).toHaveBeenCalledWith('sa-1', {
        rate_limit_per_day: 500,
        clear: ['RATE_LIMIT_PER_MINUTE'],
      }),
    );
  });

  it('lists, grants and revokes on-behalf-of principals', async () => {
    listDelegatedPrincipals.mockResolvedValue([
      {
        id: 'd-1',
        service_account_user_id: 'sa-1',
        service_account_email: 'ci-bot@example.com',
        principal_user_id: 'u-owner',
        principal_email: 'alice@example.com',
        granted_by: 'u-owner',
        created_at: '2026-09-10T12:00:00Z',
        expires_at: null,
        revoked_at: null,
        status: 'ACTIVE',
      },
    ]);
    grantDelegatedPrincipal.mockResolvedValue({});
    revokeDelegatedPrincipal.mockResolvedValue(undefined);
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=principals'));
    await screen.findByRole('heading', { name: 'CI bot' });

    const row = (await within(panel()).findByText('alice@example.com')).closest('tr');
    expect(within(row!).getByText('Self-service')).toBeInTheDocument();
    expect(within(row!).getByText('Active')).toBeInTheDocument();

    fireEvent.click(within(row!).getByText('Revoke').closest('button')!);
    const pop = await screen.findByRole('tooltip');
    fireEvent.click(within(pop).getByText('Revoke', { selector: 'button > span' }).closest('button')!);
    await waitFor(() => expect(revokeDelegatedPrincipal).toHaveBeenCalledWith('sa-1', 'd-1'));

    fireEvent.click(within(panel()).getByText('Add principal').closest('button')!);
    const dialog = await screen.findByRole('dialog');
    fireEvent.mouseDown(within(dialog).getByLabelText('Person'));
    fireEvent.click(await screen.findByTitle('Alice (alice@example.com)'));
    fireEvent.click(within(dialog).getByText('Add', { selector: 'button > span' }).closest('button')!);
    await waitFor(() =>
      expect(grantDelegatedPrincipal).toHaveBeenCalledWith('sa-1', {
        principal_user_id: 'u-owner',
        expires_at: null,
      }),
    );
  });

  it('shows the activity filtered to the account as actor with the on-behalf-of chip', async () => {
    listAuditEvents.mockResolvedValue(
      page([
        {
          id: 'a-1',
          organization_id: 'org-1',
          actor_id: 'sa-1',
          actor_email: 'ci-bot@example.com',
          actor_display_name: 'CI bot',
          on_behalf_of_email: 'alice@example.com',
          action: 'QUERY_SUBMITTED',
          resource_type: 'query_request',
          resource_id: 'q-1',
          metadata: { on_behalf_of_user_id: 'u-owner' },
          ip_address: null,
          user_agent: null,
          created_at: '2026-09-16T08:11:02Z',
        },
      ]),
    );
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=activity'));
    await screen.findByRole('heading', { name: 'CI bot' });

    expect(await within(panel()).findByTestId('on-behalf-of-tag')).toHaveTextContent('on behalf of alice@example.com');
    expect(listAuditEvents).toHaveBeenCalledWith({ actor_id: 'sa-1', page: 0, size: 20 });
    expect(within(panel()).getByRole('link', { name: 'Open in audit log' })).toHaveAttribute(
      'href',
      '/admin/audit-log?actor_id=sa-1',
    );
  });

  it('deactivates from the header and navigates back', async () => {
    deactivateServiceAccount.mockResolvedValue(undefined);
    render(wrap(<ServiceAccountSettingsPage />));
    await screen.findByRole('heading', { name: 'CI bot' });

    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
    const pop = await screen.findByRole('tooltip');
    fireEvent.click(within(pop).getByRole('button', { name: 'Deactivate' }));
    await waitFor(() => expect(deactivateServiceAccount).toHaveBeenCalledWith('sa-1'));

    fireEvent.click(screen.getByRole('button', { name: 'Back to service accounts' }));
    expect(await screen.findByText('list-route')).toBeInTheDocument();
  });

  it('shows the load error with the server detail and a retry, never "not found", on a failure', async () => {
    getServiceAccount.mockRejectedValueOnce(
      axiosError(500, { error: 'INTERNAL', detail: 'database is on fire' }),
    );
    render(wrap(<ServiceAccountSettingsPage />));
    expect(await screen.findByText('Could not load the service account')).toBeInTheDocument();
    expect(screen.getByText('database is on fire')).toBeInTheDocument();
    expect(screen.queryByText('Service account not found')).not.toBeInTheDocument();

    getServiceAccount.mockResolvedValueOnce(account());
    fireEvent.click(screen.getByText('Retry').closest('button')!);
    expect(await screen.findByRole('heading', { name: 'CI bot' })).toBeInTheDocument();
  });

  it('sends the picked expiry as an ISO instant when issuing a key', async () => {
    issueServiceAccountKey.mockResolvedValue({ api_key: key({ id: 'k-2', name: 'ci' }), raw_key: 'af_raw' });
    render(wrap(<ServiceAccountSettingsPage />, '/admin/service-accounts/sa-1?tab=api-keys'));
    await screen.findByRole('heading', { name: 'CI bot' });

    fireEvent.click(within(panel()).getByRole('button', { name: 'Issue key' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Key name'), { target: { value: 'ci' } });
    // The picker is the Form.Item's controlled child: typing a date into it must reach the form.
    const picker = within(dialog).getByLabelText('Expires');
    fireEvent.mouseDown(picker);
    fireEvent.change(picker, { target: { value: '2099-01-02 03:04:05' } });
    fireEvent.keyDown(picker, { key: 'Enter', code: 'Enter' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Issue key' }));

    await waitFor(() => expect(issueServiceAccountKey).toHaveBeenCalled());
    const payload = issueServiceAccountKey.mock.calls[0]?.[1] as { expires_at: string | null };
    // Local wall-clock in, UTC instant out — compare instants, not calendar days.
    expect(payload.expires_at).toBe(new Date('2099-01-02T03:04:05').toISOString());
  });
});
