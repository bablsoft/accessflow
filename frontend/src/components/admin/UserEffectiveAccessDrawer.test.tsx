import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { User } from '@/types/api';

vi.mock('@/components/access/EffectivePermissionExplorer', () => ({
  EffectivePermissionExplorer: ({ userId }: { userId?: string }) => (
    <div data-testid="explorer">{userId}</div>
  ),
}));

const { UserEffectiveAccessDrawer } = await import('./UserEffectiveAccessDrawer');

const user = { id: 'u-1', email: 'ana@example.com', display_name: 'Ana Analyst' } as User;

describe('UserEffectiveAccessDrawer', () => {
  it('opens the explorer fixed to the chosen user', async () => {
    render(<UserEffectiveAccessDrawer user={user} onClose={() => undefined} />);

    expect(await screen.findByText(/Effective access — Ana Analyst/)).toBeInTheDocument();
    expect(screen.getByTestId('explorer')).toHaveTextContent('u-1');
  });

  it('renders nothing while closed', () => {
    render(<UserEffectiveAccessDrawer user={null} onClose={() => undefined} />);

    expect(screen.queryByTestId('explorer')).not.toBeInTheDocument();
  });
});
