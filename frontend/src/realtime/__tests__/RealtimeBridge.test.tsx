import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';

const connect = vi.fn();
const disconnect = vi.fn();
const bindQueryClient = vi.fn();

vi.mock('@/realtime/websocketManager', () => ({
  websocketManager: { connect, disconnect, bindQueryClient },
}));

const setAccessToken = (token: string | null) => {
  useAuthStore.setState({ accessToken: token, user: token ? { id: 'u' } as never : null });
};

const { useAuthStore } = await import('@/store/authStore');
const { RealtimeBridge } = await import('../RealtimeBridge');

function wrap(node: React.ReactNode) {
  const client = new QueryClient();
  return <QueryClientProvider client={client}>{node}</QueryClientProvider>;
}

describe('RealtimeBridge', () => {
  beforeEach(() => {
    connect.mockClear();
    disconnect.mockClear();
    bindQueryClient.mockClear();
    setAccessToken(null);
  });

  it('renders nothing', () => {
    const { container } = render(wrap(<RealtimeBridge />));
    expect(container.firstChild).toBeNull();
  });

  it('binds the QueryClient on mount', () => {
    render(wrap(<RealtimeBridge />));
    expect(bindQueryClient).toHaveBeenCalled();
  });

  it('calls connect when an access token is present', () => {
    setAccessToken('jwt-1');
    render(wrap(<RealtimeBridge />));
    expect(connect).toHaveBeenCalledWith('jwt-1');
  });

  it('calls disconnect when there is no access token', () => {
    setAccessToken(null);
    render(wrap(<RealtimeBridge />));
    expect(disconnect).toHaveBeenCalled();
  });

  it('disconnects on unmount', () => {
    setAccessToken('jwt-2');
    const { unmount } = render(wrap(<RealtimeBridge />));
    disconnect.mockClear();
    unmount();
    expect(disconnect).toHaveBeenCalled();
  });
});
