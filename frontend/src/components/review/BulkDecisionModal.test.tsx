import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { BulkDecisionModal } from './BulkDecisionModal';

function noop(): void {}

describe('BulkDecisionModal (AF-346)', () => {
  it('does not require a comment for APPROVED', () => {
    render(
      <BulkDecisionModal
        open
        decision="APPROVED"
        selectedCount={3}
        loading={false}
        onCancel={noop}
        onConfirm={noop}
      />,
    );
    // The modal title and primary button share the same label semantics;
    // pick the last matching button (the modal's primary action).
    const buttons = screen.getAllByRole('button', { name: /Approve selected/ });
    const confirm = buttons[buttons.length - 1]!;
    expect(confirm).not.toBeDisabled();
  });

  it('requires a non-empty trimmed comment for REJECTED', () => {
    render(
      <BulkDecisionModal
        open
        decision="REJECTED"
        selectedCount={2}
        loading={false}
        onCancel={noop}
        onConfirm={noop}
      />,
    );
    const buttons = screen.getAllByRole('button', { name: /Reject selected/ });
    const confirm = buttons[buttons.length - 1]!;
    expect(confirm).toBeDisabled();

    const textarea = screen.getByPlaceholderText(/Comment that will apply/);
    act(() => {
      fireEvent.change(textarea, { target: { value: '   ' } });
    });
    expect(confirm).toBeDisabled();

    act(() => {
      fireEvent.change(textarea, { target: { value: 'too risky' } });
    });
    expect(confirm).not.toBeDisabled();
  });

  it('requires a non-empty trimmed comment for REQUESTED_CHANGES', () => {
    render(
      <BulkDecisionModal
        open
        decision="REQUESTED_CHANGES"
        selectedCount={1}
        loading={false}
        onCancel={noop}
        onConfirm={noop}
      />,
    );
    const buttons = screen.getAllByRole('button', { name: /Request changes/ });
    const confirm = buttons[buttons.length - 1]!;
    expect(confirm).toBeDisabled();

    const textarea = screen.getByPlaceholderText(/Comment that will apply/);
    act(() => {
      fireEvent.change(textarea, { target: { value: 'add a WHERE clause' } });
    });
    expect(confirm).not.toBeDisabled();
  });

  it('fires onConfirm with the trimmed comment', () => {
    const onConfirm = vi.fn();
    render(
      <BulkDecisionModal
        open
        decision="APPROVED"
        selectedCount={2}
        loading={false}
        onCancel={noop}
        onConfirm={onConfirm}
      />,
    );
    const textarea = screen.getByPlaceholderText(/Comment that will apply/);
    act(() => {
      fireEvent.change(textarea, { target: { value: '  looks good  ' } });
    });
    const buttons = screen.getAllByRole('button', { name: /Approve selected/ });
    const confirm = buttons[buttons.length - 1]!;
    act(() => {
      fireEvent.click(confirm);
    });
    expect(onConfirm).toHaveBeenCalledWith('looks good');
  });

  it('fires onCancel when the cancel button is clicked', () => {
    const onCancel = vi.fn();
    render(
      <BulkDecisionModal
        open
        decision="APPROVED"
        selectedCount={1}
        loading={false}
        onCancel={onCancel}
        onConfirm={noop}
      />,
    );
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    act(() => {
      fireEvent.click(cancel);
    });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('resets the textarea between opens', () => {
    const { rerender } = render(
      <BulkDecisionModal
        open
        decision="REJECTED"
        selectedCount={1}
        loading={false}
        onCancel={noop}
        onConfirm={noop}
      />,
    );
    const textarea = screen.getByPlaceholderText(
      /Comment that will apply/,
    ) as HTMLTextAreaElement;
    act(() => {
      fireEvent.change(textarea, { target: { value: 'first draft' } });
    });
    expect(textarea.value).toBe('first draft');

    rerender(
      <BulkDecisionModal
        open={false}
        decision="REJECTED"
        selectedCount={1}
        loading={false}
        onCancel={noop}
        onConfirm={noop}
      />,
    );
    rerender(
      <BulkDecisionModal
        open
        decision="REJECTED"
        selectedCount={1}
        loading={false}
        onCancel={noop}
        onConfirm={noop}
      />,
    );

    const reopened = screen.getByPlaceholderText(
      /Comment that will apply/,
    ) as HTMLTextAreaElement;
    expect(reopened.value).toBe('');
  });
});
