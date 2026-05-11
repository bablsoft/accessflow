import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { App } from 'antd';
import '@/i18n';
import { TraceIdFooter } from '../TraceIdFooter';

function wrap(node: React.ReactNode) {
  return <App>{node}</App>;
}

describe('TraceIdFooter', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders a truncated trace id and the full id in title attribute', () => {
    render(wrap(<TraceIdFooter traceId="abcdef0123456789abcd" />));
    const code = screen.getByText(/abcdef01.*abcd/);
    expect(code).toBeInTheDocument();
    expect(code.getAttribute('title')).toBe('abcdef0123456789abcd');
  });

  it('does not truncate short trace ids', () => {
    render(wrap(<TraceIdFooter traceId="short" />));
    expect(screen.getByText('short')).toBeInTheDocument();
  });

  it('copies the trace id to the clipboard on click', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });

    render(wrap(<TraceIdFooter traceId="abc-123" />));
    const button = screen.getByRole('button', { name: /copy trace id/i });
    fireEvent.click(button);

    expect(writeText).toHaveBeenCalledWith('abc-123');
  });

  it('does not throw when the clipboard API rejects', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('clipboard unavailable'));
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });

    render(wrap(<TraceIdFooter traceId="abc-123" />));
    const button = screen.getByRole('button', { name: /copy trace id/i });
    fireEvent.click(button);

    // No assertion needed beyond not throwing — the catch branch is exercised.
    await Promise.resolve();
    expect(writeText).toHaveBeenCalled();
  });
});
