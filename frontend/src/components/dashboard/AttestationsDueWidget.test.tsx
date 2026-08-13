import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AttestationItem, AttestationItemPage } from '@/types/api';

const { listWorklist } = vi.hoisted(() => ({ listWorklist: vi.fn() }));

vi.mock('@/api/attestation', async () => {
  const actual = await vi.importActual<typeof import('@/api/attestation')>('@/api/attestation');
  return { ...actual, listAttestationWorklist: listWorklist };
});

const { AttestationsDueWidget } = await import('./AttestationsDueWidget');

function item(overrides: Partial<AttestationItem> = {}): AttestationItem {
  return {
    id: 'it-1',
    campaign_id: 'c-1',
    permission_id: 'p-1',
    datasource_id: 'ds-1',
    datasource_name: 'Prod',
    subject_user_id: 'u-2',
    subject_user_email: 'subject@x.io',
    subject_user_display_name: 'Subject',
    can_read: true,
    can_write: false,
    can_ddl: false,
    can_break_glass: false,
    permission_expires_at: '2026-09-01T00:00:00Z',
    permission_created_at: '2026-01-01T00:00:00Z',
    usage_last_used_at: null,
    usage_count: 0,
    usage_granted_target_count: 4,
    usage_used_target_count: 0,
    usage_recommendation: 'NEVER_USED',
    decision: 'PENDING',
    close_reason: null,
    decided_by: null,
    decided_at: null,
    decision_comment: null,
    created_at: '2026-06-01T00:00:00Z',
    ...overrides,
  };
}

function page(content: AttestationItem[]): AttestationItemPage {
  return { content, page: 0, size: 5, total_elements: content.length, total_pages: 1 };
}

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<AttestationsDueWidget />, { wrapper });
}

describe('AttestationsDueWidget', () => {
  beforeEach(() => {
    listWorklist.mockResolvedValue(page([item()]));
  });

  it('renders worklist rows with the usage-recommendation pill and expiry', async () => {
    renderWidget();
    expect(await screen.findByText('subject@x.io')).toBeInTheDocument();
    expect(screen.getByText('Prod')).toBeInTheDocument();
    expect(screen.getByText(/expires/i)).toBeInTheDocument();
    const viewAll = screen.getByRole('link', { name: /view all/i });
    expect(viewAll).toHaveAttribute('href', '/reviews/attestations');
  });

  it('omits the usage pill when the recommendation is null (no data ≠ judgement)', async () => {
    listWorklist.mockResolvedValue(page([item({ usage_recommendation: null })]));
    const { container } = renderWidget();
    expect(await screen.findByText('subject@x.io')).toBeInTheDocument();
    expect(container.querySelector('.af-activity-pills')).toBeNull();
  });

  it('shows the compact empty state when the worklist is empty', async () => {
    listWorklist.mockResolvedValue(page([]));
    renderWidget();
    expect(await screen.findByText(/no attestation items/i)).toBeInTheDocument();
  });
});
