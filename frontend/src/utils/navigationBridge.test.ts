import { afterEach, describe, expect, it, vi } from 'vitest';
import type { NavigateFunction } from 'react-router-dom';
import { getNavigate, setNavigate } from './navigationBridge';

const stubNavigate = (): NavigateFunction => vi.fn() as unknown as NavigateFunction;

afterEach(() => {
  setNavigate(null);
});

describe('navigationBridge', () => {
  it('returns null when nothing has been bound yet', () => {
    expect(getNavigate()).toBeNull();
  });

  it('returns the most recently bound navigate function', () => {
    const nav = stubNavigate();
    setNavigate(nav);
    expect(getNavigate()).toBe(nav);
  });

  it('clears the binding when set to null', () => {
    setNavigate(stubNavigate());
    setNavigate(null);
    expect(getNavigate()).toBeNull();
  });
});
