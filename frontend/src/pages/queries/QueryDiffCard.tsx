import { Skeleton } from 'antd';
import { DiffOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { isAxiosError } from 'axios';
import { Link } from 'react-router-dom';
import { DeltaBadge } from '@/components/common/DeltaBadge';
import { getQueryDiff, queryKeys } from '@/api/queries';
import type { QueryDetail } from '@/types/api';

interface Props {
  query: QueryDetail;
}

export function QueryDiffCard({ query }: Props) {
  const { t } = useTranslation();
  const { data, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.diff(query.id),
    queryFn: () => getQueryDiff(query.id),
    enabled: query.status === 'EXECUTED',
    retry: false,
  });

  const is404 = isAxiosError(error) && error.response?.status === 404;

  return (
    <Card title={t('queries.detail.card_diff')} icon={<DiffOutlined />}>
      <div style={{ padding: 14 }}>
        {isLoading && <Skeleton active title={false} paragraph={{ rows: 2 }} />}
        {!isLoading && is404 && (
          <div className="muted" style={{ fontSize: 13 }}>
            {t('queries.detail.diff_empty')}
          </div>
        )}
        {!isLoading && isError && !is404 && (
          <div className="muted" style={{ fontSize: 13 }}>
            {t('queries.detail.diff_error')}
          </div>
        )}
        {!isLoading && data && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(3, 1fr)',
                gap: 16,
              }}
            >
              <DeltaRow
                label={t('queries.detail.diff_rows')}
                delta={data.rows_affected_delta}
                previous={query.rows_affected}
                current={query.rows_affected}
              />
              <DeltaRow
                label={t('queries.detail.diff_duration')}
                delta={data.execution_ms_delta}
                previous={query.duration_ms}
                current={query.duration_ms}
                unit="ms"
              />
              <DeltaRow
                label={t('queries.detail.diff_row_count')}
                delta={data.row_count_delta}
                previous={null}
                current={null}
              />
            </div>
            <Link
              to={`/queries/${data.previous_run_id}`}
              style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}
            >
              {t('queries.detail.diff_previous_link')}
            </Link>
          </div>
        )}
      </div>
    </Card>
  );
}

function DeltaRow({
  label,
  delta,
  previous,
  current,
  unit,
}: {
  label: string;
  delta: number | null;
  previous: number | null;
  current: number | null;
  unit?: string;
}) {
  const { t } = useTranslation();
  if (delta == null) {
    return (
      <div>
        <Label text={label} />
        <div className="muted" style={{ fontSize: 13 }}>
          {t('queries.detail.diff_unavailable')}
        </div>
      </div>
    );
  }
  return (
    <div>
      <Label text={label} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <DeltaBadge
          delta={delta}
          previous={previous}
          unit={unit}
          // For "rows" / "row_count" more rows is neither inherently good nor bad;
          // for duration more ms is worse. positiveIsGood=false keeps red=worse for
          // duration; the other rows fall under the same muted convention.
          positiveIsGood={false}
        />
        {current != null && (
          <span className="mono muted" style={{ fontSize: 12 }}>
            {current}
            {unit ? ` ${unit}` : ''}
          </span>
        )}
      </div>
    </div>
  );
}

function Label({ text }: { text: string }) {
  return (
    <div
      className="muted mono"
      style={{
        fontSize: 10,
        textTransform: 'uppercase',
        letterSpacing: '0.04em',
        marginBottom: 4,
      }}
    >
      {text}
    </div>
  );
}

function Card({
  title,
  icon,
  children,
}: {
  title: string;
  icon?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      <div
        style={{
          padding: '10px 14px',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        {icon && <span style={{ color: 'var(--fg-muted)' }}>{icon}</span>}
        <span style={{ fontWeight: 600, fontSize: 13 }}>{title}</span>
      </div>
      {children}
    </div>
  );
}
