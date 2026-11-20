import { describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { useWizardStep } from '../useWizardStep';

const KEYS = ['one', 'two', 'three'] as const;
type Key = (typeof KEYS)[number];

function setup(initial: string, canShow: (key: Key) => boolean, fallback: Key = 'one') {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>
  );
  return renderHook(
    () => {
      const [step, go] = useWizardStep(KEYS, fallback, canShow);
      return { step, go, search: useLocation().search };
    },
    { wrapper },
  );
}

describe('useWizardStep', () => {
  it('starts on the first step with a clean URL', () => {
    const { result } = setup('/w', () => true);
    expect(result.current.step).toBe('one');
    expect(result.current.search).toBe('');
  });

  it('reads the step from the URL and keeps other parameters', () => {
    const { result } = setup('/w?step=two&x=1', () => true);
    expect(result.current.step).toBe('two');
    act(() => result.current.go('three'));
    expect(result.current.step).toBe('three');
    expect(result.current.search).toBe('?step=three&x=1');
  });

  it('drops the parameter when going to the first step', () => {
    const { result } = setup('/w?step=two', () => true);
    act(() => result.current.go('one'));
    expect(result.current.search).toBe('');
  });

  it('falls back and corrects the URL for a step it cannot show', () => {
    const { result } = setup('/w?step=three', (key) => key !== 'three');
    expect(result.current.step).toBe('one');
    expect(result.current.search).toBe('');
  });

  it('writes a fallback that is not the first step into the URL', () => {
    const { result } = setup('/w?step=bogus', () => true, 'two');
    expect(result.current.step).toBe('two');
    expect(result.current.search).toBe('?step=two');
  });
});
