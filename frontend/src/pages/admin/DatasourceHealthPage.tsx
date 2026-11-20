import { Button, Card, Col, Row, Skeleton, Space, Statistic, Tag } from 'antd';
import { Pie } from '@ant-design/charts';
import { DashboardOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Page, PageBody, StatusTag } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { datasourceHealthKeys, fetchDatasourceHealth } from '@/api/datasourceHealth';
import { dbTypeLabel } from '@/utils/enumLabels';
import type { DatasourceHealth } from '@/types/api';

const PAGE_SIZE = 50;
const REFRESH_MS = 30_000;

export default function DatasourceHealthPage() {
  const { t } = useTranslation();

  const query = useQuery({
    queryKey: datasourceHealthKeys.list({ page: 0, size: PAGE_SIZE }),
    queryFn: () => fetchDatasourceHealth({ page: 0, size: PAGE_SIZE }),
    refetchInterval: REFRESH_MS,
  });

  const rows = query.data?.content ?? [];

  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-datasource-health"
        title={t('admin.datasource_health.title')}
        subtitle={t('admin.datasource_health.subtitle')}
        actions={
          <Space>
            <Button
              icon={<ReloadOutlined />}
              loading={query.isFetching}
              onClick={() => query.refetch()}
            >
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />
      <PageBody>
        {query.isLoading ? (
          <Row gutter={[16, 16]}>
            {[0, 1, 2, 3].map((i) => (
              <Col key={i} xs={24} lg={12} xxl={8}>
                <Card>
                  <Skeleton active paragraph={{ rows: 4 }} />
                </Card>
              </Col>
            ))}
          </Row>
        ) : query.isError ? (
          <EmptyState
            icon={<DashboardOutlined className="u-text-xl" />}
            title={t('admin.datasource_health.error_title')}
            description={t('admin.datasource_health.error_description')}
            action={
              <Button icon={<ReloadOutlined />} onClick={() => query.refetch()}>
                {t('common.retry')}
              </Button>
            }
          />
        ) : rows.length === 0 ? (
          <EmptyState
            icon={<DashboardOutlined className="u-text-xl" />}
            title={t('admin.datasource_health.empty_title')}
            description={t('admin.datasource_health.empty_description')}
          />
        ) : (
          <Row gutter={[16, 16]} data-testid="datasource-health-grid">
            {rows.map((row) => (
              <Col key={row.datasource_id} xs={24} lg={12} xxl={8}>
                <HealthCard row={row} />
              </Col>
            ))}
          </Row>
        )}
      </PageBody>
    </Page>
  );
}

function HealthCard({ row }: { row: DatasourceHealth }) {
  const { t } = useTranslation();
  return (
    <Card
      data-testid="datasource-health-card"
      title={
        <Space>
          <span>{row.datasource_name}</span>
          <Tag>{dbTypeLabel(t, row.db_type)}</Tag>
          {!row.active && <StatusTag tone="neutral">{t('admin.datasource_health.inactive')}</StatusTag>}
        </Space>
      }
    >
      <div className="u-flex u-wrap u-gap-6 u-items-center">
        <PoolRing row={row} />
        <div
          className="u-flex-1 u-grid u-gap-4" style={{ minWidth: 200, gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' }}
        >
          <Statistic
            title={t('admin.datasource_health.queries_last_24h')}
            value={row.queries_last_24h}
          />
          <Statistic
            title={t('admin.datasource_health.errors_last_24h')}
            value={row.errors_last_24h}
            styles={
              row.errors_last_24h > 0
                ? { content: { color: 'var(--danger)' } }
                : undefined
            }
          />
          <Statistic
            title={t('admin.datasource_health.execution_p50')}
            value={row.execution_ms_p50 ?? '—'}
            suffix={row.execution_ms_p50 != null ? t('admin.datasource_health.unit_ms') : undefined}
          />
          <Statistic
            title={t('admin.datasource_health.execution_p95')}
            value={row.execution_ms_p95 ?? '—'}
            suffix={row.execution_ms_p95 != null ? t('admin.datasource_health.unit_ms') : undefined}
          />
        </div>
      </div>
      {row.replicas.length > 0 && (
        <div className="u-mt-4" data-testid="replica-health-row">
          <div className="muted u-text-xs" style={{ marginBottom: 6 }}>
            {t('admin.datasource_health.replicas_title', {
              healthy: row.replicas.filter((r) => r.healthy).length,
              total: row.replicas.length,
            })}
          </div>
          <Space wrap>
            {row.replicas.map((replica) => (
              <StatusTag tone={replica.healthy ? 'success' : 'danger'} key={replica.endpoint_id} className="mono">
                {replica.label}
                {replica.pool_active != null && replica.pool_total != null
                  ? ` · ${replica.pool_active}/${replica.pool_total}`
                  : ''}
              </StatusTag>
            ))}
          </Space>
        </div>
      )}
    </Card>
  );
}

function PoolRing({ row }: { row: DatasourceHealth }) {
  const { t } = useTranslation();
  if (row.pool_active == null || row.pool_idle == null || row.pool_max == null) {
    return (
      <div
        data-testid="datasource-health-pool-uninitialized"
        className="u-flex u-items-center u-justify-center u-text-center u-fg-muted u-text-xs u-p-3 u-w-sm" style={{ minHeight: 120 }}
      >
        {t('admin.datasource_health.pool_not_initialized')}
      </div>
    );
  }
  const total = row.pool_total ?? row.pool_active + row.pool_idle;
  const free = Math.max(0, row.pool_max - total);
  const data = [
    { type: t('admin.datasource_health.pool_active'), value: row.pool_active },
    { type: t('admin.datasource_health.pool_idle'), value: row.pool_idle },
    { type: t('admin.datasource_health.pool_free'), value: free },
  ];
  return (
    <div data-testid="datasource-health-pool-ring" className="u-w-sm">
      <Pie
        data={data}
        angleField="value"
        colorField="type"
        innerRadius={0.6}
        height={140}
        width={160}
        legend={false}
        label={false}
      />
      <div className="mono muted u-text-2xs u-text-center u-mt-1">
        {t('admin.datasource_health.pool_caption', {
          active: row.pool_active,
          idle: row.pool_idle,
          waiting: row.pool_waiting ?? 0,
          max: row.pool_max,
        })}
      </div>
    </div>
  );
}
