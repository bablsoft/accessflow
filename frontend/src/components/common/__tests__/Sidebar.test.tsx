import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { Sidebar } from '../Sidebar';
import type { AuthUser } from '@/api/auth';

function renderSidebar(user: AuthUser, collapsed = false) {
  return render(
    <MemoryRouter>
      <Sidebar
        user={user}
        pendingCount={0}
        collapsed={collapsed}
        onToggle={() => undefined}
        mobileOpen={false}
        onMobileClose={() => undefined}
      />
    </MemoryRouter>,
  );
}

const adminUser: AuthUser = {
  id: 'u1',
  email: 'admin@example.com',
  display_name: 'Ada Admin',
  role: 'ADMIN',
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
};

const readonlyUser: AuthUser = {
  id: 'u2',
  email: 'readonly@example.com',
  display_name: 'Read Only',
  role: 'READONLY',
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
};

describe('Sidebar', () => {
  it('shows all four group headings for an admin', () => {
    renderSidebar(adminUser);
    expect(screen.getByText('Workflow')).toBeInTheDocument();
    expect(screen.getByText('Data sources')).toBeInTheDocument();
    expect(screen.getByText('Security & Access')).toBeInTheDocument();
    expect(screen.getByText('System')).toBeInTheDocument();
  });

  it('renders admin-only items for an admin user', () => {
    renderSidebar(adminUser);
    expect(screen.getByText('Users')).toBeInTheDocument();
    expect(screen.getByText('Audit log')).toBeInTheDocument();
    expect(screen.getByText('Datasources')).toBeInTheDocument();
    expect(screen.getByText('AI configurations')).toBeInTheDocument();
  });

  it('hides admin groups for a READONLY user', () => {
    renderSidebar(readonlyUser);
    expect(screen.queryByText('Data sources')).not.toBeInTheDocument();
    expect(screen.queryByText('Security & Access')).not.toBeInTheDocument();
    expect(screen.queryByText('System')).not.toBeInTheDocument();
    expect(screen.queryByText('Users')).not.toBeInTheDocument();
    expect(screen.queryByText('Audit log')).not.toBeInTheDocument();
  });

  it('shows the Query history item to a READONLY user (only-visible workflow item)', () => {
    renderSidebar(readonlyUser);
    expect(screen.getByText('Query history')).toBeInTheDocument();
    expect(screen.queryByText('Query editor')).not.toBeInTheDocument();
    expect(screen.queryByText('Review queue')).not.toBeInTheDocument();
  });

  it('hides group headings when collapsed (renders thin divider lines between groups)', () => {
    const { container } = renderSidebar(adminUser, true);
    expect(screen.queryByText('Workflow')).not.toBeInTheDocument();
    expect(screen.queryByText('Security & Access')).not.toBeInTheDocument();
    // Between four groups there are three divider lines.
    expect(container.querySelectorAll('.af-sidebar-divider-line').length).toBe(3);
  });

  it('renders the translated role label in the footer instead of the raw enum value', () => {
    const { container } = renderSidebar(adminUser);
    const footer = container.querySelector('.af-sidebar-footer') as HTMLElement;
    const roleEl = within(footer).getByText('Admin');
    expect(roleEl).toBeInTheDocument();
    expect(roleEl.className).toContain('mono');
    expect(within(footer).queryByText('ADMIN')).not.toBeInTheDocument();
  });
});
