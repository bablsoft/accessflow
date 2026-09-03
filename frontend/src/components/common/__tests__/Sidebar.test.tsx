import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { Sidebar } from '../Sidebar';
import type { AuthUser } from '@/api/auth';
import { SYSTEM_ROLE_PERMISSIONS } from '@/mocks/systemRolePermissions';
import { usePreferencesStore } from '@/store/preferencesStore';

function renderSidebar(user: AuthUser, collapsed = false, route = '/dashboard') {
  return render(
    <MemoryRouter initialEntries={[route]}>
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

/** The `<div>` holding one sub-section's links, addressed the way `aria-controls` does. */
function subgroupItems(container: HTMLElement, id: string): HTMLElement | null {
  return container.querySelector(`#af-nav-sub-${id}`);
}

/**
 * One top-level group by its heading. Sub-section labels repeat across groups ("Database" sits
 * under both Workflow and Connections), so every sub-section assertion is scoped through this.
 */
function group(label: string): HTMLElement {
  return screen.getByRole('group', { name: label });
}

/** The subset of `screen` / `within(…)` the `link` helper needs — both satisfy it structurally. */
interface RoleQueries {
  getByRole: (role: string, options: { name: RegExp }) => HTMLElement;
}

/**
 * AntD prefixes every link's accessible name with its icon's `aria-label` ("home Dashboard"),
 * so nav links are matched on a substring rather than exactly.
 */
function link(scope: RoleQueries, label: string): HTMLElement {
  return scope.getByRole('link', { name: new RegExp(label) });
}

const adminUser: AuthUser = {
  id: 'u1',
  email: 'admin@example.com',
  display_name: 'Ada Admin',
  role: 'ADMIN',
  role_id: null,
  permissions: SYSTEM_ROLE_PERMISSIONS.ADMIN,
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
  role_id: null,
  permissions: SYSTEM_ROLE_PERMISSIONS.READONLY,
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
};

/**
 * Every sub-section id the nav declares. Sub-sections start collapsed, so a test whose subject is
 * the items inside one seeds the whole set expanded rather than repeating a header click.
 */
const ALL_SUBGROUPS = [
  'workflow-database',
  'workflow-api',
  'workflow-deployments',
  'workflow-request-groups',
  'workflow-access-lifecycle',
  'connections-database',
  'connections-api',
  'connections-deployments',
  'security-identity',
  'security-access-control',
  'security-data-governance',
  'security-audit-compliance',
  'system-ai',
];

function expandAll() {
  usePreferencesStore.setState({ navExpandedSubgroups: [...ALL_SUBGROUPS] });
}

beforeEach(() => {
  usePreferencesStore.setState({ navExpandedSubgroups: [] });
});

describe('Sidebar', () => {
  it('shows every group heading for an admin', () => {
    renderSidebar(adminUser);
    expect(screen.getByText('Workflow')).toBeInTheDocument();
    expect(screen.getByText('Connections')).toBeInTheDocument();
    expect(screen.getByText('Security & Access')).toBeInTheDocument();
    expect(screen.getByText('System')).toBeInTheDocument();
  });

  it('renders admin-only items for an admin user', () => {
    expandAll();
    renderSidebar(adminUser);
    expect(screen.getByText('Users')).toBeInTheDocument();
    expect(screen.getByText('Audit log')).toBeInTheDocument();
    expect(screen.getByText('Datasources')).toBeInTheDocument();
    expect(screen.getByText('AI configurations')).toBeInTheDocument();
  });

  it('hides admin groups for a READONLY user', () => {
    renderSidebar(readonlyUser);
    expect(screen.queryByRole('group', { name: 'Connections' })).not.toBeInTheDocument();
    expect(screen.queryByText('Security & Access')).not.toBeInTheDocument();
    expect(screen.queryByText('System')).not.toBeInTheDocument();
    expect(screen.queryByText('Users')).not.toBeInTheDocument();
    expect(screen.queryByText('Audit log')).not.toBeInTheDocument();
  });

  it('shows the Query history item to a READONLY user (only-visible workflow item)', () => {
    expandAll();
    renderSidebar(readonlyUser);
    expect(screen.getByText('Query history')).toBeInTheDocument();
    expect(screen.queryByText('Query editor')).not.toBeInTheDocument();
    expect(screen.queryByText('Review queue')).not.toBeInTheDocument();
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

describe('Sidebar — top generic group (AF-837)', () => {
  it('puts Dashboard and Review queue in an unlabelled group above Workflow', () => {
    const { container } = renderSidebar(adminUser);
    const first = container.querySelectorAll('.af-sidebar-group')[0] as HTMLElement;
    expect(link(within(first), 'Dashboard')).toBeInTheDocument();
    expect(link(within(first), 'Review queue')).toBeInTheDocument();
    // No divider heading, and no sub-sections — the generic entries stay ungrouped.
    expect(first.querySelector('.af-sidebar-divider')).toBeNull();
    expect(first.querySelector('.af-sidebar-subgroup')).toBeNull();
    expect(first.getAttribute('role')).toBeNull();
  });
});

describe('Sidebar — sub-sections (AF-837)', () => {
  it('renders sub-section headers as collapsed disclosure buttons by default', () => {
    // A first login shows every sub-section closed — the user opens the ones they need.
    const { container } = renderSidebar(adminUser);
    const header = within(group('Security & Access'))
      .getByRole('button', { name: 'Expand Identity section' });
    expect(header).toHaveAttribute('aria-expanded', 'false');
    expect(header).toHaveAttribute('aria-controls', 'af-nav-sub-security-identity');
    expect(subgroupItems(container, 'security-identity')!).toBeEmptyDOMElement();
    expect(screen.queryByRole('link', { name: /Users/ })).not.toBeInTheDocument();
  });

  it('opens no sub-section on a route that belongs to none', () => {
    const { container } = renderSidebar(adminUser, false, '/dashboard');
    expect(container.querySelectorAll('button[aria-expanded="true"]').length).toBe(0);
  });

  it('groups the workflow entries by domain', () => {
    expandAll();
    const { container } = renderSidebar(adminUser);
    const deployments = within(subgroupItems(container, 'workflow-deployments')!);
    expect(link(deployments, 'Deployments')).toBeInTheDocument();
    expect(link(deployments, 'Version Matrix')).toBeInTheDocument();
    // Since #772 deployment reviews live in the unified Review queue, not under Deployments.
    expect(deployments.queryByRole('link', { name: /Review/ })).not.toBeInTheDocument();

    const database = within(subgroupItems(container, 'workflow-database')!);
    expect(link(database, 'Query editor')).toBeInTheDocument();
    expect(link(database, 'Query history')).toBeInTheDocument();
  });

  it("renders a group's own items above its sub-sections", () => {
    renderSidebar(adminUser);
    const children = Array.from(group('System').children);
    const languages = children.findIndex((c) => c.textContent === 'Languages');
    const aiSection = children.findIndex((c) => c.classList.contains('af-sidebar-subgroup'));
    expect(languages).toBeGreaterThan(-1);
    expect(aiSection).toBeGreaterThan(languages);
  });

  it('moves Slack out of Security into the System group', () => {
    expandAll();
    renderSidebar(adminUser);
    expect(link(within(group('System')), 'Slack')).toBeInTheDocument();
    expect(within(group('Security & Access')).queryByRole('link', { name: /Slack/ }))
      .not.toBeInTheDocument();
  });

  it('expands a sub-section on click and persists the id in preferences', () => {
    const { container } = renderSidebar(adminUser);
    const security = within(group('Security & Access'));
    fireEvent.click(security.getByRole('button', { name: 'Expand Identity section' }));

    expect(usePreferencesStore.getState().navExpandedSubgroups).toContain('security-identity');
    expect(security.getByRole('button', { name: 'Collapse Identity section' }))
      .toHaveAttribute('aria-expanded', 'true');
    expect(link(within(subgroupItems(container, 'security-identity')!), 'Users'))
      .toBeInTheDocument();
  });

  it('re-collapses an expanded sub-section on a second click', () => {
    const { container } = renderSidebar(adminUser);
    const security = within(group('Security & Access'));
    fireEvent.click(security.getByRole('button', { name: 'Expand Identity section' }));
    fireEvent.click(security.getByRole('button', { name: 'Collapse Identity section' }));

    expect(usePreferencesStore.getState().navExpandedSubgroups).not.toContain('security-identity');
    expect(subgroupItems(container, 'security-identity')!).toBeEmptyDOMElement();
  });

  it('renders a collapsed sub-section open when it holds the active route', () => {
    // The deep-link case: nothing is expanded, yet the section around the current page opens.
    const { container } = renderSidebar(adminUser, false, '/admin/users');

    expect(
      within(group('Security & Access')).getByRole('button', {
        name: 'Identity section — kept open because it contains the current page',
      }),
    ).toHaveAttribute('aria-expanded', 'true');
    const users = link(within(subgroupItems(container, 'security-identity')!), 'Users');
    expect(users.className).toContain('active');
  });

  it('disables the header of the sub-section holding the active route', () => {
    // Toggling it could not change what is on screen, so advertising it as a live collapse
    // control would be a dead button announcing the wrong action.
    renderSidebar(adminUser, false, '/admin/users');
    const header = within(group('Security & Access')).getByRole('button', {
      name: 'Identity section — kept open because it contains the current page',
    });
    expect(header).toBeDisabled();

    fireEvent.click(header);
    expect(usePreferencesStore.getState().navExpandedSubgroups).toEqual([]);
    expect(header).toHaveAttribute('aria-expanded', 'true');
  });

  it('leaves sibling sub-section headers interactive while one is locked open', () => {
    renderSidebar(adminUser, false, '/admin/users');
    const accessControl = within(group('Security & Access'))
      .getByRole('button', { name: 'Expand Access control section' });
    expect(accessControl).toBeEnabled();
    fireEvent.click(accessControl);
    expect(usePreferencesStore.getState().navExpandedSubgroups)
      .toEqual(['security-access-control']);
  });

  it('drops a sub-section whose items the user cannot see, and its group with it', () => {
    const { container } = renderSidebar(readonlyUser);
    // READONLY manages nothing, so every Connections sub-section — and the group — disappears.
    expect(screen.queryByRole('group', { name: 'Connections' })).not.toBeInTheDocument();
    expect(subgroupItems(container, 'connections-database')).toBeNull();
    // Same for the whole Security group and its Identity sub-section.
    expect(screen.queryByRole('group', { name: 'Security & Access' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Expand Identity section' }))
      .not.toBeInTheDocument();
  });

  it('keeps a single-item sub-section header so positions stay predictable', () => {
    expandAll();
    const { container } = renderSidebar(adminUser);
    expect(
      within(group('Connections')).getByRole('button', { name: 'Collapse Deployments section' }),
    ).toBeInTheDocument();
    expect(subgroupItems(container, 'connections-deployments')!.children.length).toBe(1);
  });

  it('keeps the header of a sub-section a role has whittled down to one item', () => {
    expandAll();
    const { container } = renderSidebar(readonlyUser);
    // READONLY holds QUERY_SUBMIT_SELECT only, so API keeps API Requests and nothing else.
    expect(
      within(group('Workflow')).getByRole('button', { name: 'Collapse API section' }),
    ).toBeInTheDocument();
    const api = subgroupItems(container, 'workflow-api')!;
    expect(api.children.length).toBe(1);
    expect(link(within(api), 'API Requests')).toBeInTheDocument();
  });
});

describe('Sidebar — active highlighting', () => {
  it('highlights only the deepest matching entry when nav paths nest', () => {
    // `/request-groups/reviews` sits under `/request-groups`; a prefix test lit up both.
    expandAll();
    const { container } = renderSidebar(adminUser, false, '/request-groups/reviews');
    const items = within(subgroupItems(container, 'workflow-request-groups')!);
    expect(link(items, 'Group Reviews').className).toContain('active');
    expect(link(items, 'Request Groups').className).not.toContain('active');
  });

  it('highlights the parent entry on its own route', () => {
    expandAll();
    const { container } = renderSidebar(adminUser, false, '/request-groups');
    const items = within(subgroupItems(container, 'workflow-request-groups')!);
    expect(link(items, 'Request Groups').className).toContain('active');
    expect(link(items, 'Group Reviews').className).not.toContain('active');
  });

  it('keeps the parent highlighted on a detail route it alone owns', () => {
    expandAll();
    const { container } = renderSidebar(adminUser, false, '/request-groups/8f3c');
    const items = within(subgroupItems(container, 'workflow-request-groups')!);
    expect(link(items, 'Request Groups').className).toContain('active');
    expect(link(items, 'Group Reviews').className).not.toContain('active');
  });

  it('does not highlight the review queue on the attestation reviews route', () => {
    // Same nesting between two different groups: `/reviews` vs `/reviews/attestations`.
    expandAll();
    const { container } = renderSidebar(adminUser, false, '/reviews/attestations');
    const first = container.querySelectorAll('.af-sidebar-group')[0] as HTMLElement;
    expect(link(within(first), 'Review queue').className).not.toContain('active');
    const lifecycle = within(subgroupItems(container, 'workflow-access-lifecycle')!);
    expect(link(lifecycle, 'Attestation reviews').className).toContain('active');
  });

  it('locks open only the sub-section owning the deepest match', () => {
    const security = 'Security & Access';
    renderSidebar(adminUser, false, '/reviews/attestations');
    expect(
      within(group('Workflow')).getByRole('button', {
        name: 'Access & lifecycle section — kept open because it contains the current page',
      }),
    ).toBeDisabled();
    expect(
      within(group(security)).getByRole('button', { name: 'Expand Identity section' }),
    ).toBeEnabled();
  });
});

describe('Sidebar — icon rail (AF-837)', () => {
  it('hides group and sub-section headers and flattens every item', () => {
    const { container } = renderSidebar(adminUser, true);
    expect(screen.queryByText('Workflow')).not.toBeInTheDocument();
    expect(screen.queryByText('Security & Access')).not.toBeInTheDocument();
    expect(container.querySelector('.af-sidebar-subgroup')).toBeNull();
    expect(container.querySelectorAll('button[aria-expanded]').length).toBe(0);
    // Every link is still reachable, just unlabelled.
    expect(container.querySelector('a[href="/admin/users"]')).toBeInTheDocument();
    expect(container.querySelector('a[href="/editor"]')).toBeInTheDocument();
  });

  it('separates each block with a divider line (one fewer than the block count)', () => {
    const { container } = renderSidebar(adminUser, true);
    // general + 5 workflow + 3 connections + 4 security + system items + system AI = 15.
    expect(container.querySelectorAll('.af-sidebar-group').length).toBe(15);
    expect(container.querySelectorAll('.af-sidebar-divider-line').length).toBe(14);
  });

  it('ignores the collapsed sub-section preference — the rail never hides items', () => {
    // Nothing is expanded, yet every link is still on the rail.
    const { container } = renderSidebar(adminUser, true);
    expect(container.querySelector('a[href="/admin/users"]')).toBeInTheDocument();
    expect(container.querySelector('a[href="/admin/ai-configs"]')).toBeInTheDocument();
  });
});

describe('Sidebar — deployment governance (#696)', () => {
  it('shows the three deployment entries for an admin', () => {
    expandAll();
    renderSidebar(adminUser);
    expect(link(screen, 'Deployments')).toBeInTheDocument();
    expect(link(screen, 'Version Matrix')).toBeInTheDocument();
    expect(link(screen, 'Deployment Pipelines')).toBeInTheDocument();
  });

  it('shows the version matrix to a REVIEWER but not the pipelines admin page', () => {
    expandAll();
    renderSidebar({
      ...readonlyUser,
      role: 'REVIEWER',
      permissions: SYSTEM_ROLE_PERMISSIONS.REVIEWER,
    });
    // The version matrix is any-of MANAGE / REVIEW / QUERY_ADMIN, so DEPLOYMENT_REVIEW is enough.
    expect(link(screen, 'Version Matrix')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Deployment Pipelines/ })).not.toBeInTheDocument();
  });

  it('hides the version matrix and pipelines from a READONLY user', () => {
    expandAll();
    renderSidebar(readonlyUser);
    expect(screen.queryByRole('link', { name: /Version Matrix/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Deployment Pipelines/ })).not.toBeInTheDocument();
    // Deployments list rides on QUERY_SUBMIT_SELECT, which READONLY has.
    expect(link(screen, 'Deployments')).toBeInTheDocument();
  });
});

describe('Sidebar — unified review queue (#772)', () => {
  it('renders exactly one review entry, in the top group, for a user holding every review permission', () => {
    const { container } = renderSidebar({
      ...readonlyUser,
      role: 'REVIEWER',
      permissions: SYSTEM_ROLE_PERMISSIONS.REVIEWER,
    });
    expect(screen.getAllByRole('link', { name: /Review queue/ })).toHaveLength(1);
    // The pre-#772 per-queue entries are gone.
    expect(screen.queryByRole('link', { name: /API Reviews|Deployment Reviews/ }))
      .not.toBeInTheDocument();
    const first = container.querySelectorAll('.af-sidebar-group')[0] as HTMLElement;
    expect(link(within(first), 'Review queue')).toHaveAttribute('href', '/reviews');
  });

  it.each([
    ['QUERY_REVIEW'],
    ['API_REQUEST_REVIEW'],
    ['DEPLOYMENT_REVIEW'],
  ] as const)('shows the review queue to a user holding only %s', (permission) => {
    renderSidebar({ ...readonlyUser, permissions: ['QUERY_SUBMIT_SELECT', permission] });
    expect(link(screen, 'Review queue')).toBeInTheDocument();
  });

  it('hides the review queue from a user holding no review permission', () => {
    renderSidebar(readonlyUser);
    expect(screen.queryByRole('link', { name: /Review queue/ })).not.toBeInTheDocument();
  });

  it('shows the pending badge on the review entry', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Sidebar
          user={adminUser}
          pendingCount={7}
          collapsed={false}
          onToggle={() => undefined}
          mobileOpen={false}
          onMobileClose={() => undefined}
        />
      </MemoryRouter>,
    );
    expect(link(screen, 'Review queue').querySelector('.af-sidebar-badge')).toHaveTextContent('7');
  });
});
