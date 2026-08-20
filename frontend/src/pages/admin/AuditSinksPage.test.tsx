import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AuditSink } from '@/types/api';

const {
  listAuditSinksMock,
  createAuditSinkMock,
  updateAuditSinkMock,
  deleteAuditSinkMock,
  testAuditSinkMock,
} = vi.hoisted(() => ({
  listAuditSinksMock: vi.fn(),
  createAuditSinkMock: vi.fn(),
  updateAuditSinkMock: vi.fn(),
  deleteAuditSinkMock: vi.fn(),
  testAuditSinkMock: vi.fn(),
}));

vi.mock('@/api/auditSinks', async () => {
  const actual = await vi.importActual<typeof import('@/api/auditSinks')>('@/api/auditSinks');
  return {
    ...actual,
    listAuditSinks: listAuditSinksMock,
    createAuditSink: createAuditSinkMock,
    updateAuditSink: updateAuditSinkMock,
    deleteAuditSink: deleteAuditSinkMock,
    testAuditSink: testAuditSinkMock,
  };
});

const { default: AuditSinksPage } = await import('./AuditSinksPage');

function splunkSink(overrides: Partial<AuditSink> = {}): AuditSink {
  return {
    id: 'as-1',
    organization_id: 'org-1',
    name: 'SOC Splunk',
    type: 'SPLUNK_HEC',
    config: {
      url: 'https://splunk.example.com:8088/services/collector/event',
      token: '********',
      index: 'accessflow',
      source: 'accessflow',
    },
    enabled: true,
    cursor_created_at: '2026-08-19T09:00:00Z',
    last_success_at: '2026-08-19T09:05:00Z',
    last_error: null,
    consecutive_failures: 0,
    next_attempt_at: null,
    behind_count: 0,
    behind_count_capped: false,
    created_at: '2026-08-19T08:00:00Z',
    updated_at: '2026-08-19T09:05:00Z',
    ...overrides,
  };
}

function failingSyslogSink(): AuditSink {
  return splunkSink({
    id: 'as-2',
    name: 'SOC Syslog',
    type: 'SYSLOG_CEF',
    config: { host: 'siem.example.com', port: 6514, protocol: 'TLS' },
    enabled: false,
    last_success_at: null,
    last_error: 'Connection refused: siem.example.com:6514',
    consecutive_failures: 4,
    next_attempt_at: '2026-08-19T09:15:00Z',
    behind_count: 1000,
    behind_count_capped: true,
  });
}

function axiosError(status: number, data: unknown): AxiosError {
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

describe('AuditSinksPage', () => {
  beforeEach(() => {
    listAuditSinksMock.mockReset();
    createAuditSinkMock.mockReset();
    updateAuditSinkMock.mockReset();
    deleteAuditSinkMock.mockReset();
    testAuditSinkMock.mockReset();
  });

  it('renders a card per sink with the per-sink health block', async () => {
    listAuditSinksMock.mockResolvedValue([splunkSink(), failingSyslogSink()]);

    render(wrap(<AuditSinksPage />));

    expect(screen.getByText('Audit sinks')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-sink-card')).toHaveLength(2);
    });
    expect(screen.getByText('SOC Splunk')).toBeInTheDocument();
    expect(screen.getByText('Splunk HEC')).toBeInTheDocument();
    expect(screen.getByText('Syslog (CEF)')).toBeInTheDocument();

    // Masked secrets never surface on the card.
    expect(screen.queryByText(/\*{8}/)).not.toBeInTheDocument();

    // The failing sink shows a capped behind-count, its error, and the disabled pill.
    const failingCard = screen
      .getAllByTestId('audit-sink-card')
      .find((c) => within(c).queryByText('SOC Syslog'));
    expect(failingCard).toBeDefined();
    expect(within(failingCard!).getByText('1000+')).toBeInTheDocument();
    expect(within(failingCard!).getByText('4')).toBeInTheDocument();
    expect(
      within(failingCard!).getByText('Connection refused: siem.example.com:6514'),
    ).toBeInTheDocument();
    expect(within(failingCard!).getByText('disabled')).toBeInTheDocument();
  });

  it('shows the empty state when no sinks exist', async () => {
    listAuditSinksMock.mockResolvedValue([]);

    render(wrap(<AuditSinksPage />));

    await waitFor(() => {
      expect(
        screen.getByText(
          'No audit sinks yet — add one to stream the audit log to your SIEM or WORM storage.',
        ),
      ).toBeInTheDocument();
    });
  });

  it('shows the load-error state when the list request fails', async () => {
    listAuditSinksMock.mockRejectedValue(new Error('boom'));

    render(wrap(<AuditSinksPage />));

    await waitFor(() => {
      expect(screen.getByText('Failed to load audit sinks')).toBeInTheDocument();
    });
  });

  it('creates a SPLUNK_HEC sink from the modal', async () => {
    listAuditSinksMock.mockResolvedValue([]);
    createAuditSinkMock.mockResolvedValue(splunkSink());

    render(wrap(<AuditSinksPage />));
    await waitFor(() => expect(listAuditSinksMock).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /Add sink/ }));
    expect(
      await screen.findByText('Add audit sink', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'SOC Splunk' } });
    // SPLUNK_HEC is the default type — its fields are visible immediately.
    fireEvent.change(screen.getByLabelText('HEC endpoint URL'), {
      target: { value: 'https://splunk.example.com:8088/services/collector/event' },
    });
    fireEvent.change(screen.getByLabelText('HEC token'), { target: { value: 'hec-token' } });
    fireEvent.change(screen.getByLabelText('Index (optional)'), {
      target: { value: 'accessflow' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Create sink' }));

    await waitFor(() => {
      expect(createAuditSinkMock).toHaveBeenCalledWith({
        name: 'SOC Splunk',
        type: 'SPLUNK_HEC',
        config: {
          url: 'https://splunk.example.com:8088/services/collector/event',
          token: 'hec-token',
          index: 'accessflow',
        },
      });
    });
    expect(await screen.findByText('Audit sink created')).toBeInTheDocument();
  });

  it('does not submit when required fields are missing', async () => {
    listAuditSinksMock.mockResolvedValue([]);

    render(wrap(<AuditSinksPage />));
    await waitFor(() => expect(listAuditSinksMock).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /Add sink/ }));
    expect(
      await screen.findByText('Add audit sink', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Create sink' }));

    // Inline validation blocks the POST — the modal stays open, nothing is sent.
    await waitFor(() => {
      expect(document.querySelectorAll('.ant-form-item-explain-error').length).toBeGreaterThan(0);
    });
    expect(createAuditSinkMock).not.toHaveBeenCalled();
  });

  it('edits a sink, passing the masked token through so the backend preserves it', async () => {
    listAuditSinksMock.mockResolvedValue([splunkSink()]);
    updateAuditSinkMock.mockResolvedValue(splunkSink({ name: 'SOC Splunk v2' }));

    render(wrap(<AuditSinksPage />));
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-sink-card')).toHaveLength(1);
    });

    fireEvent.click(screen.getByRole('button', { name: /Edit/ }));
    expect(
      await screen.findByText('Edit sink · SOC Splunk', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();

    // Type is immutable when editing.
    const typeSelect = screen.getByLabelText('Type');
    expect(typeSelect).toBeDisabled();
    // The stored token round-trips masked.
    expect(screen.getByLabelText('HEC token')).toHaveValue('********');

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'SOC Splunk v2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => {
      expect(updateAuditSinkMock).toHaveBeenCalledWith('as-1', {
        name: 'SOC Splunk v2',
        enabled: true,
        config: {
          url: 'https://splunk.example.com:8088/services/collector/event',
          token: '********',
          index: 'accessflow',
          source: 'accessflow',
        },
      });
    });
    expect(await screen.findByText('Audit sink updated')).toBeInTheDocument();
  });

  it('shows a success toast when the test delivery succeeds', async () => {
    listAuditSinksMock.mockResolvedValue([splunkSink()]);
    testAuditSinkMock.mockResolvedValue({ status: 'OK', detail: 'Test event delivered' });

    render(wrap(<AuditSinksPage />));
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-sink-card')).toHaveLength(1);
    });

    fireEvent.click(screen.getByRole('button', { name: /Test/ }));

    await waitFor(() => {
      expect(testAuditSinkMock).toHaveBeenCalledWith('as-1');
    });
    expect(await screen.findByText('Test event delivered')).toBeInTheDocument();
  });

  it('surfaces the backend detail when the test delivery fails with 502', async () => {
    listAuditSinksMock.mockResolvedValue([splunkSink()]);
    testAuditSinkMock.mockRejectedValue(
      axiosError(502, {
        error: 'AUDIT_SINK_TEST_FAILED',
        title: 'Bad Gateway',
        detail: 'Splunk HEC returned 403',
      }),
    );

    render(wrap(<AuditSinksPage />));
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-sink-card')).toHaveLength(1);
    });

    fireEvent.click(screen.getByRole('button', { name: /Test/ }));

    expect(await screen.findByText('Splunk HEC returned 403')).toBeInTheDocument();
  });

  it('deletes a sink through the Popconfirm', async () => {
    listAuditSinksMock.mockResolvedValue([splunkSink()]);
    deleteAuditSinkMock.mockResolvedValue(undefined);

    render(wrap(<AuditSinksPage />));
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-sink-card')).toHaveLength(1);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Delete sink' }));
    expect(await screen.findByText('Delete audit sink?')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => {
      expect(deleteAuditSinkMock).toHaveBeenCalledWith('as-1');
    });
    expect(await screen.findByText('Audit sink deleted')).toBeInTheDocument();
  });
});
