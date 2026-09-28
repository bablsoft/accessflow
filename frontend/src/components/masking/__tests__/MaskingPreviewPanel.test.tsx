import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MaskingPreviewPanel } from '../MaskingPreviewPanel';
import '@/i18n';

function renderPanel(props: Partial<Parameters<typeof MaskingPreviewPanel>[0]> = {}) {
  const onSampleChange = vi.fn();
  render(
    <MaskingPreviewPanel
      strategy="KEEP_FIRST"
      params={{ visible_prefix: '4' }}
      sample="0912345678"
      onSampleChange={onSampleChange}
      label="Preview"
      sampleAriaLabel="Sample value"
      {...props}
    />,
  );
  return onSampleChange;
}

describe('MaskingPreviewPanel', () => {
  it('renders the masked sample', () => {
    renderPanel();
    expect(screen.getByTestId('masking-preview-output')).toHaveTextContent('0912******');
    expect(screen.queryByText(/browser's regex engine/)).toBeNull();
  });

  it('reports sample edits', () => {
    const onSampleChange = renderPanel();
    fireEvent.change(screen.getByLabelText('Sample value'), { target: { value: 'x' } });
    expect(onSampleChange).toHaveBeenCalledWith('x');
  });

  it('shows NULL for nullify and a dash for an empty sample', () => {
    renderPanel({ strategy: 'NULLIFY', params: undefined });
    expect(screen.getByTestId('masking-preview-output')).toHaveTextContent('NULL');
  });

  it('shows a dash for an empty sample and defaults to the full mask', () => {
    renderPanel({ strategy: undefined, sample: '' });
    expect(screen.getByTestId('masking-preview-output')).toHaveTextContent('—');
  });

  it('notes that regex previews are approximate', () => {
    renderPanel({ strategy: 'REGEX_REPLACE', params: { pattern: '\\d', replacement: '#' } });
    expect(screen.getByText(/browser's regex engine/)).toBeInTheDocument();
    expect(screen.getByTestId('masking-preview-output')).toHaveTextContent('##########');
  });

  it('skips the regex preview for long samples instead of risking a frozen tab', () => {
    renderPanel({
      strategy: 'REGEX_REPLACE',
      params: { pattern: '^(a+)+$', replacement: 'x' },
      sample: `${'a'.repeat(30)}!`,
    });
    expect(screen.getByText(/20 characters or fewer/)).toBeInTheDocument();
    expect(screen.getByTestId('masking-preview-output')).toHaveTextContent('—');
  });
});
