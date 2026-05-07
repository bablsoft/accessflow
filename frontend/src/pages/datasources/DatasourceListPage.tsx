import { useMemo, useState } from 'react';
import { Button, Input } from 'antd';
import { DatabaseOutlined, PlusOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { Pill } from '@/components/common/Pill';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import type { Datasource } from '@/types/api';

export function DatasourceListPage() {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const navigate = useNavigate();
  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ size: 100 }),
    queryFn: () => listDatasources({ size: 100 }),
  });
  const datasourcesData = datasourcesQuery.data;
  const total = datasourcesData?.total_elements ?? 0;
  const filtered = useMemo(() => {
    const list = datasourcesData?.content ?? [];
    return list.filter((d) => !q || d.name.toLowerCase().includes(q.toLowerCase()));
  }, [q, datasourcesData]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('datasources.list.title')}
        subtitle={t('datasources.list.subtitle')}
        actions={<Button type="primary" icon={<PlusOutlined />}>{t('datasources.list.add_button')}</Button>}
      />
      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          gap: 8,
        }}
      >
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder={t('datasources.list.search_placeholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 280 }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11, alignSelf: 'center' }}>
          {t('datasources.list.count_label', { filtered: filtered.length, total })}
        </span>
      </div>
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 24,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))',
          gap: 14,
          alignContent: 'start',
        }}
      >
        {datasourcesQuery.isLoading && (
          <div className="muted" style={{ gridColumn: '1 / -1', textAlign: 'center', padding: 40 }}>
            {t('datasources.list.loading')}
          </div>
        )}
        {datasourcesQuery.isError && (
          <div className="muted" style={{ gridColumn: '1 / -1', textAlign: 'center', padding: 40, color: 'var(--risk-high)' }}>
            {t('datasources.list.error')}
          </div>
        )}
        {datasourcesQuery.isSuccess && filtered.length === 0 && (
          <div className="muted" style={{ gridColumn: '1 / -1', textAlign: 'center', padding: 40 }}>
            {t('datasources.list.empty')}
          </div>
        )}
        {filtered.map((ds) => (
          <DsCard
            key={ds.id}
            ds={ds}
            onOpen={() => navigate(`/datasources/${ds.id}/settings`)}
          />
        ))}
      </div>
    </div>
  );
}

interface CardProps {
  ds: Datasource;
  onOpen: () => void;
}

function DsCard({ ds, onOpen }: CardProps) {
  const { t } = useTranslation();
  // TODO(FE-XX): swap to useReviewPlans() once /review-plans ships.
  const planLabel = ds.review_plan_id
    ? ds.review_plan_id.slice(0, 8)
    : t('datasources.list.stat_plan_unknown');
  return (
    <div
      onClick={onOpen}
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 16,
        cursor: 'pointer',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 8,
            background: ds.db_type === 'POSTGRESQL' ? '#dbeafe' : '#fef3c7',
            color: ds.db_type === 'POSTGRESQL' ? '#1e40af' : '#92400e',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          <DatabaseOutlined style={{ fontSize: 18 }} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 2 }}>{ds.name}</div>
          <div
            className="mono muted"
            style={{
              fontSize: 11,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {ds.host}:{ds.port} / {ds.database_name}
          </div>
        </div>
        {ds.active ? (
          <Pill
            fg="var(--risk-low)"
            bg="var(--risk-low-bg)"
            border="var(--risk-low-border)"
            withDot
            size="sm"
          >
            active
          </Pill>
        ) : (
          <Pill
            fg="var(--fg-muted)"
            bg="var(--status-neutral-bg)"
            border="var(--status-neutral-border)"
            withDot
            size="sm"
          >
            inactive
          </Pill>
        )}
      </div>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
        <Pill bg="var(--bg-sunken)" size="sm">
          {ds.db_type}
        </Pill>
        <Pill bg="var(--bg-sunken)" size="sm">
          SSL · {ds.ssl_mode}
        </Pill>
        {ds.ai_analysis_enabled && (
          <Pill
            fg="var(--accent)"
            bg="var(--accent-bg)"
            border="var(--accent-border)"
            size="sm"
          >
            <ThunderboltOutlined style={{ fontSize: 9 }} /> AI
          </Pill>
        )}
      </div>
      <div
        style={{
          paddingTop: 12,
          borderTop: '1px solid var(--border)',
        }}
      >
        <div
          className="muted mono"
          style={{
            fontSize: 10,
            textTransform: 'uppercase',
            letterSpacing: '0.04em',
            marginBottom: 4,
          }}
        >
          {t('datasources.list.stat_plan')}
        </div>
        <div style={{ fontSize: 13, fontWeight: 600, fontFamily: 'var(--font-mono)' }}>
          {planLabel}
        </div>
      </div>
    </div>
  );
}
