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
          <div className="muted u-text-sm">
            {t('queries.detail.diff_empty')}
          </div>
        )}
        {!isLoading && isError && !is404 && (
          <div className="muted u-text-sm">
            {t('queries.detail.diff_error')}
          </div>
        )}
        {!isLoading && data && (
          <div className="u-flex u-col u-gap-3">
            <div
              className="u-grid u-gap-4 u-cols-3"
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
              className="u-text-xs u-mono"
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
        <div className="muted u-text-sm">
          {t('queries.detail.diff_unavailable')}
        </div>
      </div>
    );
  }
  return (
    <div>
      <Label text={label} />
      <div className="u-flex u-items-center u-gap-2">
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
          <span className="mono muted u-text-xs">
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
      className="muted mono u-text-2xs u-mb-1 u-uppercase u-tracking-wide"
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
      className="af-box"
    >
      <div
        className="u-flex u-items-center u-gap-2 u-border-b" style={{ padding: '10px 14px' }}
      >
        {icon && <span className="u-fg-muted">{icon}</span>}
        <span className="u-fw-600 u-text-sm">{title}</span>
      </div>
      {children}
    </div>
  );
}
