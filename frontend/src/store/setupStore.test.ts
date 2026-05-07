import { beforeEach, describe, expect, it } from 'vitest';
import { useSetupStore } from './setupStore';

describe('setupStore', () => {
  beforeEach(() => {
    useSetupStore.setState({ setupRequired: null });
  });

  it('starts with an unknown setup state', () => {
    expect(useSetupStore.getState().setupRequired).toBeNull();
  });

  it('setSetupRequired flips the flag', () => {
    useSetupStore.getState().setSetupRequired(true);
    expect(useSetupStore.getState().setupRequired).toBe(true);
    useSetupStore.getState().setSetupRequired(false);
    expect(useSetupStore.getState().setupRequired).toBe(false);
  });
});
