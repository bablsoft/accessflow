import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';

const getPublicLocalizationConfig = vi.fn();
const getMeLocalization = vi.fn();
const updateMeLocalization = vi.fn();

vi.mock('@/api/localization', () => ({
  getPublicLocalizationConfig: () => getPublicLocalizationConfig(),
  getMeLocalization: () => getMeLocalization(),
  updateMeLocalization: (code: string) => updateMeLocalization(code),
  localizationKeys: {
    all: ['localization'],
    admin: () => ['localization', 'admin'],
    me: () => ['localization', 'me'],
    public: () => ['localization', 'public'],
  },
}));

vi.mock('@/store/authStore', () => ({
  useAuthStore: (selector: (state: { isAuthenticated: () => boolean }) => unknown) =>
    selector({ isAuthenticated: () => false }),
}));

const { LanguageSwitcher } = await import('../LanguageSwitcher');

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

describe('LanguageSwitcher', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPublicLocalizationConfig.mockResolvedValue({
      available_languages: ['en', 'es'],
      default_language: 'en',
    });
    getMeLocalization.mockResolvedValue({
      available_languages: ['en', 'es', 'fr'],
      default_language: 'en',
      current_language: 'en',
    });
  });

  it('renders the switcher trigger with accessible label', () => {
    render(wrap(<LanguageSwitcher mode="public" />));
    expect(screen.getByRole('button', { name: /language/i })).toBeInTheDocument();
  });

  it('public mode hits the public endpoint and lists only its languages', async () => {
    render(wrap(<LanguageSwitcher mode="public" />));

    fireEvent.click(screen.getByRole('button', { name: /language/i }));

    await waitFor(() => {
      expect(getPublicLocalizationConfig).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(screen.getByRole('menuitem', { name: 'Español' })).toBeInTheDocument();
    });
    expect(screen.getByRole('menuitem', { name: 'English' })).toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: 'Français' })).toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Deutsch' })).toBeNull();
    expect(getMeLocalization).not.toHaveBeenCalled();
  });

  it('public mode does not call updateMeLocalization on selection', async () => {
    render(wrap(<LanguageSwitcher mode="public" />));
    fireEvent.click(screen.getByRole('button', { name: /language/i }));

    const esItem = await screen.findByRole('menuitem', { name: 'Español' });
    fireEvent.click(esItem);

    // Public mode must never call the authenticated mutation
    expect(updateMeLocalization).not.toHaveBeenCalled();
  });
});
