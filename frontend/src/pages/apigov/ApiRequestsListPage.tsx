import { App, Button, Table, Tag } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  apiRequestKeys,
  cancelApiRequest,
  executeApiRequest,
  listApiRequests,
} from '@/api/apiRequests';
import { queryStatusLabel, riskLevelLabel } from '@/utils/enumLabels';
import type { ApiRequest, QueryStatus } from '@/types/api';

const STATUS_COLOR: Record<QueryStatus, string> = {
  PENDING_AI: 'blue',
  PENDING_REVIEW: 'gold',
  APPROVED: 'cyan',
  EXECUTED: 'green',
  REJECTED: 'red',
  TIMED_OUT: 'volcano',
  FAILED: 'red',
  CANCELLED: 'default',
};

export default function ApiRequestsListPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const requestsQuery = useQuery({
    queryKey: apiRequestKeys.list({ size: 100 }),
    queryFn: () => listApiRequests({ size: 100 }),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: apiRequestKeys.lists() });

  const cancelMutation = useMutation({
    mutationFn: cancelApiRequest,
    onSuccess: () => {
      message.success(t('apiGov.requests.cancelled'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const executeMutation = useMutation({
    mutationFn: executeApiRequest,
    onSuccess: () => {
      message.success(t('apiGov.requests.executed'));
      invalidate();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const columns = [
    {
      title: t('apiGov.requests.connector'),
      dataIndex: 'connector_name',
      render: (name: string | null, row: ApiRequest) => (
        <a onClick={() => navigate(`/api-requests/${row.id}`)}>{name ?? row.connector_id.slice(0, 8)}</a>
      ),
    },
    { title: t('apiGov.requests.verb'), dataIndex: 'verb' },
    { title: t('apiGov.requests.path'), dataIndex: 'request_path', ellipsis: true },
    {
      title: t('apiGov.requests.status'),
      dataIndex: 'status',
      render: (s: QueryStatus) => <Tag color={STATUS_COLOR[s]}>{queryStatusLabel(t, s)}</Tag>,
    },
    {
      title: t('apiGov.requests.risk'),
      dataIndex: 'ai_risk_level',
      render: (_: unknown, row: ApiRequest) =>
        row.ai_risk_level ? (
          <Tag>{riskLevelLabel(t, row.ai_risk_level)} · {row.ai_risk_score ?? '—'}</Tag>
        ) : (
          <span style={{ color: 'var(--fg-faint)' }}>—</span>
        ),
    },
    {
      title: t('apiGov.requests.view'),
      key: 'actions',
      render: (_: unknown, row: ApiRequest) => (
        <span style={{ display: 'flex', gap: 8 }}>
          <Button size="small" onClick={() => navigate(`/api-requests/${row.id}`)}>
            {t('apiGov.requests.view')}
          </Button>
          {row.status === 'APPROVED' && (
            <Button
              size="small"
              type="primary"
              loading={executeMutation.isPending && executeMutation.variables === row.id}
              onClick={() => executeMutation.mutate(row.id)}
            >
              {t('apiGov.requests.execute')}
            </Button>
          )}
          {(row.status === 'PENDING_AI' || row.status === 'PENDING_REVIEW') && (
            <Button
              size="small"
              danger
              loading={cancelMutation.isPending && cancelMutation.variables === row.id}
              onClick={() => cancelMutation.mutate(row.id)}
            >
              {t('apiGov.requests.cancel')}
            </Button>
          )}
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('apiGov.requests.title')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        <Table<ApiRequest>
          rowKey="id"
          loading={requestsQuery.isLoading}
          dataSource={requestsQuery.data?.content ?? []}
          columns={columns}
          pagination={false}
          locale={{ emptyText: t('apiGov.requests.empty') }}
        />
      </div>
    </div>
  );
}
