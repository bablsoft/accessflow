import { useMemo, useState } from 'react';
import { Button, Input, Select, Skeleton, Space, Table, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import { Avatar } from '@/components/common/Avatar';
import {
  listPrivilegedAccess,
  privilegedAccessKeys,
  type PrivilegedAccessFilters,
} from '@/api/privilegedAccess';
import { adminErrorMessage } from '@/utils/apiErrors';
import { fmtDate, fmtNum, timeAgo } from '@/utils/dateFormat';
import {
  STANDING_BYPASS_KINDS,
  permissionSourceKindLabel,
  standingBypassKindLabel,
} from '@/utils/enumLabels';
import { standingBypassKindColor } from '@/utils/statusColors';
import type { PrivilegedAccessRow, StandingBypassKind } from '@/types/api';

const PAGE_SIZE = 20;

// `user_id` binds to a UUID server-side and a partial value is a guaranteed 400, so the filter only
// reaches the request once the text is a whole id — typing (rather than pasting) one must not turn
// the table into an error state on every keystroke.
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The standing privileged-access report (#968): every identity that can reach data without
 * appearing in any permission table — QUERY_ADMIN holders, who skip the per-datasource gate
 * outright, and break-glass grantees — one row each, with the evidence of them having used it.
 *
 * Read-only and advisory. Nothing on this page revokes anything — a bypass ends through a role
 * change, an attestation campaign, or an explicit permission edit.
 */
export default function PrivilegedAccessPage() {
  const { t } = useTranslation();

  const [page, setPage] = useState(0);
  const [kind, setKind] = useState<StandingBypassKind | 'all'>('all');
  const [userId, setUserId] = useState('');

  const filters: PrivilegedAccessFilters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      kind: kind === 'all' ? undefined : kind,
      user_id: UUID_RE.test(userId.trim()) ? userId.trim() : undefined,
    }),
    [page, kind, userId],
  );

  const report = useQuery({
    queryKey: privilegedAccessKeys.report(filters),
    queryFn: () => listPrivilegedAccess(filters),
  });

  const rows = report.data?.content ?? [];

  const columns: TableColumnsType<PrivilegedAccessRow> = useMemo(
    () => [
      {
        title: t('privileged_access.col_identity'),
        key: 'identity',
        render: (_: unknown, row: PrivilegedAccessRow) => (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Avatar name={row.email} size={24} />
            <div>
              <div style={{ fontSize: 13 }}>{row.display_name ?? row.email}</div>
              <div className="mono muted" style={{ fontSize: 11 }}>
                {row.email}
              </div>
            </div>
          </div>
        ),
      },
      {
        title: t('privileged_access.col_role'),
        key: 'role',
        width: 200,
        render: (_: unknown, row: PrivilegedAccessRow) =>
          // A break-glass grantee need not hold a role at all; asserting "Custom role" for a null
          // one would put a role on a security report that does not exist.
          row.role_name === null ? (
            <span className="muted">{t('privileged_access.none')}</span>
          ) : (
            <div>
              <div style={{ fontSize: 13 }}>{row.role_name}</div>
              <div className="muted" style={{ fontSize: 11 }}>
                {row.system_role
                  ? t('privileged_access.system_role')
                  : t('privileged_access.custom_role')}
              </div>
            </div>
          ),
      },
      {
        title: t('privileged_access.col_bypass'),
        key: 'bypass',
        width: 220,
        render: (_: unknown, row: PrivilegedAccessRow) => (
          <Space size={4} wrap>
            {row.bypass_kinds.map((k) => {
              const color = standingBypassKindColor(k);
              return (
                <Pill key={k} fg={color.fg} bg={color.bg} border={color.border} withDot size="sm">
                  {standingBypassKindLabel(t, k)}
                </Pill>
              );
            })}
          </Space>
        ),
      },
      {
        title: t('privileged_access.col_break_glass'),
        key: 'break_glass',
        render: (_: unknown, row: PrivilegedAccessRow) =>
          row.break_glass_grants.length === 0 ? (
            <span className="muted">{t('privileged_access.none')}</span>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {row.break_glass_grants.map((g) => (
                <div key={g.source_id} style={{ fontSize: 12 }}>
                  <span className="mono">{g.datasource_name}</span>
                  <span className="muted" style={{ marginLeft: 6, fontSize: 11 }}>
                    {g.source_kind === 'GROUP' && g.group_name
                      ? t('privileged_access.via_group', { group: g.group_name })
                      : permissionSourceKindLabel(t, g.source_kind)}
                    {' · '}
                    {g.expires_at
                      ? t('privileged_access.expires_at', { date: fmtDate(g.expires_at) })
                      : t('privileged_access.never_expires')}
                  </span>
                </div>
              ))}
            </div>
          ),
      },
      {
        title: t('privileged_access.col_queries'),
        key: 'queries',
        width: 170,
        render: (_: unknown, row: PrivilegedAccessRow) => (
          <EvidenceCell
            count={row.evidence.submitted_query_count}
            lastAt={row.evidence.last_submitted_at}
          />
        ),
      },
      {
        title: t('privileged_access.col_break_glass_runs'),
        key: 'break_glass_runs',
        width: 170,
        render: (_: unknown, row: PrivilegedAccessRow) => (
          <EvidenceCell
            count={row.evidence.break_glass_execution_count}
            lastAt={row.evidence.last_break_glass_at}
          />
        ),
      },
    ],
    [t],
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('privileged_access.title')}
        subtitle={t('privileged_access.subtitle')}
        actions={
          <Button icon={<ReloadOutlined />} onClick={() => report.refetch()}>
            {t('common.refresh')}
          </Button>
        }
      />

      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 8,
        }}
      >
        <Select<StandingBypassKind | 'all'>
          value={kind}
          onChange={(v) => {
            setKind(v);
            setPage(0);
          }}
          style={{ width: 200 }}
          aria-label={t('privileged_access.filter_kind')}
          options={[
            { value: 'all', label: t('privileged_access.filter_all_kinds') },
            ...STANDING_BYPASS_KINDS.map((k) => ({ value: k, label: standingBypassKindLabel(t, k) })),
          ]}
        />
        <Input
          placeholder={t('privileged_access.filter_user_placeholder')}
          value={userId}
          onChange={(e) => {
            setUserId(e.target.value);
            setPage(0);
          }}
          style={{ width: 300 }}
          className="mono"
          aria-label={t('privileged_access.filter_user_placeholder')}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11, alignSelf: 'center' }}>
          {t('privileged_access.count_label', { count: report.data?.total_elements ?? 0 })}
        </span>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {report.isLoading ? (
          <Skeleton active paragraph={{ rows: 8 }} style={{ padding: 24 }} />
        ) : report.isError ? (
          <EmptyState
            title={t('privileged_access.load_error')}
            description={adminErrorMessage(report.error)}
          />
        ) : rows.length === 0 ? (
          <EmptyState
            title={t('privileged_access.empty_title')}
            description={t('privileged_access.empty_description')}
          />
        ) : (
          <Table<PrivilegedAccessRow>
            rowKey="user_id"
            size="middle"
            dataSource={rows}
            columns={columns}
            scroll={{ x: 'max-content' }}
            data-testid="privileged-access-table"
            pagination={{
              pageSize: PAGE_SIZE,
              current: page + 1,
              total: report.data?.total_elements ?? 0,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </div>
    </div>
  );
}

/** A count with when it last happened; a null timestamp is "never", never a date. */
function EvidenceCell({ count, lastAt }: { count: number; lastAt: string | null }) {
  const { t } = useTranslation();
  return (
    <div>
      <div className="mono" style={{ fontSize: 12 }}>
        {fmtNum(count)}
      </div>
      {lastAt ? (
        <Tooltip title={fmtDate(lastAt)}>
          <span className="muted" style={{ fontSize: 11 }}>
            {t('privileged_access.last_at', { ago: timeAgo(lastAt) })}
          </span>
        </Tooltip>
      ) : (
        <span className="muted" style={{ fontSize: 11 }}>
          {t('privileged_access.never')}
        </span>
      )}
    </div>
  );
}
