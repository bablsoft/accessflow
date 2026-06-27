import { App, Button, Descriptions, Table, Tag } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import {
  apiRequestKeys,
  cancelApiRequest,
  executeApiRequest,
  getApiRequest,
} from '@/api/apiRequests';
import { queryStatusLabel, riskLevelLabel } from '@/utils/enumLabels';
import type { ApiReviewDecision } from '@/types/api';

export default function ApiRequestDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const requestQuery = useQuery({
    queryKey: apiRequestKeys.detail(id),
    queryFn: () => getApiRequest(id),
    enabled: !!id,
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: apiRequestKeys.detail(id) });
    queryClient.invalidateQueries({ queryKey: apiRequestKeys.lists() });
  };

  const executeMutation = useMutation({
    mutationFn: () => executeApiRequest(id),
    onSuccess: () => {
      message.success(t('apiGov.requests.executed'));
      refresh();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelApiRequest(id),
    onSuccess: () => {
      message.success(t('apiGov.requests.cancelled'));
      refresh();
    },
    onError: () => message.error(t('apiGov.error')),
  });

  const request = requestQuery.data;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('apiGov.requests.title')}
        actions={
          <Button onClick={() => navigate('/api-requests')}>{t('common.back')}</Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24, display: 'flex', flexDirection: 'column', gap: 20 }}>
        {request && (
          <>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label={t('apiGov.requests.connector')}>
                {request.connector_name ?? request.connector_id}
              </Descriptions.Item>
              <Descriptions.Item label={t('apiGov.requests.status')}>
                <Tag>{queryStatusLabel(t, request.status)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('apiGov.requests.verb')}>{request.verb}</Descriptions.Item>
              <Descriptions.Item label={t('apiGov.requests.path')}>{request.request_path}</Descriptions.Item>
              <Descriptions.Item label={t('apiGov.requests.risk')}>
                {request.ai_risk_level
                  ? `${riskLevelLabel(t, request.ai_risk_level)} · ${request.ai_risk_score ?? '—'}`
                  : '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('apiGov.requests.responseCode')}>
                {request.response_status_code ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('apiGov.requests.justification')} span={2}>
                {request.justification ?? '—'}
              </Descriptions.Item>
              {request.ai_summary && (
                <Descriptions.Item label="AI" span={2}>
                  {request.ai_summary}
                </Descriptions.Item>
              )}
              {request.error_message && (
                <Descriptions.Item label="Error" span={2}>
                  <span style={{ color: 'var(--risk-high)' }}>{request.error_message}</span>
                </Descriptions.Item>
              )}
            </Descriptions>

            <div style={{ display: 'flex', gap: 8 }}>
              {request.status === 'APPROVED' && (
                <Button type="primary" loading={executeMutation.isPending} onClick={() => executeMutation.mutate()}>
                  {t('apiGov.requests.execute')}
                </Button>
              )}
              {(request.status === 'PENDING_AI' || request.status === 'PENDING_REVIEW') && (
                <Button danger loading={cancelMutation.isPending} onClick={() => cancelMutation.mutate()}>
                  {t('apiGov.requests.cancel')}
                </Button>
              )}
            </div>

            {request.response_snapshot && (
              <div>
                <h4>{t('apiGov.requests.snapshot')}</h4>
                <pre
                  style={{
                    background: 'var(--bg-sunken)',
                    border: '1px solid var(--border)',
                    borderRadius: 'var(--radius-md)',
                    padding: 12,
                    maxHeight: 320,
                    overflow: 'auto',
                    fontSize: 12,
                  }}
                >
                  {request.response_snapshot}
                </pre>
              </div>
            )}

            <div>
              <h4>{t('apiGov.requests.decisions')}</h4>
              <Table<ApiReviewDecision>
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={request.decisions}
                columns={[
                  { title: 'Decision', dataIndex: 'decision', render: (d: string) => <Tag>{d}</Tag> },
                  { title: 'Stage', dataIndex: 'stage' },
                  { title: 'Comment', dataIndex: 'comment' },
                  { title: 'At', dataIndex: 'decided_at' },
                ]}
                locale={{ emptyText: '—' }}
              />
            </div>
          </>
        )}
      </div>
    </div>
  );
}
