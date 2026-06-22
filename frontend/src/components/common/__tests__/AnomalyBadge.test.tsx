import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';

const { fetchAnomalyBadgeMock } = vi.hoisted(() => ({
  fetchAnomalyBadgeMock: vi.fn(),
}));

vi.mock('@/api/anomalies', async () => {
  const actual = await vi.importActual<typeof import('@/api/anomalies')>('@/api/anomalies');
  return {
    ...actual,
    fetchAnomalyBadge: fetchAnomalyBadgeMock,
  };
});

const { AnomalyBadge } = await import('../AnomalyBadge');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AnomalyBadge', () => {
  beforeEach(() => {
    fetchAnomalyBadgeMock.mockReset();
  });

  it('renders a chip from controlled props without fetching', () => {
    render(wrap(<AnomalyBadge openCount={3} maxScore={5.1} />));
    expect(screen.getByTestId('anomaly-badge')).toBeInTheDocument();
    expect(screen.getByText('3 anomalies')).toBeInTheDocument();
    expect(fetchAnomalyBadgeMock).not.toHaveBeenCalled();
  });

  it('renders nothing when the controlled open count is zero', () => {
    const { container } = render(wrap(<AnomalyBadge openCount={0} maxScore={0} />));
    expect(container).toBeEmptyDOMElement();
    expect(fetchAnomalyBadgeMock).not.toHaveBeenCalled();
  });

  it('fetches the badge when no count is supplied and renders the result', async () => {
    fetchAnomalyBadgeMock.mockResolvedValue({ openCount: 1, maxScore: 2 });
    render(wrap(<AnomalyBadge datasourceId="ds-1" />));
    await waitFor(() => {
      expect(screen.getByTestId('anomaly-badge')).toBeInTheDocument();
    });
    expect(screen.getByText('1 anomaly')).toBeInTheDocument();
    expect(fetchAnomalyBadgeMock).toHaveBeenCalledWith('ds-1');
  });

  it('renders nothing when a self-fetch reports zero open anomalies', async () => {
    fetchAnomalyBadgeMock.mockResolvedValue({ openCount: 0, maxScore: 0 });
    const { container } = render(wrap(<AnomalyBadge />));
    await waitFor(() => {
      expect(fetchAnomalyBadgeMock).toHaveBeenCalled();
    });
    expect(container.querySelector('[data-testid="anomaly-badge"]')).toBeNull();
  });

  it('wraps the chip in a link to the anomalies dashboard when link is set', () => {
    render(wrap(<AnomalyBadge openCount={2} maxScore={3} link />));
    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', '/admin/anomalies');
  });
});
