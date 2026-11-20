import { Fragment } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import {
  EditOutlined,
  BranchesOutlined,
  DiffOutlined,
  ThunderboltOutlined,
  UnorderedListOutlined,
  InboxOutlined,
  DatabaseOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  SafetyOutlined,
  TagsOutlined,
  ExperimentOutlined,
  BellOutlined,
  ApartmentOutlined,
  NodeIndexOutlined,
  FileSearchOutlined,
  IdcardOutlined,
  GlobalOutlined,
  ApiOutlined,
  LoginOutlined,
  SlackOutlined,
  LeftOutlined,
  RightOutlined,
  CloudSyncOutlined,
  CloudUploadOutlined,
  CloseOutlined,
  BarChartOutlined,
  DashboardOutlined,
  DownOutlined,
  HomeOutlined,
  KeyOutlined,
  UnlockOutlined,
  UserDeleteOutlined,
  CrownOutlined,
  AimOutlined,
  LineChartOutlined,
  QuestionCircleOutlined,
  AppstoreOutlined,
  BankOutlined,
  ScheduleOutlined,
  AuditOutlined,
  WarningOutlined,
  FileProtectOutlined,
  FieldTimeOutlined,
  CheckSquareOutlined,
  BlockOutlined,
  DeploymentUnitOutlined,
  FundOutlined,
  GatewayOutlined,
  RobotOutlined,
  RocketOutlined,
  ProfileOutlined,
  BuildOutlined,
  LinkOutlined,
  UsergroupAddOutlined,
  CarryOutOutlined,
  PartitionOutlined,
  HourglassOutlined,
  SolutionOutlined,
  ControlOutlined,
  PushpinOutlined,
  PushpinFilled,
} from '@ant-design/icons';
import { Tooltip } from 'antd';
import type { TFunction } from 'i18next';
import * as m from 'motion/react-m';
import { useTranslation } from 'react-i18next';
import type { AuthUser } from '@/api/auth';
import { REVIEW_HUB_PERMISSIONS } from '@/utils/reviewHubTabs';
import { userDisplay } from '@/utils/userDisplay';
import { roleLabel } from '@/utils/enumLabels';
import { usePreferencesStore } from '@/store/preferencesStore';
import { transitions } from '@/motion/transitions';
import { Avatar } from './Avatar';
import {
  activeNavEntry,
  flattenNav,
  visibleNavGroups,
  type NavGroup,
  type NavItem,
} from './navModel';
import { LogoMark } from './LogoMark';
import { VersionBadge } from './VersionBadge';
import './sidebar.css';

/**
 * The navigation tree. The `GROUPS` literal is read as data by the help-corpus generator
 * (`.github/scripts/build-help-corpus.mjs`), so keep it a plain literal of `t('nav.…')` labels.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function buildNavGroups(t: TFunction): NavGroup[] {
  const GROUPS: NavGroup[] = [
    {
      id: 'general',
      items: [
        { id: 'dashboard', to: '/dashboard', label: t('nav.dashboard'), icon: <HomeOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
        { id: 'reviews', to: '/reviews', label: t('nav.reviews'), icon: <InboxOutlined />, permissions: REVIEW_HUB_PERMISSIONS, badge: 'pending' },
      ],
    },
    {
      id: 'workflow',
      label: t('nav.group_workflow'),
      subgroups: [
        {
          id: 'workflow-database',
          label: t('nav.sub_database'),
          items: [
            { id: 'editor', to: '/editor', label: t('nav.editor'), icon: <EditOutlined />, permissions: ['QUERY_SUBMIT_DML'] },
            { id: 'queries', to: '/queries', label: t('nav.queries'), icon: <UnorderedListOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
          ],
        },
        {
          id: 'workflow-api',
          label: t('nav.sub_api'),
          domain: 'apis',
          items: [
            { id: 'api-editor', to: '/api-editor', label: t('nav.apiEditor'), icon: <ApiOutlined />, permissions: ['QUERY_SUBMIT_DML'] },
            { id: 'api-requests', to: '/api-requests', label: t('nav.apiRequests'), icon: <ProfileOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
          ],
        },
        {
          id: 'workflow-deployments',
          label: t('nav.sub_deployments'),
          domain: 'deployments',
          items: [
            { id: 'deployments', to: '/deployments', label: t('nav.deployments'), icon: <RocketOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
            { id: 'deployment-versions', to: '/deployment-versions', label: t('nav.deploymentVersions'), icon: <DeploymentUnitOutlined />, permissions: ['DEPLOYMENT_PIPELINE_MANAGE', 'DEPLOYMENT_REVIEW', 'QUERY_ADMIN'] },
            { id: 'deployment-metrics', to: '/deployment-metrics', label: t('nav.deploymentMetrics'), icon: <FundOutlined />, permissions: ['DEPLOYMENT_PIPELINE_MANAGE', 'DEPLOYMENT_REVIEW', 'QUERY_ADMIN'] },
          ],
        },
        {
          id: 'workflow-request-groups',
          label: t('nav.sub_request_groups'),
          items: [
            { id: 'request-groups', to: '/request-groups', label: t('nav.requestGroups'), icon: <BlockOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
            { id: 'request-group-reviews', to: '/request-groups/reviews', label: t('nav.requestGroupReviews'), icon: <CarryOutOutlined />, permissions: ['QUERY_REVIEW'] },
          ],
        },
        {
          id: 'workflow-schema-changes',
          label: t('nav.sub_schema_changes'),
          items: [
            { id: 'schema-change-sets', to: '/schema-change-sets', label: t('nav.schemaChangeSets'), icon: <BranchesOutlined />, permissions: ['SCHEMA_CHANGE_MANAGE'] },
            { id: 'schema-drift', to: '/schema-drift', label: t('nav.schemaDrift'), icon: <DiffOutlined />, permissions: ['SCHEMA_CHANGE_MANAGE'] },
          ],
        },
        {
          id: 'workflow-access-lifecycle',
          label: t('nav.sub_access_lifecycle'),
          items: [
            { id: 'request-access', to: '/access-requests', label: t('nav.request_access'), icon: <KeyOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
            { id: 'request-erasure', to: '/lifecycle/erasure', label: t('nav.request_erasure'), icon: <FieldTimeOutlined />, permissions: ['QUERY_SUBMIT_SELECT'] },
            { id: 'erasure-review', to: '/lifecycle/erasure-reviews', label: t('nav.erasure_review'), icon: <FileProtectOutlined />, permissions: ['ERASURE_REVIEW'] },
            { id: 'attestation-reviews', to: '/reviews/attestations', label: t('nav.attestation_reviews'), icon: <CheckSquareOutlined />, permissions: ['ATTESTATION_REVIEW'] },
          ],
        },
      ],
    },
    {
      id: 'connections',
      label: t('nav.group_connections'),
      subgroups: [
        {
          id: 'connections-database',
          label: t('nav.sub_database'),
          items: [
            { id: 'datasources', to: '/datasources', label: t('nav.datasources'), icon: <DatabaseOutlined />, permissions: ['DATASOURCE_MANAGE'] },
            { id: 'connectors', to: '/admin/connectors', label: t('nav.connectors'), icon: <AppstoreOutlined />, permissions: ['DATASOURCE_MANAGE'] },
            { id: 'drivers', to: '/admin/drivers', label: t('nav.custom_drivers'), icon: <BuildOutlined />, permissions: ['DATASOURCE_MANAGE'] },
          ],
        },
        {
          id: 'connections-api',
          label: t('nav.sub_api'),
          domain: 'apis',
          items: [
            { id: 'api-connectors', to: '/api-connectors', label: t('nav.apiConnectors'), icon: <LinkOutlined />, permissions: ['API_CONNECTOR_MANAGE'] },
          ],
        },
        {
          id: 'connections-deployments',
          label: t('nav.sub_deployments'),
          domain: 'deployments',
          items: [
            { id: 'deployment-pipelines', to: '/admin/deployment-pipelines', label: t('nav.deploymentPipelines'), icon: <PartitionOutlined />, permissions: ['DEPLOYMENT_PIPELINE_MANAGE'] },
          ],
        },
      ],
    },
    {
      id: 'identity',
      label: t('nav.group_identity'),
      subgroups: [
        {
          id: 'security-identity',
          label: t('nav.sub_identity'),
          items: [
            { id: 'users', to: '/admin/users', label: t('nav.users'), icon: <TeamOutlined />, permissions: ['USER_MANAGE'] },
            { id: 'groups', to: '/admin/groups', label: t('nav.groups'), icon: <UsergroupAddOutlined />, permissions: ['GROUP_MANAGE'] },
            { id: 'roles', to: '/admin/roles', label: t('nav.roles'), icon: <SafetyOutlined />, permissions: ['ROLE_MANAGE'] },
            { id: 'service-accounts', to: '/admin/service-accounts', label: t('nav.service_accounts'), icon: <RobotOutlined />, permissions: ['SERVICE_ACCOUNT_MANAGE'] },
            { id: 'saml', to: '/admin/saml', label: t('nav.saml'), icon: <IdcardOutlined />, permissions: ['SSO_CONFIGURE'] },
            { id: 'oauth2', to: '/admin/oauth2', label: t('nav.oauth2'), icon: <LoginOutlined />, permissions: ['SSO_CONFIGURE'] },
            { id: 'scim', to: '/admin/scim', label: t('nav.scim'), icon: <CloudSyncOutlined />, permissions: ['SSO_CONFIGURE'] },
          ],
        },
        {
          id: 'security-access-control',
          label: t('nav.sub_access_control'),
          items: [
            { id: 'access-requests', to: '/admin/access-requests', label: t('nav.access_requests'), icon: <UnlockOutlined />, permissions: ['ACCESS_REQUEST_REVIEW'] },
            { id: 'review-plans', to: '/admin/review-plans', label: t('nav.review_plans'), icon: <ApartmentOutlined />, permissions: ['REVIEW_PLAN_MANAGE'] },
            { id: 'routing-policies', to: '/admin/routing-policies', label: t('nav.routing_policies'), icon: <NodeIndexOutlined />, permissions: ['ROUTING_POLICY_MANAGE'] },
            { id: 'decision-hooks', to: '/admin/decision-hooks', label: t('nav.decision_hooks'), icon: <GatewayOutlined />, permissions: ['ROUTING_POLICY_MANAGE'] },
            { id: 'sql-review', to: '/admin/sql-review', label: t('nav.sql_review'), icon: <FileSearchOutlined />, permissions: ['SQL_REVIEW_MANAGE'] },
            { id: 'over-provisioned-access', to: '/admin/over-provisioned-access', label: t('nav.over_provisioned_access'), icon: <UserDeleteOutlined />, permissions: ['ACCESS_USAGE_REPORT_VIEW'] },
            { id: 'privileged-access', to: '/admin/privileged-access', label: t('nav.privileged_access'), icon: <CrownOutlined />, permissions: ['DATASOURCE_PERMISSION_MANAGE', 'ACCESS_USAGE_REPORT_VIEW'] },
            { id: 'access-simulation', to: '/admin/access-simulations', label: t('nav.access_simulation'), icon: <AimOutlined />, permissions: ['DATASOURCE_PERMISSION_MANAGE', 'ACCESS_USAGE_REPORT_VIEW'] },
            { id: 'break-glass', to: '/admin/break-glass', label: t('nav.break_glass'), icon: <ThunderboltOutlined />, permissions: ['BREAK_GLASS_VIEW'] },
          ],
        },
      ],
    },
    {
      id: 'governance',
      label: t('nav.group_governance'),
      subgroups: [
        {
          id: 'security-data-governance',
          label: t('nav.sub_data_governance'),
          items: [
            { id: 'data-classifications', to: '/admin/data-classifications', label: t('nav.data_classifications'), icon: <TagsOutlined />, permissions: ['DATA_CLASSIFICATION_MANAGE'] },
            { id: 'lifecycle', to: '/admin/lifecycle/policies', label: t('nav.lifecycle'), icon: <HourglassOutlined />, permissions: ['RETENTION_POLICY_MANAGE'] },
            { id: 'attestation', to: '/admin/attestation', label: t('nav.attestation'), icon: <SolutionOutlined />, permissions: ['ATTESTATION_CAMPAIGN_MANAGE'] },
          ],
        },
        {
          id: 'security-audit-compliance',
          label: t('nav.sub_audit_compliance'),
          items: [
            { id: 'audit', to: '/admin/audit-log', label: t('nav.audit'), icon: <SafetyCertificateOutlined />, permissions: ['AUDIT_LOG_VIEW'] },
            { id: 'audit-sinks', to: '/admin/audit-sinks', label: t('nav.audit_sinks'), icon: <CloudUploadOutlined />, permissions: ['AUDIT_SINK_MANAGE'] },
            { id: 'auditor', to: '/admin/auditor', label: t('nav.auditor'), icon: <AuditOutlined />, permissions: ['COMPLIANCE_REPORT_VIEW'] },
          ],
        },
      ],
    },
    {
      id: 'system',
      label: t('nav.group_system'),
      items: [
        { id: 'datasource-health', to: '/admin/datasource-health', label: t('nav.datasource_health'), icon: <DashboardOutlined />, permissions: ['DATASOURCE_MANAGE'] },
        { id: 'anomalies', to: '/admin/anomalies', label: t('nav.anomalies'), icon: <WarningOutlined />, permissions: ['ANOMALY_MANAGE'] },
        { id: 'channels', to: '/admin/notifications', label: t('nav.notifications'), icon: <BellOutlined />, permissions: ['NOTIFICATION_CHANNEL_MANAGE'] },
        { id: 'slack', to: '/admin/slack', label: t('nav.slack'), icon: <SlackOutlined />, permissions: ['NOTIFICATION_CHANNEL_MANAGE'] },
        { id: 'languages', to: '/admin/languages', label: t('nav.languages'), icon: <GlobalOutlined />, permissions: ['LOCALIZATION_CONFIGURE'] },
        { id: 'governance-domains', to: '/admin/governance-domains', label: t('nav.governance_domains'), icon: <ControlOutlined />, permissions: ['SETUP_PROGRESS_VIEW'] },
      ],
      subgroups: [
        {
          id: 'system-ai',
          label: t('nav.sub_ai'),
          items: [
            { id: 'ai', to: '/admin/ai-configs', label: t('nav.ai_configs'), icon: <ExperimentOutlined />, permissions: ['AI_MANAGE'] },
            { id: 'ai-analyses', to: '/admin/ai-analyses', label: t('nav.ai_analyses'), icon: <BarChartOutlined />, permissions: ['AI_MANAGE'] },
            { id: 'langfuse', to: '/admin/langfuse', label: t('nav.langfuse'), icon: <LineChartOutlined />, permissions: ['AI_MANAGE'] },
            { id: 'help-agent', to: '/admin/help-agent', label: t('nav.help_agent'), icon: <QuestionCircleOutlined />, permissions: ['AI_MANAGE'] },
          ],
        },
      ],
    },
    {
      id: 'platform',
      label: t('nav.group_platform'),
      items: [
        { id: 'organizations', to: '/admin/organizations', label: t('nav.organizations'), icon: <BankOutlined />, permissions: [], platformAdmin: true },
        { id: 'jobs', to: '/admin/jobs', label: t('nav.jobs'), icon: <ScheduleOutlined />, permissions: [], platformAdmin: true },
      ],
    },
  ];
  return GROUPS;
}

/** Destinations that are always on screen and so cannot be pinned. */
const UNPINNABLE_GROUP = 'general';

interface SidebarProps {
  user: AuthUser;
  pendingCount: number;
  collapsed: boolean;
  onToggle: () => void;
  mobileOpen: boolean;
  onMobileClose: () => void;
}

export function Sidebar({
  user, pendingCount, collapsed, onToggle, mobileOpen, onMobileClose,
}: SidebarProps) {
  const { t } = useTranslation();
  const location = useLocation();
  const expandedSubgroups = usePreferencesStore((s) => s.navExpandedSubgroups);
  const toggleSubgroup = usePreferencesStore((s) => s.toggleNavSubgroup);
  const pinnedIds = usePreferencesStore((s) => s.navPinned);
  const togglePinned = usePreferencesStore((s) => s.toggleNavPinned);

  const visibleGroups = visibleNavGroups(buildNavGroups(t), user);
  const entries = flattenNav(visibleGroups);
  const activePath = activeNavEntry(entries, location.pathname)?.item.to ?? null;
  const isActive = (to: string) => to === activePath;

  const unpinnable = new Set(
    visibleGroups.filter((g) => g.id === UNPINNABLE_GROUP).flatMap((g) => g.items.map((i) => i.id)),
  );
  // In the order the user pinned them; an id the user can no longer see simply stops matching.
  const pinnedItems = pinnedIds
    .map((id) => entries.find((e) => e.item.id === id)?.item)
    .filter((it): it is NavItem => it !== undefined && !unpinnable.has(it.id));

  const renderItem = (item: NavItem, inPinned = false) => {
    const active = isActive(item.to);
    const pinned = pinnedIds.includes(item.id);
    const link = (
      <NavLink
        to={item.to}
        // The callback form so react-router does not append its own `active` class on top: its
        // matching is plain-prefix, which is exactly what `isActive` exists to override.
        className={() => `af-sidebar-item${active ? ' active' : ''}`}
        aria-label={collapsed ? item.label : undefined}
      >
        {active && (
          <m.span
            layoutId={inPinned ? 'af-nav-active-pinned' : 'af-nav-active'}
            className="af-sidebar-active-bg"
            transition={transitions.layout}
          />
        )}
        <span className="af-sidebar-icon">{item.icon}</span>
        {!collapsed && <span className="af-sidebar-label">{item.label}</span>}
        {item.badge === 'pending' && pendingCount > 0 && (
          collapsed ? (
            <span className="af-badge-dot" />
          ) : (
            <span className="af-sidebar-badge mono">{pendingCount}</span>
          )
        )}
      </NavLink>
    );
    return (
      <div key={inPinned ? `pinned-${item.id}` : item.id} className="af-sidebar-row">
        {collapsed ? <Tooltip title={item.label} placement="right">{link}</Tooltip> : link}
        {!collapsed && !unpinnable.has(item.id) && (
          <button
            type="button"
            className={`af-sidebar-pin${pinned ? ' on' : ''}`}
            aria-pressed={pinned}
            aria-label={t(pinned ? 'nav.unpin' : 'nav.pin', { page: item.label })}
            onClick={() => togglePinned(item.id)}
          >
            {pinned ? <PushpinFilled /> : <PushpinOutlined />}
          </button>
        )}
      </div>
    );
  };

  // Icon rail: no room for headers, so every block (a group's own items, then each sub-section)
  // renders flat and is separated by the thin divider line.
  const railBlocks = visibleGroups.flatMap((g, gi) => [
    ...(g.items.length > 0 ? [{ key: g.id, items: g.items, pinned: false }] : []),
    ...(gi === 0 && pinnedItems.length > 0 ? [{ key: 'pinned', items: pinnedItems, pinned: true }] : []),
    ...g.subgroups.map((s) => ({ key: s.id, items: s.items, pinned: false })),
  ]);

  const pinnedGroup = pinnedItems.length > 0 && (
    <div className="af-sidebar-group" role="group" aria-labelledby="af-nav-group-pinned">
      <div id="af-nav-group-pinned" className="af-sidebar-divider mono">
        {t('nav.group_pinned')}
      </div>
      {pinnedItems.map((it) => renderItem(it, true))}
    </div>
  );

  return (
    <>
      <aside className={`af-sidebar${collapsed ? ' collapsed' : ''}${mobileOpen ? ' mobile-open' : ''}`}>
        <div className="af-sidebar-brand">
          <LogoMark size={26} className="af-logo-mark" />
          {!collapsed && (
            <div className="u-flex-1 u-min-w-0">
              <div className="u-text-md u-fw-600" style={{ letterSpacing: '-0.01em' }}>
                {t('common.app_name')}
              </div>
              <VersionBadge />
            </div>
          )}
          <button className="af-sidebar-collapse-btn" onClick={onToggle} aria-label={t('common.toggle_sidebar')}>
            {collapsed ? <RightOutlined /> : <LeftOutlined />}
          </button>
          <button className="af-icon-btn af-mobile-menu-btn" onClick={onMobileClose} aria-label={t('common.close_menu')}>
            <CloseOutlined />
          </button>
        </div>
        <nav className="af-sidebar-nav">
          {collapsed
            ? railBlocks.map((block, bi) => (
              <div key={block.key} className="af-sidebar-group">
                {bi > 0 && <div className="af-sidebar-divider-line" />}
                {block.items.map((it) => renderItem(it, block.pinned))}
              </div>
            ))
            : visibleGroups.map((group, groupIndex) => (
              // The group is an a11y grouping so that sub-section headers reusing a label
              // ("Database", "API", "Deployments" appear under both Workflow and Connections)
              // are still told apart by assistive tech. It borrows the visible heading rather
              // than repeating it in an aria-label, which would be announced twice.
              <Fragment key={group.id}>
              <div
                className="af-sidebar-group"
                role={group.label ? 'group' : undefined}
                aria-labelledby={group.label ? `af-nav-group-${group.id}` : undefined}
              >
                {group.label && (
                  <div id={`af-nav-group-${group.id}`} className="af-sidebar-divider mono">
                    {group.label}
                  </div>
                )}
                {group.items.map((it) => renderItem(it))}
                {group.subgroups.map((sub) => {
                  // Every sub-section starts closed; only the ids the user has opened are
                  // persisted. The sub-section holding the current route always renders open on
                  // top of that, so landing on a deep link — or a fresh login — can never leave
                  // the active page hidden behind a collapsed header. Its header is disabled
                  // while that holds: toggling would mutate the stored preference without
                  // changing anything on screen, and would announce the wrong action.
                  const lockedOpen = sub.items.some((it) => isActive(it.to));
                  const open = lockedOpen || expandedSubgroups.includes(sub.id);
                  const listId = `af-nav-sub-${sub.id}`;
                  return (
                    <div key={sub.id} className="af-sidebar-subgroup">
                      <button
                        type="button"
                        className={`af-sidebar-subgroup-header mono${open ? ' open' : ''}`}
                        aria-expanded={open}
                        aria-controls={listId}
                        disabled={lockedOpen}
                        aria-label={lockedOpen
                          ? t('nav.section_locked_open', { section: sub.label })
                          : open
                            ? t('nav.collapse_section', { section: sub.label })
                            : t('nav.expand_section', { section: sub.label })}
                        onClick={() => toggleSubgroup(sub.id)}
                      >
                        <DownOutlined className="af-sidebar-subgroup-chevron" />
                        <span>{sub.label}</span>
                      </button>
                      <div id={listId} className="af-sidebar-subgroup-items">
                        {open && sub.items.map((it) => renderItem(it))}
                      </div>
                    </div>
                  );
                })}
              </div>
              {groupIndex === 0 && pinnedGroup}
              </Fragment>
            ))}
        </nav>
        <div className="af-sidebar-footer">
          {(() => {
            const label = userDisplay(user.display_name, user.email);
            const role = roleLabel(t, user.role);
            return (
              <div
                className="u-flex u-items-center" style={{ gap: 10, justifyContent: collapsed ? 'center' : 'flex-start' }}
                title={collapsed ? `${label} · ${role}` : undefined}
              >
                <Avatar name={label} size={28} />
                {!collapsed && (
                  <div className="u-min-w-0 u-flex-1">
                    <div
                      className="u-text-sm u-fw-500 u-nowrap u-overflow-hidden u-ellipsis"
                    >
                      {label}
                    </div>
                    <div className="mono muted u-text-2xs">{role}</div>
                  </div>
                )}
              </div>
            );
          })()}
        </div>
      </aside>
      <div
        className={`af-sidebar-scrim${mobileOpen ? ' visible' : ''}`}
        aria-hidden={!mobileOpen}
        onClick={onMobileClose}
      />
    </>
  );
}
