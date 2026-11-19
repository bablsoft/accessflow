import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { APP_VERSION } from '@/config/version';

vi.mock('@/components/common/LanguageSwitcher', () => ({
  LanguageSwitcher: ({ mode }: { mode: string }) => <div data-testid="lang" data-mode={mode} />,
}));

const { AuthLayout } = await import('../AuthLayout');

describe('AuthLayout', () => {
  it('renders the brand, the real app version, the heading and the card', () => {
    const { container } = render(
      <AuthLayout title="Sign in" subtitle="Welcome back">
        <form aria-label="form" />
      </AuthLayout>,
    );
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByText('Welcome back')).toBeInTheDocument();
    expect(screen.getByText(`v${APP_VERSION}`)).toBeInTheDocument();
    expect(container.querySelector('.af-card')).toContainElement(screen.getByLabelText('form'));
    expect(container.querySelector('.af-auth-column')).not.toHaveClass('af-auth-column--wide');
    expect(screen.queryByTestId('lang')).toBeNull();
    expect(container.querySelector('.af-auth-footer')).toBeNull();
  });

  it('supports the wide column, the public language switcher and a footer', () => {
    const { container } = render(
      <AuthLayout title="Set up" wide languageSwitcher footer="Open source">
        body
      </AuthLayout>,
    );
    expect(container.querySelector('.af-auth-column')).toHaveClass('af-auth-column--wide');
    expect(screen.getByTestId('lang')).toHaveAttribute('data-mode', 'public');
    expect(screen.getByText('Open source')).toBeInTheDocument();
    expect(container.querySelector('.af-auth-subtitle')).toBeNull();
  });
});
