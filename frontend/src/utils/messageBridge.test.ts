import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MessageInstance } from 'antd/es/message/interface';
import { getMessageApi, setMessageApi } from './messageBridge';

const stubMessage = (): MessageInstance =>
  ({
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
    loading: vi.fn(),
    open: vi.fn(),
    destroy: vi.fn(),
  }) as unknown as MessageInstance;

afterEach(() => {
  setMessageApi(null);
});

describe('messageBridge', () => {
  it('returns null when nothing has been bound yet', () => {
    expect(getMessageApi()).toBeNull();
  });

  it('returns the most recently bound message instance', () => {
    const api = stubMessage();
    setMessageApi(api);
    expect(getMessageApi()).toBe(api);
  });

  it('clears the binding when set to null', () => {
    setMessageApi(stubMessage());
    setMessageApi(null);
    expect(getMessageApi()).toBeNull();
  });
});
