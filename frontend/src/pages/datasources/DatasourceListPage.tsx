import { useMemo, useState } from 'react';
import { Button, Skeleton } from 'antd';
import { PlusOutlined, ThunderboltOutlined, WarningOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { Pill } from '@/components/common/Pill';
import { EmptyState } from '@/components/common/EmptyState';
import { FilterBar, Page, PageBody, StatusTag } from '@/components/layout';
import './datasource-list.css';
import { DatasourceIcon } from '@/components/datasources/DatasourceIcon';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { listReviewPlans, reviewPlanKeys } from '@/api/reviewPlans';
import { dbTypeLabel, sslModeLabel } from '@/utils/enumLabels';
import type { Datasource, ReviewPlan } from '@/types/api';

export function DatasourceListPage() {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const navigate = useNavigate();
  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ size: 100 }),
    queryFn: () => listDatasources({ size: 100 }),
  });
  const reviewPlansQuery = useQuery({
    queryKey: reviewPlanKeys.lists(),
    queryFn: listReviewPlans,
  });
  const plansById = useMemo(() => {
    const map = new Map<string, ReviewPlan>();
    for (const plan of reviewPlansQuery.data ?? []) {
      map.set(plan.id, plan);
    }
    return map;
  }, [reviewPlansQuery.data]);
  const datasourcesData = datasourcesQuery.data;
  const total = datasourcesData?.total_elements ?? 0;
  const filtered = useMemo(() => {
    const list = datasourcesData?.content ?? [];
    return list.filter((d) => !q || d.name.toLowerCase().includes(q.toLowerCase()));
  }, [q, datasourcesData]);

  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-datasources"
        title={t('datasources.list.title')}
        subtitle={t('datasources.list.subtitle')}
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/datasources/new')}
          >
            {t('datasources.list.add_button')}
          </Button>
        }
      />
      <FilterBar>
        <FilterBar.Search
          placeholder={t('datasources.list.search_placeholder')}
          aria-label={t('datasources.list.search_placeholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <FilterBar.Count>
          {t('datasources.list.count_label', { filtered: filtered.length, total })}
        </FilterBar.Count>
      </FilterBar>
      <PageBody>
        {datasourcesQuery.isLoading && (
          <div className="af-ds-grid" aria-busy="true">
            {[0, 1, 2].map((i) => (
              <div key={i} className="af-ds-card">
                <Skeleton active avatar={{ shape: 'square' }} paragraph={{ rows: 3 }} />
              </div>
            ))}
          </div>
        )}
        {datasourcesQuery.isError && (
          <EmptyState
            icon={<WarningOutlined />}
            title={t('datasources.list.error')}
            action={
              <Button onClick={() => void datasourcesQuery.refetch()}>{t('common.retry')}</Button>
            }
          />
        )}
        {datasourcesQuery.isSuccess && filtered.length === 0 && (
          <EmptyState title={t('datasources.list.empty')} />
        )}
        {filtered.length > 0 && (
          <div className="af-ds-grid">
            {filtered.map((ds) => (
              <DsCard
                key={ds.id}
                ds={ds}
                plan={ds.review_plan_id ? plansById.get(ds.review_plan_id) ?? null : null}
              />
            ))}
          </div>
        )}
      </PageBody>
    </Page>
  );
}

interface CardProps {
  ds: Datasource;
  plan: ReviewPlan | null;
}

function DsCard({ ds, plan }: CardProps) {
  const { t } = useTranslation();
  const planLabel = plan
    ? plan.name
    : ds.review_plan_id
      ? ds.review_plan_id.slice(0, 8)
      : t('datasources.list.stat_plan_unknown');
  return (
    <article className="af-ds-card af-ds-card--link">
      <div className="af-ds-card-head">
        <DatasourceIcon dbType={ds.db_type} size={36} className="af-ds-card-icon" />
        <div className="af-ds-card-titles">
          {/* The name is the card's one link; its ::after stretches the hit area over the card. */}
          <Link to={`/datasources/${ds.id}/settings`} className="af-ds-card-name">
            {ds.name}
          </Link>
          <div className="af-ds-card-host">
            {ds.host}:{ds.port} / {ds.database_name}
          </div>
        </div>
        <StatusTag tone={ds.active ? 'success' : 'neutral'} size="sm" withDot>
          {t(ds.active ? 'datasources.list.status_active' : 'datasources.list.status_inactive')}
        </StatusTag>
      </div>
      <div className="af-ds-card-tags">
        <Pill bg="var(--bg-sunken)" size="sm">
          {dbTypeLabel(t, ds.db_type)}
        </Pill>
        <Pill bg="var(--bg-sunken)" size="sm">
          SSL · {sslModeLabel(t, ds.ssl_mode)}
        </Pill>
        {ds.ai_analysis_enabled && (
          <StatusTag tone="accent" size="sm">
            <ThunderboltOutlined aria-hidden /> AI
          </StatusTag>
        )}
      </div>
      <div className="af-ds-card-plan">
        <div className="af-ds-card-plan-label">{t('datasources.list.stat_plan')}</div>
        {ds.review_plan_id ? (
          <Link
            to={`/admin/review-plans?planId=${encodeURIComponent(ds.review_plan_id)}`}
            className="af-ds-card-plan-link"
          >
            {planLabel}
          </Link>
        ) : (
          <div className="af-ds-card-plan-none">{planLabel}</div>
        )}
      </div>
    </article>
  );
}
