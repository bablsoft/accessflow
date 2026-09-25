import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { BytesInput } from '../BytesInput';

describe('BytesInput', () => {
  it('shows a byte value in the largest unit it fills', () => {
    render(<BytesInput value={2_000_000_000_000} aria-label="cap" />);
    expect(screen.getByLabelText('cap')).toHaveValue('2');
    expect(screen.getByText('TB')).toBeInTheDocument();
  });

  it('emits raw bytes for the typed amount', () => {
    const onChange = vi.fn();
    render(<BytesInput value={null} onChange={onChange} aria-label="cap" />);

    fireEvent.change(screen.getByLabelText('cap'), { target: { value: '3' } });

    expect(onChange).toHaveBeenLastCalledWith(3_000_000_000);
  });

  it('emits null when the amount is cleared', () => {
    const onChange = vi.fn();
    render(<BytesInput value={1_000_000_000} onChange={onChange} aria-label="cap" />);

    fireEvent.change(screen.getByLabelText('cap'), { target: { value: '' } });

    expect(onChange).toHaveBeenLastCalledWith(null);
  });

  it('rescales the value when the unit changes', () => {
    const onChange = vi.fn();
    render(<BytesInput value={2_000_000_000} onChange={onChange} aria-label="cap" />);

    fireEvent.mouseDown(screen.getByRole('combobox'));
    fireEvent.click(screen.getByTitle('TB'));

    expect(onChange).toHaveBeenLastCalledWith(2_000_000_000_000);
  });
});
