import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import i18n from '@/i18n';
import { SampleDataPreview } from './SampleDataPreview';

const { useTableSampleMock } = vi.hoisted(() => ({ useTableSampleMock: vi.fn() }));

vi.mock('@/hooks/useTableSample', () => ({
  useTableSample: useTableSampleMock,
  SAMPLE_LIMIT: 50,
}));

function renderPreview() {
  return render(
    <I18nextProvider i18n={i18n}>
      <SampleDataPreview datasourceId="ds-1" schema="public" table="users" />
    </I18nextProvider>,
  );
}

describe('SampleDataPreview', () => {
  beforeEach(() => useTableSampleMock.mockReset());

  it('renders a skeleton while loading', () => {
    useTableSampleMock.mockReturnValue({ isLoading: true });
    const { container } = renderPreview();
    expect(container.querySelector('.ant-skeleton')).toBeInTheDocument();
  });

  it('renders an error alert on failure', () => {
    useTableSampleMock.mockReturnValue({ isError: true, error: new Error('nope') });
    renderPreview();
    expect(screen.getByText(/failed to load sample data/i)).toBeInTheDocument();
  });

  it('renders rows and badges masked columns, showing only the masked value', () => {
    useTableSampleMock.mockReturnValue({
      data: {
        columns: [
          { name: 'id', type: 'uuid', restricted: false },
          { name: 'email', type: 'varchar', restricted: true },
        ],
        rows: [['1', '***']],
        row_count: 1,
        truncated: false,
        duration_ms: 4,
      },
    });
    renderPreview();
    // The governance note is always shown.
    expect(screen.getByText(/row-level security and column masking are applied/i)).toBeInTheDocument();
    // Masked column carries a lock with the masking tooltip as its accessible name.
    expect(screen.getAllByLabelText(/column masked by access policy/i).length).toBeGreaterThan(0);
    expect(screen.getByText('***')).toBeInTheDocument();
    expect(screen.queryByText(/a@/)).not.toBeInTheDocument();
  });

  it('renders the truncation footer when the sample is capped', () => {
    useTableSampleMock.mockReturnValue({
      data: {
        columns: [{ name: 'id', type: 'uuid', restricted: false }],
        rows: [['1']],
        row_count: 1,
        truncated: true,
        duration_ms: 4,
      },
    });
    renderPreview();
    expect(screen.getByText(/capped by the proxy row limit/i)).toBeInTheDocument();
  });

  it('renders the byte-limit footer when truncated_reason is BYTE_LIMIT', () => {
    useTableSampleMock.mockReturnValue({
      data: {
        columns: [{ name: 'id', type: 'uuid', restricted: false }],
        rows: [['1']],
        row_count: 1,
        truncated: true,
        truncated_reason: 'BYTE_LIMIT',
        duration_ms: 4,
      },
    });
    renderPreview();
    expect(screen.getByText(/capped by the result size limit/i)).toBeInTheDocument();
  });
});
