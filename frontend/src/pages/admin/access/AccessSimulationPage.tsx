import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { QueryTracePanel } from '@/components/access/QueryTracePanel';
import { EffectiveAccessPanel } from '@/components/access/EffectiveAccessPanel';
import { useAuthStore } from '@/store/authStore';
import { hasAnyPermission, hasPermission } from '@/utils/permissions';

/**
 * Access simulation (#1066): the query decision trace ("why would this be held?") and the reverse
 * access index ("who can write to this table?"). Both read-only — nothing on this page creates a
 * request or changes a grant.
 *
 * The route admits DATASOURCE_PERMISSION_MANAGE or ACCESS_USAGE_REPORT_VIEW; the trace tab needs
 * the former (its endpoint does), so an auditor lands on the reverse index alone.
 */
export default function AccessSimulationPage() {
  const { t } = useTranslation();
  const user = useAuthStore((s) => s.user);
  const canTrace = hasPermission(user, 'DATASOURCE_PERMISSION_MANAGE');
  const canIndex = hasAnyPermission(user, [
    'DATASOURCE_PERMISSION_MANAGE',
    'ACCESS_USAGE_REPORT_VIEW',
  ]);

  const items = [
    ...(canTrace
      ? [{ key: 'trace', label: t('access.simulation.tab_trace'), children: <QueryTracePanel /> }]
      : []),
    ...(canIndex
      ? [
          {
            key: 'effective',
            label: t('access.simulation.tab_effective'),
            children: <EffectiveAccessPanel />,
          },
        ]
      : []),
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('access.simulation.title')} subtitle={t('access.simulation.subtitle')} />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 28px 28px' }}>
        <Tabs items={items} />
      </div>
    </div>
  );
}
