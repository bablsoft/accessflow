import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AttestationItem, AttestationItemPage } from '@/types/api';

const { listWorklistMock, certifyItemMock, revokeItemMock, bulkDecideItemsMock } = vi.hoisted(
  () => ({
    listWorklistMock: vi.fn(),
    certifyItemMock: vi.fn(),
    revokeItemMock: vi.fn(),
    bulkDecideItemsMock: vi.fn(),
  }),
);

vi.mock('@/api/attestation', async () => {
  const actual = await vi.importActual<typeof import('@/api/attestation')>('@/api/attestation');
  return {
    ...actual,
    listAttestationWorklist: listWorklistMock,
    certifyItem: certifyItemMock,
    revokeItem: revokeItemMock,
    bulkDecideItems: bulkDecideItemsMock,
  };
});

const { default: AttestationWorklistPage } = await import('./AttestationWorklistPage');

function item(overrides: Partial<AttestationItem> = {}): AttestationItem {
  return {
    id: 'item-1',
    campaign_id: 'camp-1',
    permission_id: 'perm-1',
    datasource_id: 'ds-1',
    datasource_name: 'Prod Postgres',
    subject_user_id: 'u-1',
    subject_user_email: 'analyst@example.com',
    subject_user_display_name: 'Analyst One',
    can_read: true,
    can_write: false,
    can_ddl: false,
    can_break_glass: false,
    permission_expires_at: null,
    permission_created_at: '2026-06-01T00:00:00Z',
    decision: 'PENDING',
    close_reason: null,
    decided_by: null,
    decided_at: null,
    decision_comment: null,
    created_at: '2026-06-20T00:00:00Z',
    ...overrides,
  };
}

function pageOf(content: AttestationItem[]): AttestationItemPage {
  return {
    content,
    page: 0,
    size: 50,
    total_elements: content.length,
    total_pages: content.length === 0 ? 0 : 1,
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

describe('AttestationWorklistPage', () => {
  beforeEach(() => {
    listWorklistMock.mockReset();
    certifyItemMock.mockReset();
    revokeItemMock.mockReset();
    bulkDecideItemsMock.mockReset();
  });

  it('renders the worklist title and a row', async () => {
    listWorklistMock.mockResolvedValue(pageOf([item()]));

    render(wrap(<AttestationWorklistPage />));

    expect(screen.getByText('Attestation reviews')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText('analyst@example.com')).toBeInTheDocument();
    });
  });

  it('shows an empty state when there is nothing to review', async () => {
    listWorklistMock.mockResolvedValue(pageOf([]));

    render(wrap(<AttestationWorklistPage />));

    await waitFor(() => {
      expect(screen.getByText('Nothing to review')).toBeInTheDocument();
    });
  });

  it('certifies an item when the Certify button is clicked', async () => {
    listWorklistMock.mockResolvedValue(pageOf([item()]));
    certifyItemMock.mockResolvedValue({
      item_id: 'item-1',
      decision: 'CERTIFIED',
      was_idempotent_replay: false,
    });

    render(wrap(<AttestationWorklistPage />));

    const certify = await screen.findByRole('button', { name: /Certify/i });
    fireEvent.click(certify);

    await waitFor(() => {
      expect(certifyItemMock).toHaveBeenCalledWith('item-1');
    });
  });

  it('revokes an item only after a comment is entered in the modal', async () => {
    listWorklistMock.mockResolvedValue(pageOf([item()]));
    revokeItemMock.mockResolvedValue({
      item_id: 'item-1',
      decision: 'REVOKED',
      was_idempotent_replay: false,
    });

    render(wrap(<AttestationWorklistPage />));

    const revoke = await screen.findByRole('button', { name: /Revoke/i });
    fireEvent.click(revoke);

    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: /^Revoke$/ });
    // Comment required — confirm is disabled until a reason is typed.
    expect(confirm).toBeDisabled();

    fireEvent.change(
      within(dialog).getByPlaceholderText(/Reason for revoking this access/i),
      { target: { value: 'no longer needs prod access' } },
    );
    expect(confirm).toBeEnabled();

    fireEvent.click(confirm);

    await waitFor(() => {
      expect(revokeItemMock).toHaveBeenCalledWith('item-1', 'no longer needs prod access');
    });
  });
});
