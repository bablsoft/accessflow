import { describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { useUrlTab } from '../useUrlTab';

const KEYS = ['config', 'schema', 'activity'] as const;

function setup(initial: string) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>
  );
  return renderHook(() => ({ tab: useUrlTab(KEYS, 'config'), search: useLocation().search }), {
    wrapper,
  });
}

describe('useUrlTab', () => {
  it('reads the tab from the URL and falls back for a missing or unknown one', () => {
    expect(setup('/x?tab=schema').result.current.tab[0]).toBe('schema');
    expect(setup('/x').result.current.tab[0]).toBe('config');
    expect(setup('/x?tab=bogus').result.current.tab[0]).toBe('config');
  });

  it('writes the tab, keeps other parameters, and drops the parameter for the fallback', () => {
    const { result } = setup('/x?planId=7');
    act(() => result.current.tab[1]('activity'));
    expect(result.current.tab[0]).toBe('activity');
    expect(result.current.search).toBe('?planId=7&tab=activity');
    act(() => result.current.tab[1]('config'));
    expect(result.current.tab[0]).toBe('config');
    expect(result.current.search).toBe('?planId=7');
  });
});
