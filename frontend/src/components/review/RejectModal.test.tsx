import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { RejectModal } from './RejectModal';

function noop(): void {}

describe('RejectModal (AF-269)', () => {
  it('renders the modal with placeholder when open', () => {
    render(
      <RejectModal open loading={false} onCancel={noop} onConfirm={noop} />,
    );
    // Modal title text — there's also an aria-labelled textarea with the same
    // title; assert at least one match instead of pinning to one.
    expect(screen.getAllByText('Reject query').length).toBeGreaterThan(0);
    expect(screen.getByPlaceholderText(/Explain why/)).toBeInTheDocument();
  });

  it('keeps the confirm button disabled until the textarea is non-empty', () => {
    const onConfirm = vi.fn();
    render(
      <RejectModal open loading={false} onCancel={noop} onConfirm={onConfirm} />,
    );
    const confirm = screen.getByRole('button', { name: 'Reject' });
    expect(confirm).toBeDisabled();

    const textarea = screen.getByPlaceholderText(/Explain why/);
    act(() => {
      fireEvent.change(textarea, { target: { value: '   ' } });
    });
    // Whitespace-only must not enable confirm — guards against a sloppy paste.
    expect(confirm).toBeDisabled();

    act(() => {
      fireEvent.change(textarea, { target: { value: 'too risky' } });
    });
    expect(confirm).not.toBeDisabled();
  });

  it('fires onConfirm with the trimmed comment', () => {
    const onConfirm = vi.fn();
    render(
      <RejectModal open loading={false} onCancel={noop} onConfirm={onConfirm} />,
    );
    const textarea = screen.getByPlaceholderText(/Explain why/);
    act(() => {
      fireEvent.change(textarea, { target: { value: '  narrow the WHERE  ' } });
    });
    const confirm = screen.getByRole('button', { name: 'Reject' });
    act(() => {
      fireEvent.click(confirm);
    });
    expect(onConfirm).toHaveBeenCalledWith('narrow the WHERE');
  });

  it('fires onCancel when the cancel button is clicked', () => {
    const onCancel = vi.fn();
    render(
      <RejectModal open loading={false} onCancel={onCancel} onConfirm={noop} />,
    );
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    act(() => {
      fireEvent.click(cancel);
    });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('resets the textarea between opens', () => {
    const { rerender } = render(
      <RejectModal open loading={false} onCancel={noop} onConfirm={noop} />,
    );
    const textarea = screen.getByPlaceholderText(/Explain why/) as HTMLTextAreaElement;
    act(() => {
      fireEvent.change(textarea, { target: { value: 'first draft' } });
    });
    expect(textarea.value).toBe('first draft');

    rerender(<RejectModal open={false} loading={false} onCancel={noop} onConfirm={noop} />);
    rerender(<RejectModal open loading={false} onCancel={noop} onConfirm={noop} />);

    const reopened = screen.getByPlaceholderText(/Explain why/) as HTMLTextAreaElement;
    expect(reopened.value).toBe('');
  });
});
