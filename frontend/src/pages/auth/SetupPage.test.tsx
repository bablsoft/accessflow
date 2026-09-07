import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import { useSetupStore } from '@/store/setupStore';

const submitSetup = vi.fn();
const updateSystemSmtp = vi.fn();
const navigate = vi.fn();

vi.mock('@/api/setup', () => ({
  submitSetup: (...args: unknown[]) => submitSetup(...args),
  getSetupStatus: vi.fn(),
}));

vi.mock('@/api/admin', () => ({
  updateSystemSmtp: (...args: unknown[]) => updateSystemSmtp(...args),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigate };
});

const { SetupPage } = await import('./SetupPage');

function wrap(node: ReactNode) {
  return <MemoryRouter>{node}</MemoryRouter>;
}

const session = {
  access_token: 'tok',
  expires_in: 900,
  user: {
    id: 'u-1',
    email: 'admin@example.com',
    display_name: 'Ada',
    role: 'ADMIN' as const,
    role_id: null,
    permissions: [],
    auth_provider: 'LOCAL' as const,
    totp_enabled: false,
    platform_admin: false,
    preferred_language: 'en',
  },
};

/** Fills the account step and advances to the domains step. */
async function completeAccountStep(): Promise<void> {
  fireEvent.change(screen.getByLabelText('Organization name'), {
    target: { value: 'Acme Inc.' },
  });
  fireEvent.change(screen.getByLabelText('Email'), {
    target: { value: 'admin@example.com' },
  });
  fireEvent.change(screen.getByLabelText('Password'), {
    target: { value: 'Password123!' },
  });
  fireEvent.change(screen.getByLabelText('Confirm password'), {
    target: { value: 'Password123!' },
  });
  // The AntD arrow icon folds its aria-label into the button's accessible name.
  fireEvent.click(screen.getByRole('button', { name: /Continue/ }));
  await screen.findByText('What will you govern?');
}

describe('SetupPage', () => {
  beforeEach(() => {
    submitSetup.mockReset();
    updateSystemSmtp.mockReset();
    navigate.mockReset();
    submitSetup.mockResolvedValue(session);
    useAuthStore.setState({ user: null, accessToken: null });
    useSetupStore.setState({ setupRequired: true });
  });

  it('starts on the account step and fires no request while collecting it', async () => {
    render(wrap(<SetupPage />));

    expect(screen.getByText('Create the first admin')).toBeInTheDocument();
    await completeAccountStep();

    // /auth/setup is one-shot, so it must not fire until the domain answer exists.
    expect(submitSetup).not.toHaveBeenCalled();
  });

  it('shows both domain switches off by default with a docs link each', async () => {
    render(wrap(<SetupPage />));
    await completeAccountStep();

    const apis = screen.getByLabelText('Govern outbound API calls');
    const deployments = screen.getByLabelText('Gate CI/CD deployments');
    expect(apis).not.toBeChecked();
    expect(deployments).not.toBeChecked();
    // Distinct accessible names — two identical "Learn more" links read the same out of context.
    expect(
      screen.getByRole('link', { name: 'Learn more about API governance' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Learn more about deployment governance' }),
    ).toBeInTheDocument();
  });

  it('submits the toggled domains with the account payload', async () => {
    render(wrap(<SetupPage />));
    await completeAccountStep();

    fireEvent.click(screen.getByLabelText('Govern outbound API calls'));
    fireEvent.click(screen.getByRole('button', { name: /^Create admin$/ }));

    await waitFor(() => expect(submitSetup).toHaveBeenCalledTimes(1));
    expect(submitSetup.mock.calls[0]?.[0]).toEqual({
      organization_name: 'Acme Inc.',
      email: 'admin@example.com',
      password: 'Password123!',
      governs_apis: true,
      governs_deployments: false,
    });
    await screen.findByText('Configure system SMTP (optional)');
    expect(useAuthStore.getState().accessToken).toBe('tok');
    expect(useSetupStore.getState().setupRequired).toBe(false);
  });

  it('skipping the domain step still creates the admin with both flags false', async () => {
    render(wrap(<SetupPage />));
    await completeAccountStep();

    fireEvent.click(screen.getByRole('button', { name: /Skip — databases only/ }));

    await waitFor(() => expect(submitSetup).toHaveBeenCalledTimes(1));
    expect(submitSetup.mock.calls[0]?.[0]).toMatchObject({
      governs_apis: false,
      governs_deployments: false,
    });
    await screen.findByText('Configure system SMTP (optional)');
  });

  it('keeps the user on the domain step and surfaces the error when setup fails', async () => {
    submitSetup.mockRejectedValue({
      isAxiosError: true,
      response: {
        status: 409,
        data: { title: 'Setup already completed', status: 409 },
      },
    });
    render(wrap(<SetupPage />));
    await completeAccountStep();

    fireEvent.click(screen.getByRole('button', { name: /^Create admin$/ }));

    await screen.findByRole('alert');
    expect(screen.getByText('What will you govern?')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /Skip — databases only/ }),
    ).toBeEnabled();
  });

  it('going back from the domain step preserves what was typed', async () => {
    render(wrap(<SetupPage />));
    await completeAccountStep();

    fireEvent.click(screen.getByRole('button', { name: /^Back$/ }));

    await screen.findByText('Create the first admin');
    expect(screen.getByLabelText('Organization name')).toHaveValue('Acme Inc.');
    expect(screen.getByLabelText('Email')).toHaveValue('admin@example.com');
    expect(submitSetup).not.toHaveBeenCalled();
  });

  it('offers a way back after a server-side rejection', async () => {
    submitSetup.mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { title: 'Email already exists', status: 409 } },
    });
    render(wrap(<SetupPage />));
    await completeAccountStep();
    fireEvent.click(screen.getByRole('button', { name: /^Create admin$/ }));
    await screen.findByRole('alert');

    // The offending field lives on the account step — the user must be able to reach it.
    fireEvent.click(screen.getByRole('button', { name: /^Back$/ }));

    await screen.findByText('Create the first admin');
    expect(screen.getByLabelText('Email')).toHaveValue('admin@example.com');
  });

  it('skipping SMTP lands on /queries', async () => {
    render(wrap(<SetupPage />));
    await completeAccountStep();
    fireEvent.click(screen.getByRole('button', { name: /Skip — databases only/ }));
    await screen.findByText('Configure system SMTP (optional)');

    fireEvent.click(screen.getByRole('button', { name: /Skip for now/ }));

    expect(navigate).toHaveBeenCalledWith('/queries');
    expect(updateSystemSmtp).not.toHaveBeenCalled();
  });
});
