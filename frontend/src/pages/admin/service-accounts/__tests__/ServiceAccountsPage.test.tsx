import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AxiosError, type AxiosResponse } from 'axios';
import '@/i18n';
import { HUMANS, MCP_TOOLS, ROLES, account, page } from './fixtures';

const listServiceAccounts = vi.fn();
const createServiceAccount = vi.fn();
const listMcpTools = vi.fn();
const listUsers = vi.fn();

vi.mock('@/api/serviceAccounts', async () => {
  const actual = await vi.importActual<typeof import('@/api/serviceAccounts')>('@/api/serviceAccounts');
  return {
    ...actual,
    listServiceAccounts: (...args: unknown[]) => listServiceAccounts(...args),
    createServiceAccount: (...args: unknown[]) => createServiceAccount(...args),
    listMcpTools: () => listMcpTools(),
  };
});

vi.mock('@/api/roles', () => ({
  listRoles: () => Promise.resolve(ROLES),
  roleKeys: { all: ['roles'] as const, lists: () => ['roles', 'list'] as const },
}));

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, listUsers: (...args: unknown[]) => listUsers(...args) };
});

const { ServiceAccountsPage } = await import('../ServiceAccountsPage');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/service-accounts']}>
        <AntdApp>
          <Routes>
            <Route path="/admin/service-accounts" element={node} />
            <Route path="/admin/service-accounts/:id" element={<div>settings-route</div>} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function axiosError(status: number, data: unknown): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
}

describe('ServiceAccountsPage', () => {
  beforeEach(() => {
    listServiceAccounts.mockReset();
    createServiceAccount.mockReset();
    listMcpTools.mockReset();
    listUsers.mockReset();
    listServiceAccounts.mockResolvedValue(
      page([
        account(),
        account({
          id: 'sa-2',
          email: 'terraform@example.com',
          display_name: 'Terraform',
          managed_by: 'BOOTSTRAP',
          owner_user_id: null,
          owner_email: null,
          owner_display_name: null,
          mcp_tool_allow_list: null,
          rate_limit_per_minute: null,
          rate_limit_per_day: 500,
          active: false,
          active_api_key_count: 0,
          last_used_at: null,
        }),
      ]),
    );
    listMcpTools.mockResolvedValue(MCP_TOOLS);
    listUsers.mockResolvedValue(HUMANS);
  });

  it('renders every column localized — never a raw enum value', async () => {
    render(wrap(<ServiceAccountsPage />));

    const row = (await screen.findByText('CI bot')).closest('tr');
    expect(row).not.toBeNull();
    const cells = within(row!);
    expect(cells.getByText('ci-bot@example.com')).toBeInTheDocument();
    expect(cells.getByText('Read-only')).toBeInTheDocument();
    expect(cells.getByText('Alice')).toBeInTheDocument();
    expect(cells.getByText('Active')).toBeInTheDocument();
    expect(cells.getByTestId('managed-by-tag')).toHaveTextContent('UI');
    expect(cells.getByText('1')).toBeInTheDocument();
    expect(await cells.findByText('2 / 12 tools')).toBeInTheDocument();
    expect(cells.getByText('60/min')).toBeInTheDocument();

    const bootstrapRow = screen.getByText('Terraform').closest('tr');
    const bcells = within(bootstrapRow!);
    expect(bcells.getByTestId('managed-by-tag')).toHaveTextContent('Bootstrap');
    expect(bcells.getByText('Inactive')).toBeInTheDocument();
    expect(bcells.getByText('All tools')).toBeInTheDocument();
    expect(bcells.getByText('500/day')).toBeInTheDocument();
    expect(bcells.getByText('Never')).toBeInTheDocument();

    // Column headers are localized too; the wire values never reach the DOM.
    expect(screen.getByRole('columnheader', { name: 'Managed by' })).toBeInTheDocument();
    expect(screen.queryByText(/^(BOOTSTRAP|SERVICE_ACCOUNT|READONLY|HUMAN)$/)).not.toBeInTheDocument();
  });

  it('applies the managed-by filter server-side and resets the page', async () => {
    render(wrap(<ServiceAccountsPage />));
    await screen.findByText('CI bot');
    expect(listServiceAccounts).toHaveBeenCalledWith({ page: 0, size: 20 });

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Managed by' }));
    fireEvent.click(await screen.findByTitle('Bootstrap'));

    await waitFor(() =>
      expect(listServiceAccounts).toHaveBeenCalledWith({ page: 0, size: 20, managed_by: 'BOOTSTRAP' }),
    );
  });

  it('creates an account from the modal with the READONLY default and navigates to it', async () => {
    createServiceAccount.mockResolvedValue(account({ id: 'sa-new' }));

    render(wrap(<ServiceAccountsPage />));
    await screen.findByText('CI bot');

    fireEvent.click(screen.getByRole('button', { name: /Create service account/ }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: 'Role' })).toBeInTheDocument());

    fireEvent.change(within(dialog).getByLabelText('Email'), { target: { value: 'new-bot@example.com' } });
    fireEvent.change(within(dialog).getByLabelText('Display name'), { target: { value: 'New bot' } });
    fireEvent.change(within(dialog).getByLabelText('Description'), { target: { value: 'nightly' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));

    await waitFor(() =>
      expect(createServiceAccount).toHaveBeenCalledWith({
        email: 'new-bot@example.com',
        display_name: 'New bot',
        role_id: 'r-readonly',
        description: 'nightly',
      }),
    );
    expect(await screen.findByText('settings-route')).toBeInTheDocument();
  });

  it('warns when a review-capable role is picked', async () => {
    render(wrap(<ServiceAccountsPage />));
    await screen.findByText('CI bot');

    fireEvent.click(screen.getByRole('button', { name: /Create service account/ }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: 'Role' })).toBeInTheDocument());
    expect(screen.queryByTestId('review-role-warning')).not.toBeInTheDocument();

    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: 'Role' }));
    fireEvent.click(await screen.findByTitle('Reviewer'));

    expect(await screen.findByTestId('review-role-warning')).toBeInTheDocument();
  });

  it('rejects an invalid email client-side before calling the API', async () => {
    render(wrap(<ServiceAccountsPage />));
    await screen.findByText('CI bot');

    fireEvent.click(screen.getByRole('button', { name: /Create service account/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Email'), { target: { value: 'not-an-email' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(dialog.querySelector('.ant-form-item-explain-error')).not.toBeNull());
    expect(createServiceAccount).not.toHaveBeenCalled();
  });

  it('surfaces the server error for a create conflict', async () => {
    createServiceAccount.mockRejectedValue(axiosError(409, { error: 'EMAIL_ALREADY_EXISTS' }));

    render(wrap(<ServiceAccountsPage />));
    await screen.findByText('CI bot');
    fireEvent.click(screen.getByRole('button', { name: /Create service account/ }));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: 'Role' })).toBeInTheDocument());
    fireEvent.change(within(dialog).getByLabelText('Email'), { target: { value: 'dup@example.com' } });
    fireEvent.change(within(dialog).getByLabelText('Display name'), { target: { value: 'Dup' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));

    expect(await screen.findByText('A user with that email already exists.')).toBeInTheDocument();
  });

  it('shows the empty state and the load error', async () => {
    listServiceAccounts.mockResolvedValueOnce(page([]));
    const { unmount } = render(wrap(<ServiceAccountsPage />));
    expect(await screen.findByText('No service accounts yet')).toBeInTheDocument();
    unmount();

    listServiceAccounts.mockRejectedValueOnce(new Error('boom'));
    render(wrap(<ServiceAccountsPage />));
    expect(await screen.findByText('Could not load service accounts')).toBeInTheDocument();
  });
});
