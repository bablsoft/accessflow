import { useMemo, useState } from 'react';
import { App, Button, Input, Select, Skeleton, Space, Table, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { Pill } from '@/components/common/Pill';
import { Avatar } from '@/components/common/Avatar';
import {
  exportOverProvisionedCsv,
  grantUsageKeys,
  listOverProvisionedGrants,
  type OverProvisionedFilters,
} from '@/api/grantUsage';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { downloadBlob } from '@/utils/downloadBlob';
import {
  GRANT_RESOURCE_KINDS,
  GRANT_USAGE_RECOMMENDATIONS,
  grantResourceKindLabel,
  grantUsageRecommendationLabel,
} from '@/utils/enumLabels';
import { grantUsageRecommendationColor } from '@/utils/statusColors';
import type {
  GrantResourceKind,
  GrantUsageRecommendation,
  OverProvisionedGrant,
} from '@/types/api';

const PAGE_SIZE = 20;

/**
 * The standing "over-provisioned access" report (#625): every standing grant with the usage
 * evidence folded out of the audit log, worst first.
 *
 * Read-only and advisory. Nothing on this page revokes anything — revocation happens through
 * attestation campaigns or an admin's explicit permission change.
 */
export default function OverProvisionedAccessPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const [page, setPage] = useState(0);
  const [resourceKind, setResourceKind] = useState<GrantResourceKind | 'all'>('all');
  const [recommendation, setRecommendation] = useState<GrantUsageRecommendation[]>([]);
  const [userId, setUserId] = useState('');

  const filters: OverProvisionedFilters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      resource_kind: resourceKind === 'all' ? undefined : resourceKind,
      recommendation: recommendation.length > 0 ? recommendation : undefined,
      user_id: userId.trim() || undefined,
    }),
    [page, resourceKind, recommendation, userId],
  );

  const report = useQuery({
    queryKey: grantUsageKeys.report(filters),
    queryFn: () => listOverProvisionedGrants(filters),
  });

  const exportCsv = useMutation({
    mutationFn: () => exportOverProvisionedCsv(filters),
    onSuccess: (result) => {
      downloadBlob(result);
      if (result.truncated) {
        message.warning(t('over_provisioned.export_truncated'));
      }
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const rows = report.data?.content ?? [];

  const columns: TableColumnsType<OverProvisionedGrant> = useMemo(
    () => [
      {
        title: t('over_provisioned.col_subject'),
        key: 'subject',
        render: (_: unknown, row: OverProvisionedGrant) => (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Avatar name={row.user_email} size={24} />
            <div>
              <div style={{ fontSize: 13 }}>{row.user_display_name ?? row.user_email}</div>
              <div className="mono muted" style={{ fontSize: 11 }}>
                {row.user_email}
              </div>
            </div>
          </div>
        ),
      },
      {
        title: t('over_provisioned.col_resource'),
        key: 'resource',
        render: (_: unknown, row: OverProvisionedGrant) => (
          <div>
            <div className="mono" style={{ fontSize: 12 }}>
              {row.resource_name}
            </div>
            <div className="muted" style={{ fontSize: 11 }}>
              {grantResourceKindLabel(t, row.resource_kind)}
            </div>
          </div>
        ),
      },
      {
        title: t('over_provisioned.col_last_used'),
        key: 'last_used',
        width: 170,
        render: (_: unknown, row: OverProvisionedGrant) => {
          if (row.days_since_last_use !== null) {
            return <span>{t('over_provisioned.days_ago', { count: row.days_since_last_use })}</span>;
          }
          // A null timestamp alone does not mean "never used" — a grant too new to judge has one
          // too. Only NEVER_USED has actually been observed going unused, so only it earns the
          // critical colour; INSUFFICIENT_DATA stays muted.
          return row.recommendation === 'INSUFFICIENT_DATA' ? (
            <span className="muted">{t('over_provisioned.not_yet_observed')}</span>
          ) : (
            <span style={{ color: 'var(--risk-crit)' }}>{t('over_provisioned.never_used')}</span>
          );
        },
      },
      {
        title: t('over_provisioned.col_scope'),
        key: 'scope',
        width: 150,
        render: (_: unknown, row: OverProvisionedGrant) =>
          row.granted_target_count === null ? (
            <Tooltip title={t('over_provisioned.unrestricted_hint')}>
              <span className="muted">{t('over_provisioned.unrestricted')}</span>
            </Tooltip>
          ) : (
            <span className="mono" style={{ fontSize: 12 }}>
              {t('over_provisioned.scope_ratio', {
                used: row.used_target_count,
                granted: row.granted_target_count,
              })}
            </span>
          ),
      },
      {
        title: t('over_provisioned.col_usage'),
        key: 'usage',
        width: 120,
        render: (_: unknown, row: OverProvisionedGrant) => (
          <span className="mono" style={{ fontSize: 12 }}>
            {row.usage_count}
          </span>
        ),
      },
      {
        title: t('over_provisioned.col_recommendation'),
        key: 'recommendation',
        width: 180,
        render: (_: unknown, row: OverProvisionedGrant) => {
          const color = grantUsageRecommendationColor(row.recommendation);
          return (
            <Pill fg={color.fg} bg={color.bg} border={color.border} withDot size="sm">
              {grantUsageRecommendationLabel(t, row.recommendation)}
            </Pill>
          );
        },
      },
    ],
    [t],
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('over_provisioned.title')}
        subtitle={t('over_provisioned.subtitle')}
        actions={
          <Space>
            <Button
              icon={<DownloadOutlined />}
              loading={exportCsv.isPending}
              onClick={() => exportCsv.mutate()}
              data-testid="export-csv-button"
            >
              {t('over_provisioned.export_csv')}
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => report.refetch()}>
              {t('common.refresh')}
            </Button>
          </Space>
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
        <Select<GrantResourceKind | 'all'>
          value={resourceKind}
          onChange={(v) => {
            setResourceKind(v);
            setPage(0);
          }}
          style={{ width: 200 }}
          aria-label={t('over_provisioned.filter_resource_kind')}
          options={[
            { value: 'all', label: t('over_provisioned.filter_all_kinds') },
            ...GRANT_RESOURCE_KINDS.map((k) => ({ value: k, label: grantResourceKindLabel(t, k) })),
          ]}
        />
        <Select<GrantUsageRecommendation[]>
          mode="multiple"
          allowClear
          value={recommendation}
          onChange={(v) => {
            setRecommendation(v);
            setPage(0);
          }}
          style={{ minWidth: 280 }}
          aria-label={t('over_provisioned.filter_recommendation')}
          placeholder={t('over_provisioned.filter_all_recommendations')}
          options={GRANT_USAGE_RECOMMENDATIONS.map((r) => ({
            value: r,
            label: grantUsageRecommendationLabel(t, r),
          }))}
        />
        <Input
          placeholder={t('over_provisioned.filter_user_placeholder')}
          value={userId}
          onChange={(e) => {
            setUserId(e.target.value);
            setPage(0);
          }}
          style={{ width: 260 }}
          className="mono"
          aria-label={t('over_provisioned.filter_user_placeholder')}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11, alignSelf: 'center' }}>
          {t('over_provisioned.count_label', { count: report.data?.total_elements ?? 0 })}
        </span>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {report.isLoading ? (
          <Skeleton active paragraph={{ rows: 8 }} style={{ padding: 24 }} />
        ) : report.isError ? (
          <EmptyState
            title={t('over_provisioned.load_error')}
            description={adminErrorMessage(report.error)}
          />
        ) : rows.length === 0 ? (
          <EmptyState
            title={t('over_provisioned.empty_title')}
            description={t('over_provisioned.empty_description')}
          />
        ) : (
          <Table<OverProvisionedGrant>
            rowKey="id"
            size="middle"
            dataSource={rows}
            columns={columns}
            scroll={{ x: 'max-content' }}
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
