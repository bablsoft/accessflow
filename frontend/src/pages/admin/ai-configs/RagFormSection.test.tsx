import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App, Form } from 'antd';
import type { FormInstance } from 'antd';
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

function Host({
  ragEnabled,
  initialValues,
  onForm,
}: {
  ragEnabled: boolean;
  initialValues?: Record<string, unknown>;
  onForm?: (form: FormInstance) => void;
}) {
  const [form] = Form.useForm();
  onForm?.(form);
  return (
    <Form form={form} initialValues={{ rag_enabled: ragEnabled, ...initialValues }}>
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

  // --- AF-918: Voyage AI ---

  /** Opens the embedding-provider dropdown and picks the option with this exact label. */
  async function pickEmbeddingProvider(label: string) {
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Embedding provider' }));
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('.ant-select-item-option-content')].length,
      ).toBeGreaterThan(0),
    );
    const option = [...document.querySelectorAll('.ant-select-item-option-content')].find(
      (o) => o.textContent === label,
    );
    expect(option).toBeDefined();
    fireEvent.click(option!);
  }

  it('offers Voyage in the embedding-provider dropdown', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });
    render(wrap(<Host ragEnabled />));

    await pickEmbeddingProvider('Voyage AI');

    await waitFor(() => expect(screen.getByLabelText('Vector dimensions')).toBeInTheDocument());
  });

  it('prefills the Voyage endpoint, model and dimensions when Voyage is chosen', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });
    let form: FormInstance | undefined;
    render(wrap(<Host ragEnabled onForm={(f) => (form = f)} />));

    await pickEmbeddingProvider('Voyage AI');

    await waitFor(() => expect(form?.getFieldValue('embedding_model')).toBe('voyage-4'));
    expect(form?.getFieldValue('embedding_endpoint')).toBe('https://api.voyageai.com/v1');
    expect(form?.getFieldValue('embedding_dimensions')).toBe(1024);
  });

  it('undoes the whole Voyage prefill when switching away', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });
    let form: FormInstance | undefined;
    render(wrap(<Host ragEnabled onForm={(f) => (form = f)} />));

    await pickEmbeddingProvider('Voyage AI');
    await waitFor(() => expect(form?.getFieldValue('embedding_model')).toBe('voyage-4'));

    await pickEmbeddingProvider('OpenAI');

    // Leaving voyage-4 behind would save a Voyage model name against a provider that has never
    // heard of it.
    await waitFor(() => expect(form?.getFieldValue('embedding_model')).toBe(''));
    expect(form?.getFieldValue('embedding_endpoint')).toBe('');
    expect(form?.getFieldValue('embedding_dimensions')).toBeNull();
  });

  it('leaves a hand-typed model alone when switching away from Voyage', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });
    let form: FormInstance | undefined;
    render(
      wrap(
        <Host
          ragEnabled
          initialValues={{ embedding_provider: 'VOYAGE', embedding_model: 'voyage-3-large' }}
          onForm={(f) => (form = f)}
        />,
      ),
    );
    await waitFor(() => expect(getRagCapabilitiesMock).toHaveBeenCalled());

    await pickEmbeddingProvider('OpenAI');

    // Only the values this component wrote are cleared.
    await waitFor(() => expect(form?.getFieldValue('embedding_dimensions')).toBeNull());
    expect(form?.getFieldValue('embedding_model')).toBe('voyage-3-large');
  });

  it("says the API key is Voyage's, not Anthropic's", async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });

    render(wrap(<Host ragEnabled initialValues={{ embedding_provider: 'VOYAGE' }} />));

    const help = await screen.findByText(/Voyage AI is a separate vendor/i);
    expect(help).toHaveTextContent('not an Anthropic one');
  });

  // The field must be mounted for every provider that can honour a width, not just Voyage: an
  // unmounted Form.Item is absent from onFinish's values, and the edit page would then be unable to
  // tell "unchanged" from "cleared" — wiping a dimension set over the API on any unrelated save.
  it('shows the vector-dimensions field for every provider that can honour a width', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });

    for (const provider of ['VOYAGE', 'OPENAI', 'OPENAI_COMPATIBLE', 'HUGGING_FACE']) {
      const { unmount } = render(
        wrap(<Host ragEnabled initialValues={{ embedding_provider: provider }} />),
      );
      expect(await screen.findByLabelText('Vector dimensions')).toBeInTheDocument();
      unmount();
    }
  });

  it('hides the vector-dimensions field for Ollama, which has no such knob', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });

    render(wrap(<Host ragEnabled initialValues={{ embedding_provider: 'OLLAMA' }} />));

    await waitFor(() => expect(getRagCapabilitiesMock).toHaveBeenCalled());
    expect(screen.queryByLabelText('Vector dimensions')).not.toBeInTheDocument();
  });

  it('offers Voyage only its four widths, but a free number for OpenAI', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });

    const { unmount } = render(
      wrap(<Host ragEnabled initialValues={{ embedding_provider: 'VOYAGE' }} />),
    );
    // Voyage renders a Select (combobox); OpenAI renders a free spinbutton.
    expect(await screen.findByRole('combobox', { name: /Vector dimensions/ })).toBeInTheDocument();
    unmount();

    render(wrap(<Host ragEnabled initialValues={{ embedding_provider: 'OPENAI' }} />));
    expect(
      await screen.findByRole('spinbutton', { name: /Vector dimensions/ }),
    ).toBeInTheDocument();
  });

  it('warns that Voyage cannot write to the in-app pgvector store', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });

    render(
      wrap(
        <Host
          ragEnabled
          initialValues={{ embedding_provider: 'VOYAGE', rag_store_type: 'PGVECTOR' }}
        />,
      ),
    );

    expect(
      await screen.findByText(/Voyage cannot write to this in-app vector store/i),
    ).toBeInTheDocument();
  });

  it('does not warn about pgvector when Voyage is paired with Qdrant', async () => {
    getRagCapabilitiesMock.mockResolvedValue({ pgvector_available: true });

    render(
      wrap(
        <Host
          ragEnabled
          initialValues={{ embedding_provider: 'VOYAGE', rag_store_type: 'QDRANT' }}
        />,
      ),
    );

    await waitFor(() => expect(getRagCapabilitiesMock).toHaveBeenCalled());
    expect(
      screen.queryByText(/Voyage cannot write to this in-app vector store/i),
    ).not.toBeInTheDocument();
  });
});
