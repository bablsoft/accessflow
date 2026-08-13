import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { WidgetError } from './WidgetError';

describe('WidgetError', () => {
  it('renders the fallback message and title for an unknown error', () => {
    render(<WidgetError error={new Error('boom')} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/couldn't load this/i)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('invokes onRetry from the retry button', () => {
    const onRetry = vi.fn();
    render(<WidgetError error={new Error('boom')} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('uses a custom message builder when provided', () => {
    render(<WidgetError error={new Error('x')} messageFor={() => 'custom detail'} />);
    expect(screen.getByText('custom detail')).toBeInTheDocument();
  });
});
