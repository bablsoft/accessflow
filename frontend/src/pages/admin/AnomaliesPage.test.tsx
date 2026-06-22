import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AnomalyPage, BehaviorAnomaly, DatasourcePage } from '@/types/api';

const { listAnomaliesMock, acknowledgeAnomalyMock, dismissAnomalyMock, listDatasourcesMock } =
  vi.hoisted(() => ({
    listAnomaliesMock: vi.fn(),
    acknowledgeAnomalyMock: vi.fn(),
    dismissAnomalyMock: vi.fn(),
    listDatasourcesMock: vi.fn(),
  }));

vi.mock('@/api/anomalies', async () => {
  const actual = await vi.importActual<typeof import('@/api/anomalies')>('@/api/anomalies');
  return {
    ...actual,
    listAnomalies: listAnomaliesMock,
    acknowledgeAnomaly: acknowledgeAnomalyMock,
    dismissAnomaly: dismissAnomalyMock,
  };
});

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return {
    ...actual,
    listDatasources: listDatasourcesMock,
  };
});

// Charts library mocked so tests stay deterministic and don't render canvas.
vi.mock('@ant-design/charts', () => ({
  Column: ({ data }: { data: unknown[] }) => (
    <div data-testid="ant-column-chart" data-points={data.length} />
  ),
}));

const { default: AnomaliesPage } = await import('./AnomaliesPage');

function anomaly(overrides: Partial<BehaviorAnomaly> = {}): BehaviorAnomaly {
  return {
    id: 'an-1',
    user_id: 'u-1',
    user_display_name: 'Alice',
    user_email: 'alice@example.com',
    datasource_id: 'ds-1',
    datasource_name: 'Prod',
    feature: 'rows_read_per_hour',
    score: 4.2,
    observed_value: 9000,
    baseline_mean: 1200,
    baseline_stddev: 300,
    detail: { window: '1h' },
    ai_summary: 'Read volume far above baseline.',
    status: 'OPEN',
    detected_at: '2026-06-20T10:00:00Z',
    acknowledged_by: null,
    acknowledged_at: null,
    window_start: '2026-06-20T09:00:00Z',
    window_end: '2026-06-20T10:00:00Z',
    ...overrides,
  };
}

function pageOf(content: BehaviorAnomaly[]): AnomalyPage {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: content.length === 0 ? 0 : 1,
  };
}

function emptyDatasources(): DatasourcePage {
  return { content: [], page: 0, size: 100, total_elements: 0, total_pages: 0, last: true };
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

describe('AnomaliesPage', () => {
  beforeEach(() => {
    listAnomaliesMock.mockReset();
    acknowledgeAnomalyMock.mockReset();
    dismissAnomalyMock.mockReset();
    listDatasourcesMock.mockReset();
    listDatasourcesMock.mockResolvedValue(emptyDatasources());
  });

  it('renders the page title, chart and a row when anomalies are present', async () => {
    listAnomaliesMock.mockResolvedValue(pageOf([anomaly()]));

    render(wrap(<AnomaliesPage />));

    expect(screen.getByText('Anomalies')).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByTestId('anomalies-feature-chart')).toBeInTheDocument();
    });
    expect(screen.getByTestId('ant-column-chart')).toHaveAttribute('data-points', '1');
    expect(screen.getByText('rows_read_per_hour')).toBeInTheDocument();
    expect(screen.getByText('Alice')).toBeInTheDocument();
  });

  it('shows an empty state when there are no anomalies', async () => {
    listAnomaliesMock.mockResolvedValue(pageOf([]));

    render(wrap(<AnomaliesPage />));

    await waitFor(() => {
      expect(screen.getByText('No anomalies')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('anomalies-feature-chart')).not.toBeInTheDocument();
  });

  it('sends OPEN as the default status filter', async () => {
    listAnomaliesMock.mockResolvedValue(pageOf([]));

    render(wrap(<AnomaliesPage />));

    await waitFor(() => {
      expect(listAnomaliesMock).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'OPEN' }),
      );
    });
  });

  it('refetches when the refresh button is clicked', async () => {
    listAnomaliesMock.mockResolvedValue(pageOf([]));

    render(wrap(<AnomaliesPage />));

    await waitFor(() => {
      expect(listAnomaliesMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole('button', { name: /refresh/i }));

    await waitFor(() => {
      expect(listAnomaliesMock).toHaveBeenCalledTimes(2);
    });
  });

  it('acknowledges an anomaly after confirming the modal', async () => {
    listAnomaliesMock.mockResolvedValue(pageOf([anomaly()]));
    acknowledgeAnomalyMock.mockResolvedValue(anomaly({ status: 'ACKNOWLEDGED' }));

    render(wrap(<AnomaliesPage />));

    await waitFor(() => {
      expect(screen.getByTestId('acknowledge-an-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('acknowledge-an-1'));

    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: /Acknowledge/ });
    fireEvent.click(confirm);

    await waitFor(() => {
      expect(acknowledgeAnomalyMock).toHaveBeenCalledWith('an-1');
    });
  });

  it('dismisses an anomaly after confirming the modal', async () => {
    listAnomaliesMock.mockResolvedValue(pageOf([anomaly()]));
    dismissAnomalyMock.mockResolvedValue(anomaly({ status: 'DISMISSED' }));

    render(wrap(<AnomaliesPage />));

    await waitFor(() => {
      expect(screen.getByTestId('dismiss-an-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('dismiss-an-1'));

    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: /Dismiss/ });
    fireEvent.click(confirm);

    await waitFor(() => {
      expect(dismissAnomalyMock).toHaveBeenCalledWith('an-1');
    });
  });

  it('disables the acknowledge action for an already-acknowledged anomaly', async () => {
    listAnomaliesMock.mockResolvedValue(pageOf([anomaly({ status: 'ACKNOWLEDGED' })]));

    render(wrap(<AnomaliesPage />));

    await waitFor(() => {
      expect(screen.getByTestId('acknowledge-an-1')).toBeDisabled();
    });
  });
});
