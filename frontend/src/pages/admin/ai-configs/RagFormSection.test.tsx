import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App, Form } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { RagFormSection } from './RagFormSection';

const { getRagCapabilitiesMock } = vi.hoisted(() => ({
  getRagCapabilitiesMock: vi.fn(),
}));

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, getRagCapabilities: getRagCapabilitiesMock };
});

function Host({ ragEnabled }: { ragEnabled: boolean }) {
  const [form] = Form.useForm();
  return (
    <Form form={form} initialValues={{ rag_enabled: ragEnabled }}>
      <RagFormSection form={form} />
    </Form>
  );
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

const WARNING = /In-app pgvector storage is unavailable/i;

describe('RagFormSection', () => {
  beforeEach(() => {
    getRagCapabilitiesMock.mockReset();
  });

  it('warns when pgvector is unavailable and RAG is enabled', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: false });

    render(wrap(<Host ragEnabled />));

    expect(await screen.findByText(WARNING)).toBeInTheDocument();
  });

  it('does not warn when pgvector is available', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });

    render(wrap(<Host ragEnabled />));

    // Once capabilities resolve to available, the warning must not appear.
    await waitFor(() => expect(getRagCapabilitiesMock).toHaveBeenCalled());
    expect(screen.queryByText(WARNING)).not.toBeInTheDocument();
  });

  it('does not warn when RAG is disabled, even if pgvector is unavailable', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: false });

    render(wrap(<Host ragEnabled={false} />));

    await waitFor(() => expect(screen.queryByText(WARNING)).not.toBeInTheDocument());
  });
});
