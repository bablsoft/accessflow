import { useState } from 'react';
import { hasPermission } from '@/utils/permissions';
import { Alert, App, Button, Card, Descriptions, Empty, Input, Modal, Skeleton, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { DownloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { DetailCard } from '@/components/common/DetailCard';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import {
  apiRequestKeys,
  approveApiReview,
  cancelApiRequest,
  downloadApiResponse,
  executeApiRequest,
  getApiRequest,
  rejectApiReview,
} from '@/api/apiRequests';
import { useAuthStore } from '@/store/authStore';
import { reviewDecisionTypeLabel, submissionReasonLabel } from '@/utils/enumLabels';
import { fmtDate } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { ApiReviewDecision } from '@/types/api';

export default function ApiRequestDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const [decisionFor, setDecisionFor] = useState<'approve' | 'reject' | null>(null);
  const [comment, setComment] = useState('');

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
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelApiRequest(id),
    onSuccess: () => {
      message.success(t('apiGov.requests.cancelled'));
      refresh();
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const downloadMutation = useMutation({
    mutationFn: () => downloadApiResponse(id),
    onSuccess: ({ blob, filename }) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.requests.downloadFailed'))),
  });

  const decideMutation = useMutation({
    mutationFn: (kind: 'approve' | 'reject') =>
      kind === 'approve'
        ? approveApiReview(id, comment.trim() || undefined)
        : rejectApiReview(id, comment.trim() || undefined),
    onSuccess: (_data, kind) => {
      message.success(kind === 'approve' ? t('apiGov.reviews.approved') : t('apiGov.reviews.rejected'));
      setDecisionFor(null);
      setComment('');
      refresh();
      queryClient.invalidateQueries({ queryKey: ['api-reviews', 'queue'] });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const request = requestQuery.data;

  const canDecide =
    request != null &&
    hasPermission(user, 'API_REQUEST_REVIEW') &&
    request.status === 'PENDING_REVIEW' &&
    request.submitted_by !== user?.id;

  const decisionColumns: TableColumnsType<ApiReviewDecision> = [
    {
      title: t('apiGov.requests.decision'),
      dataIndex: 'decision',
      render: (d: ApiReviewDecision['decision']) => (
        <Tag color={d === 'APPROVED' ? 'green' : d === 'REJECTED' ? 'red' : 'gold'}>
          {reviewDecisionTypeLabel(t, d)}
        </Tag>
      ),
    },
    { title: t('apiGov.requests.stage'), dataIndex: 'stage' },
    {
      title: t('apiGov.requests.comment'),
      dataIndex: 'comment',
      render: (c: string | null) => c ?? '—',
    },
    {
      title: t('apiGov.requests.decidedAt'),
      dataIndex: 'decided_at',
      render: (v: string) => fmtDate(v),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={request?.connector_name ?? t('apiGov.requests.title')}
        subtitle={request ? `${request.verb} ${request.request_path}` : undefined}
        actions={<Button onClick={() => navigate('/api-requests')}>{t('common.back')}</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}>
        {requestQuery.isLoading && <Skeleton active paragraph={{ rows: 8 }} />}
        {!requestQuery.isLoading && !request && <Empty description={t('apiGov.requests.notFound')} />}
        {request && (
          <>
            <Card size="small">
              <Descriptions
                column={{ xs: 1, sm: 1, md: 2 }}
                size="small"
                items={[
                  {
                    key: 'connector',
                    label: t('apiGov.requests.connector'),
                    children: request.connector_name ?? request.connector_id,
                  },
                  {
                    key: 'status',
                    label: t('apiGov.requests.status'),
                    children: <StatusPill status={request.status} />,
                  },
                  {
                    key: 'verb',
                    label: t('apiGov.requests.verb'),
                    children: <span className="mono">{request.verb}</span>,
                  },
                  {
                    key: 'path',
                    label: t('apiGov.requests.path'),
                    children: <span className="mono">{request.request_path}</span>,
                  },
                  {
                    key: 'risk',
                    label: t('apiGov.requests.risk'),
                    children:
                      request.ai_risk_level != null && request.ai_risk_score != null ? (
                        <RiskPill level={request.ai_risk_level} score={request.ai_risk_score} />
                      ) : (
                        '—'
                      ),
                  },
                  {
                    key: 'submitter',
                    label: t('apiGov.requests.submitter'),
                    children: request.submitted_by_email ?? request.submitted_by,
                  },
                  {
                    key: 'reason',
                    label: t('apiGov.requests.submissionReason'),
                    children: submissionReasonLabel(t, request.submission_reason),
                  },
                  {
                    key: 'trace',
                    label: t('apiGov.requests.traceId'),
                    children: <span className="mono" style={{ fontSize: 12 }}>{request.trace_id ?? '—'}</span>,
                  },
                  {
                    key: 'span',
                    label: t('apiGov.requests.spanId'),
                    children: <span className="mono" style={{ fontSize: 12 }}>{request.span_id ?? '—'}</span>,
                  },
                  {
                    key: 'code',
                    label: t('apiGov.requests.responseCode'),
                    children: request.response_status_code ?? '—',
                  },
                  {
                    key: 'duration',
                    label: t('apiGov.requests.duration'),
                    children: request.response_duration_ms != null ? `${request.response_duration_ms} ms` : '—',
                  },
                  {
                    key: 'created',
                    label: t('apiGov.requests.created'),
                    children: fmtDate(request.created_at),
                  },
                  {
                    key: 'scheduled',
                    label: t('apiGov.requests.scheduled'),
                    children: request.scheduled_for ? fmtDate(request.scheduled_for) : '—',
                  },
                ]}
              />
            </Card>

            {(request.status === 'APPROVED' ||
              request.status === 'PENDING_AI' ||
              request.status === 'PENDING_REVIEW') && (
              <div style={{ display: 'flex', gap: 8 }}>
                {request.status === 'APPROVED' && (
                  <Button type="primary" loading={executeMutation.isPending} onClick={() => executeMutation.mutate()}>
                    {t('apiGov.requests.execute')}
                  </Button>
                )}
                {canDecide && (
                  <>
                    <Button type="primary" onClick={() => setDecisionFor('approve')}>
                      {t('apiGov.reviews.approve')}
                    </Button>
                    <Button danger onClick={() => setDecisionFor('reject')}>
                      {t('apiGov.reviews.reject')}
                    </Button>
                  </>
                )}
                {!canDecide && (request.status === 'PENDING_AI' || request.status === 'PENDING_REVIEW') && (
                  <Button danger loading={cancelMutation.isPending} onClick={() => cancelMutation.mutate()}>
                    {t('apiGov.requests.cancel')}
                  </Button>
                )}
              </div>
            )}

            {request.justification && (
              <DetailCard title={t('apiGov.requests.justification')}>
                <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                  {request.justification}
                </div>
              </DetailCard>
            )}

            {request.ai_summary && (
              <DetailCard
                title={t('apiGov.requests.aiSummary')}
                icon={<ThunderboltOutlined style={{ color: 'var(--accent)' }} />}
              >
                <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                  {request.ai_summary}
                </div>
              </DetailCard>
            )}

            {request.error_message && (
              <DetailCard title={t('apiGov.requests.error')}>
                <div style={{ padding: 14 }}>
                  <span style={{ color: 'var(--risk-high)' }}>{request.error_message}</span>
                </div>
              </DetailCard>
            )}

            {request.response_snapshot && (
              <DetailCard
                title={t('apiGov.requests.snapshot')}
                extra={
                  <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center', marginLeft: 'auto' }}>
                    {request.response_truncated && <Tag color="orange">{t('apiGov.requests.truncated')}</Tag>}
                    <Button
                      size="small"
                      icon={<DownloadOutlined />}
                      loading={downloadMutation.isPending}
                      onClick={() => downloadMutation.mutate()}
                    >
                      {t('apiGov.requests.downloadResponse')}
                    </Button>
                  </span>
                }
              >
                <div style={{ padding: 14 }}>
                  {request.response_snapshot_preview_truncated && (
                    <Alert
                      type="info"
                      showIcon
                      style={{ marginBottom: 8 }}
                      message={t('apiGov.requests.previewTruncated')}
                    />
                  )}
                  <pre
                    style={{
                      background: 'var(--bg-sunken)',
                      border: '1px solid var(--border)',
                      borderRadius: 'var(--radius-md)',
                      padding: 12,
                      margin: 0,
                      maxHeight: 360,
                      overflow: 'auto',
                      fontSize: 12,
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                    }}
                  >
                    {request.response_snapshot}
                  </pre>
                </div>
              </DetailCard>
            )}

            <DetailCard title={t('apiGov.requests.decisions')}>
              <div style={{ padding: 14 }}>
                <Table<ApiReviewDecision>
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={request.decisions}
                  columns={decisionColumns}
                  locale={{ emptyText: '—' }}
                />
              </div>
            </DetailCard>
          </>
        )}
      </div>
      <Modal
        open={decisionFor !== null}
        title={decisionFor === 'approve' ? t('apiGov.reviews.approve') : t('apiGov.reviews.reject')}
        onCancel={() => setDecisionFor(null)}
        confirmLoading={decideMutation.isPending}
        onOk={() => decisionFor && decideMutation.mutate(decisionFor)}
      >
        <Input.TextArea
          rows={3}
          placeholder={t('apiGov.reviews.comment')}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </Modal>
    </div>
  );
}
