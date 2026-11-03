import { useMemo, useState } from 'react';
import { Alert, Input, Skeleton, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { DiffOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { isAxiosError } from 'axios';
import { Link } from 'react-router-dom';
import { json } from '@codemirror/lang-json';
import type { Extension } from '@codemirror/state';
import { DetailCard } from '@/components/common/DetailCard';
import { DeltaBadge } from '@/components/common/DeltaBadge';
import { MergeDiffView } from '@/components/editor/MergeDiffView';
import { apiRequestKeys, getApiRequest, getApiResponseDiff } from '@/api/apiRequests';
import { apiResponseDiffKindLabel } from '@/utils/enumLabels';
import { apiResponseDiffKindColor } from '@/utils/statusColors';
import type { ApiRequest, ApiResponseDiffEntry, ApiResponseDiffFormat } from '@/types/api';

const PREVIOUS = 'previous';
const JSON_EXTENSIONS: Extension[] = [json()];
const PLAIN_EXTENSIONS: Extension[] = [];

interface Props {
  request: ApiRequest;
}

/** Pretty-prints a JSON preview so the side-by-side pane lines up; a cut-off preview stays as-is. */
function formatPreview(body: string, format: ApiResponseDiffFormat): string {
  if (format !== 'JSON') return body;
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}

export function ApiResponseDiffCard({ request }: Props) {
  const { t } = useTranslation();
  const [against, setAgainst] = useState(PREVIOUS);

  const diffQuery = useQuery({
    queryKey: apiRequestKeys.diff(request.id, against),
    queryFn: () => getApiResponseDiff(request.id, against),
    enabled: request.status === 'EXECUTED' && request.response_snapshot != null,
    retry: false,
  });
  const diff = diffQuery.data;

  const previousQuery = useQuery({
    queryKey: apiRequestKeys.detail(diff?.previous_id ?? ''),
    queryFn: () => getApiRequest(diff?.previous_id ?? ''),
    enabled: diff != null,
  });

  const errorBody = isAxiosError<{ error?: string; detail?: string }>(diffQuery.error)
    ? diffQuery.error.response
    : undefined;
  const is404 = errorBody?.status === 404;
  const errorCode = errorBody?.data?.error;
  const errorDetail = errorBody?.data?.detail;

  const columns: TableColumnsType<ApiResponseDiffEntry> = [
    {
      title: t('apiGov.requests.diffPath'),
      dataIndex: 'path',
      render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
    },
    {
      title: t('apiGov.requests.diffKind'),
      dataIndex: 'kind',
      width: 120,
      render: (k: ApiResponseDiffEntry['kind']) => {
        const c = apiResponseDiffKindColor(k);
        return (
          <Tag style={{ color: c.fg, background: c.bg, borderColor: c.border }}>
            {apiResponseDiffKindLabel(t, k)}
          </Tag>
        );
      },
    },
    {
      title: t('apiGov.requests.diffBefore'),
      dataIndex: 'before',
      render: (v: string | null | undefined) => <ValueCell value={v} />,
    },
    {
      title: t('apiGov.requests.diffAfter'),
      dataIndex: 'after',
      render: (v: string | null | undefined) => <ValueCell value={v} />,
    },
  ];

  const previousSnapshot = previousQuery.data?.response_snapshot;
  const panes = useMemo(() => {
    if (!diff || previousSnapshot == null || request.response_snapshot == null) return null;
    return {
      old: formatPreview(previousSnapshot, diff.format),
      current: formatPreview(request.response_snapshot, diff.format),
      extensions: diff.format === 'JSON' ? JSON_EXTENSIONS : PLAIN_EXTENSIONS,
    };
  }, [diff, previousSnapshot, request.response_snapshot]);

  return (
    <DetailCard title={t('apiGov.requests.diffTitle')} icon={<DiffOutlined />}>
      <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 12 }}>
        <Input.Search
          allowClear
          size="small"
          style={{ maxWidth: 420 }}
          aria-label={t('apiGov.requests.diffAgainstLabel')}
          placeholder={t('apiGov.requests.diffAgainstPlaceholder')}
          enterButton={t('apiGov.requests.diffAgainstSubmit')}
          onSearch={(value) => setAgainst(value.trim() || PREVIOUS)}
        />
        {diffQuery.isLoading && <Skeleton active title={false} paragraph={{ rows: 2 }} />}
        {!diffQuery.isLoading && is404 && (
          <div className="muted" style={{ fontSize: 13 }}>
            {t('apiGov.requests.diffEmpty')}
          </div>
        )}
        {!diffQuery.isLoading && diffQuery.isError && !is404 && (
          <Alert
            type={errorBody?.status === 422 ? 'info' : 'warning'}
            showIcon
            title={
              errorCode === 'API_RESPONSE_DIFF_UNSUPPORTED'
                ? t('apiGov.requests.diffUnsupported')
                : errorCode === 'API_RESPONSE_DIFF_INCOMPATIBLE'
                  ? t('apiGov.requests.diffIncompatible')
                  : t('apiGov.requests.diffError')
            }
            description={errorDetail}
          />
        )}
        {!diffQuery.isLoading && diff && (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
              <div>
                <Label text={t('apiGov.requests.diffStatusCode')} />
                <span className="mono" style={{ fontSize: 13 }}>
                  {`${diff.status_code_previous ?? '—'} → ${diff.status_code_current ?? '—'}`}
                </span>
              </div>
              <DeltaRow
                label={t('apiGov.requests.diffDuration')}
                delta={diff.duration_ms_delta}
                current={request.response_duration_ms}
                unit="ms"
              />
              <DeltaRow
                label={t('apiGov.requests.diffBytes')}
                delta={diff.bytes_delta}
                current={request.response_bytes}
              />
            </div>
            <div style={{ fontSize: 13 }}>
              {t('apiGov.requests.diffCounts', {
                added: diff.added_count,
                removed: diff.removed_count,
                changed: diff.changed_count,
              })}
            </div>
            {diff.truncated && <Alert type="warning" showIcon title={t('apiGov.requests.diffTruncated')} />}
            {diff.either_response_truncated && (
              <Alert type="warning" showIcon title={t('apiGov.requests.diffEitherTruncated')} />
            )}
            <Table<ApiResponseDiffEntry>
              rowKey={(e) => `${e.kind}:${e.path}`}
              size="small"
              pagination={diff.entries.length > 20 ? { pageSize: 20, size: 'small' } : false}
              dataSource={diff.entries}
              columns={columns}
              locale={{ emptyText: t('apiGov.requests.diffNoChanges') }}
            />
            <div className="muted" style={{ fontSize: 12 }}>
              {t('apiGov.requests.diffMaskingNote')}
            </div>
            {panes && (
              <div>
                <Label text={t('apiGov.requests.diffPreviewLabel')} />
                <MergeDiffView
                  oldValue={panes.old}
                  newValue={panes.current}
                  extensions={panes.extensions}
                  height={320}
                  oldLabel={t('apiGov.requests.diffPreviousPane')}
                  newLabel={t('apiGov.requests.diffCurrentPane')}
                  testId="api-response-diff-view"
                />
              </div>
            )}
            <Link
              to={`/api-requests/${diff.previous_id}`}
              style={{ fontSize: 12, fontFamily: 'var(--font-mono)' }}
            >
              {t('apiGov.requests.diffPreviousLink')}
            </Link>
          </>
        )}
      </div>
    </DetailCard>
  );
}

function ValueCell({ value }: { value: string | null | undefined }) {
  if (value == null) return <span className="muted">—</span>;
  return (
    <span className="mono" style={{ fontSize: 12, wordBreak: 'break-word' }}>
      {value}
    </span>
  );
}

function DeltaRow({
  label,
  delta,
  current,
  unit,
}: {
  label: string;
  delta: number | null | undefined;
  current: number | null;
  unit?: string;
}) {
  const { t } = useTranslation();
  return (
    <div>
      <Label text={label} />
      {delta == null ? (
        <div className="muted" style={{ fontSize: 13 }}>
          {t('apiGov.requests.diffUnavailable')}
        </div>
      ) : (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <DeltaBadge
            delta={delta}
            previous={current != null ? current - delta : null}
            unit={unit}
            positiveIsGood={false}
          />
          {current != null && (
            <span className="mono muted" style={{ fontSize: 12 }}>
              {current}
              {unit ? ` ${unit}` : ''}
            </span>
          )}
        </div>
      )}
    </div>
  );
}

function Label({ text }: { text: string }) {
  return (
    <div
      className="muted mono"
      style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.04em', marginBottom: 4 }}
    >
      {text}
    </div>
  );
}
