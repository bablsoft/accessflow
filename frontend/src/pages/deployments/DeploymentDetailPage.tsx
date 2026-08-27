import { Alert, App, Button, Card, Descriptions, Empty, Popconfirm, Progress, Skeleton, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { DetailCard } from '@/components/common/DetailCard';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { ApprovalTimeline } from '@/components/review/ApprovalTimeline';
import {
  cancelDeploymentRequest,
  deploymentKeys,
  getDeploymentGate,
  getDeploymentRequest,
} from '@/api/deploymentRequests';
import { useAuthStore } from '@/store/authStore';
import {
  pipelineProviderLabel,
  reviewDecisionTypeLabel,
  submissionReasonLabel,
} from '@/utils/enumLabels';
import { fmtDate } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { buildDeploymentTimelineStages } from './buildDeploymentTimelineStages';
import type { DeploymentDecision, DeploymentRequest } from '@/types/api';

/**
 * Mirrors DefaultDeploymentRequestService.cancel: PENDING_REVIEW always, and APPROVED only while
 * a deferred run is still in the future — once it has passed the deploy may already be releasable.
 */
function isCancellable(request: DeploymentRequest): boolean {
  if (request.status === 'PENDING_REVIEW') return true;
  if (request.status !== 'APPROVED' || request.scheduled_for == null) return false;
  return new Date(request.scheduled_for).getTime() > Date.now();
}

export default function DeploymentDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);

  const requestQuery = useQuery({
    queryKey: deploymentKeys.detail(id),
    queryFn: () => getDeploymentRequest(id),
    enabled: !!id,
  });
  const request = requestQuery.data;

  // Freeze state lives only on the gate; ask it just while the request is APPROVED.
  // A 404 (visibility) simply hides the banner — retry would never succeed.
  const gateQuery = useQuery({
    queryKey: deploymentKeys.gate(id),
    queryFn: () => getDeploymentGate(id),
    enabled: !!id && request?.status === 'APPROVED',
    retry: false,
  });
  const gate = gateQuery.data;

  const cancelMutation = useMutation({
    mutationFn: () => cancelDeploymentRequest(id),
    onSuccess: () => {
      message.success(t('deploygov.detail.cancelled'));
      void queryClient.invalidateQueries({ queryKey: deploymentKeys.detail(id) });
      void queryClient.invalidateQueries({ queryKey: deploymentKeys.lists() });
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('deploygov.error'))),
  });

  const canCancel = request != null && request.submitted_by === user?.id && isCancellable(request);

  const approvalsGranted =
    request?.decisions.filter((d) => d.decision === 'APPROVED').length ?? 0;

  const decisionColumns: TableColumnsType<DeploymentDecision> = [
    {
      title: t('deploygov.detail.reviewer'),
      dataIndex: 'reviewer_id',
      render: (v: string) => (
        <span className="mono" style={{ fontSize: 12 }}>
          {v}
        </span>
      ),
    },
    {
      title: t('deploygov.detail.decision'),
      dataIndex: 'decision',
      render: (d: DeploymentDecision['decision']) => (
        <Tag color={d === 'APPROVED' ? 'green' : d === 'REJECTED' ? 'red' : 'gold'}>
          {reviewDecisionTypeLabel(t, d)}
        </Tag>
      ),
    },
    { title: t('deploygov.detail.stage'), dataIndex: 'stage' },
    {
      title: t('deploygov.detail.comment'),
      dataIndex: 'comment',
      render: (c: string | null) => c ?? '—',
    },
    {
      title: t('deploygov.detail.decidedAt'),
      dataIndex: 'decided_at',
      render: (v: string) => fmtDate(v),
    },
  ];

  const metadataEntries = Object.keys(request?.metadata ?? {});

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={request?.pipeline_name ?? t('deploygov.detail.title')}
        subtitle={
          request ? `${request.version} → ${request.environment_name ?? request.environment_id}` : undefined
        }
        actions={<Button onClick={() => navigate('/deployments')}>{t('common.back')}</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}>
        {requestQuery.isLoading && <Skeleton active paragraph={{ rows: 8 }} />}
        {!requestQuery.isLoading && !request && (
          <Empty description={t('deploygov.deployments.notFound')} />
        )}
        {request && (
          <>
            {request.status === 'APPROVED' && gate && (
              <Alert
                showIcon
                type={gate.frozen ? 'warning' : gate.releasable ? 'success' : 'info'}
                title={
                  gate.frozen
                    ? gate.freeze_reason
                      ? t('deploygov.detail.bannerFrozenReason', { reason: gate.freeze_reason })
                      : t('deploygov.detail.bannerFrozen')
                    : gate.releasable
                    ? t('deploygov.detail.bannerReleasable')
                    : gate.scheduled_for
                    ? t('deploygov.detail.bannerScheduled', { when: fmtDate(gate.scheduled_for) })
                    : t('deploygov.detail.bannerFrozen')
                }
              />
            )}

            <Card size="small">
              <Descriptions
                column={{ xs: 1, sm: 1, md: 2 }}
                size="small"
                items={[
                  {
                    key: 'pipeline',
                    label: t('deploygov.detail.pipeline'),
                    children: request.pipeline_name ?? request.pipeline_id,
                  },
                  {
                    key: 'status',
                    label: t('deploygov.detail.status'),
                    children: <StatusPill status={request.status} />,
                  },
                  {
                    key: 'environment',
                    label: t('deploygov.detail.environment'),
                    children: request.environment_name ?? request.environment_id,
                  },
                  {
                    key: 'provider',
                    label: t('deploygov.detail.provider'),
                    children: request.provider ? pipelineProviderLabel(t, request.provider) : '—',
                  },
                  {
                    key: 'version',
                    label: t('deploygov.detail.version'),
                    children: <span className="mono">{request.version}</span>,
                  },
                  {
                    key: 'commit',
                    label: t('deploygov.detail.commit'),
                    children: request.commit_sha ? (
                      <span className="mono" style={{ fontSize: 12 }}>
                        {request.commit_sha}
                      </span>
                    ) : (
                      '—'
                    ),
                  },
                  {
                    key: 'artifact',
                    label: t('deploygov.detail.artifact'),
                    children: request.artifact_ref ? (
                      <span className="mono" style={{ fontSize: 12 }}>
                        {request.artifact_ref}
                      </span>
                    ) : (
                      '—'
                    ),
                  },
                  {
                    key: 'run',
                    label: t('deploygov.detail.runUrl'),
                    children: request.run_url ? (
                      <a href={request.run_url} target="_blank" rel="noreferrer">
                        {t('deploygov.detail.openRun')}
                      </a>
                    ) : (
                      '—'
                    ),
                  },
                  {
                    key: 'externalRun',
                    label: t('deploygov.detail.externalRunId'),
                    children: request.external_run_id ? (
                      <span className="mono" style={{ fontSize: 12 }}>
                        {request.external_run_id}
                      </span>
                    ) : (
                      '—'
                    ),
                  },
                  {
                    key: 'submitter',
                    label: t('deploygov.detail.submitter'),
                    children: request.submitted_by_email ?? request.submitted_by,
                  },
                  {
                    key: 'reason',
                    label: t('deploygov.detail.submissionReason'),
                    children: submissionReasonLabel(t, request.submission_reason),
                  },
                  {
                    key: 'scheduled',
                    label: t('deploygov.detail.scheduled'),
                    children: request.scheduled_for ? fmtDate(request.scheduled_for) : '—',
                  },
                  {
                    key: 'created',
                    label: t('deploygov.detail.created'),
                    children: fmtDate(request.created_at),
                  },
                ]}
              />
            </Card>

            {canCancel && (
              <div>
                <Popconfirm
                  title={t('deploygov.detail.cancelConfirmTitle')}
                  description={t('deploygov.detail.cancelConfirmBody')}
                  onConfirm={() => cancelMutation.mutate()}
                  okText={t('deploygov.detail.cancel')}
                  cancelText={t('common.cancel')}
                >
                  <Button danger loading={cancelMutation.isPending}>
                    {t('deploygov.detail.cancel')}
                  </Button>
                </Popconfirm>
              </div>
            )}

            <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap' }}>
              <div style={{ flex: 2, minWidth: 320, display: 'flex', flexDirection: 'column', gap: 16 }}>
                {request.justification && (
                  <DetailCard title={t('deploygov.detail.justification')}>
                    <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                      {request.justification}
                    </div>
                  </DetailCard>
                )}

                {(request.ai_risk_level != null || request.ai_summary) && (
                  <DetailCard
                    title={t('deploygov.detail.aiSummary')}
                    icon={<ThunderboltOutlined style={{ color: 'var(--accent)' }} />}
                    extra={
                      request.ai_risk_level != null && request.ai_risk_score != null ? (
                        <RiskPill level={request.ai_risk_level} score={request.ai_risk_score} />
                      ) : undefined
                    }
                  >
                    <div style={{ padding: 14, fontSize: 13, lineHeight: 1.55 }}>
                      {request.ai_summary ?? '—'}
                    </div>
                  </DetailCard>
                )}

                <DetailCard title={t('deploygov.detail.approvals')}>
                  <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 12 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <Progress
                        type="circle"
                        size={44}
                        percent={
                          request.required_approvals > 0
                            ? Math.min(100, (approvalsGranted / request.required_approvals) * 100)
                            : 100
                        }
                        format={() => `${approvalsGranted}/${request.required_approvals}`}
                      />
                      <span style={{ fontSize: 13 }}>
                        {t('deploygov.detail.approvalsProgress', {
                          granted: approvalsGranted,
                          required: request.required_approvals,
                        })}
                      </span>
                    </div>
                    <Table<DeploymentDecision>
                      rowKey="id"
                      size="small"
                      pagination={false}
                      dataSource={request.decisions}
                      columns={decisionColumns}
                      locale={{ emptyText: '—' }}
                    />
                  </div>
                </DetailCard>

                {metadataEntries.length > 0 && (
                  <DetailCard title={t('deploygov.detail.metadataJson')}>
                    <div style={{ padding: 14 }}>
                      <pre
                        style={{
                          background: 'var(--bg-sunken)',
                          border: '1px solid var(--border)',
                          borderRadius: 'var(--radius-md)',
                          padding: 12,
                          margin: 0,
                          maxHeight: 280,
                          overflow: 'auto',
                          fontSize: 12,
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word',
                        }}
                      >
                        {JSON.stringify(request.metadata, null, 2)}
                      </pre>
                    </div>
                  </DetailCard>
                )}
              </div>
              <div style={{ flex: 1, minWidth: 260 }}>
                <DetailCard title={t('deploygov.detail.timelineTitle')}>
                  <div style={{ padding: 14 }}>
                    <ApprovalTimeline stages={buildDeploymentTimelineStages(request, t)} />
                  </div>
                </DetailCard>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
