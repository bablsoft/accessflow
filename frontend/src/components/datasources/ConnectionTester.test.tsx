import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ConnectionTester } from './ConnectionTester';

describe('ConnectionTester', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the regular Test connection label when idle', () => {
    render(
      <ConnectionTester
        driverStatus="READY"
        pending={false}
        result={null}
        errorMessage={null}
        onRunTest={() => {}}
      />,
    );
    expect(screen.getByRole('button')).toHaveTextContent('Test connection');
  });

  it('flips to Resolving driver after 800ms when AVAILABLE driver is pending', () => {
    const { rerender } = render(
      <ConnectionTester
        driverStatus="AVAILABLE"
        pending={true}
        result={null}
        errorMessage={null}
        onRunTest={() => {}}
      />,
    );

    expect(screen.getByRole('button')).toHaveTextContent('Test connection');

    act(() => {
      vi.advanceTimersByTime(800);
    });

    expect(screen.getByRole('button')).toHaveTextContent('Resolving driver');

    // Once the response lands, the button label resets even though some text was shown.
    rerender(
      <ConnectionTester
        driverStatus="AVAILABLE"
        pending={false}
        result={{ ok: true, latency_ms: 42, message: 'ok' }}
        errorMessage={null}
        onRunTest={() => {}}
      />,
    );
    expect(screen.getByRole('button')).toHaveTextContent('Test connection');
    expect(screen.getByText(/Connected · 42 ms/)).toBeInTheDocument();
  });

  it('does not flip to Resolving when status is READY', () => {
    render(
      <ConnectionTester
        driverStatus="READY"
        pending={true}
        result={null}
        errorMessage={null}
        onRunTest={() => {}}
      />,
    );
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByRole('button')).toHaveTextContent('Test connection');
  });

  it('renders error description when errorMessage is set', () => {
    render(
      <ConnectionTester
        driverStatus="READY"
        pending={false}
        result={null}
        errorMessage="connection refused"
        onRunTest={() => {}}
      />,
    );
    expect(screen.getByText('Connection failed')).toBeInTheDocument();
    expect(screen.getByText('connection refused')).toBeInTheDocument();
  });

  it('invokes onRunTest when the button is clicked', () => {
    const onRunTest = vi.fn();
    render(
      <ConnectionTester
        driverStatus="READY"
        pending={false}
        result={null}
        errorMessage={null}
        onRunTest={onRunTest}
      />,
    );
    fireEvent.click(screen.getByRole('button'));
    expect(onRunTest).toHaveBeenCalled();
  });
});
