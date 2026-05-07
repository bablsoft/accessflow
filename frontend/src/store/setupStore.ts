import { create } from 'zustand';

interface SetupState {
  setupRequired: boolean | null;
  setSetupRequired: (value: boolean) => void;
}

export const useSetupStore = create<SetupState>((set) => ({
  setupRequired: null,
  setSetupRequired: (value) => set({ setupRequired: value }),
}));
