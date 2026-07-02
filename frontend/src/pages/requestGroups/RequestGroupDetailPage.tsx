import { useState } from 'react';
import {
  App,
  Button,
  Card,
  Descriptions,
  Empty,
  Input,
  Modal,
  Skeleton,
  Space,
} from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { RequestGroupStatusPill } from '@/components/common/RequestGroupStatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { useAuthStore } from '@/store/authStore';
import {
  approveRequestGroup,
  cancelRequestGroup,
  executeRequestGroup,
  getRequestGroup,
  rejectRequestGroup,
  requestGroupKeys,
} from '@/api/requestGroups';
import { fmtDate } from '@/utils/dateFormat';
import { RequestGroupMemberPanel } from './RequestGroupMemberPanel';

export default function RequestGroupDetailPage() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const isReviewer = role === 'REVIEWER' || role === 'ADMIN';

  const [decisionFor, setDecisionFor] = useState<'approve' | 'reject' | null>(null);
  const [comment, setComment] = useState('');

  const groupQuery = useQuery({
    queryKey: requestGroupKeys.detail(id),
    queryFn: () => getRequestGroup(id),
    enabled: !!id,
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: requestGroupKeys.detail(id) });
    queryClient.invalidateQueries({ queryKey: requestGroupKeys.lists() });
  };

  const executeMutation = useMutation({
    mutationFn: () => executeRequestGroup(id),
    onSuccess: () => {
      message.success(t('requestGroups.detail.executeStarted'));
      refresh();
    },
    onError: () => message.error(t('requestGroups.error')),
  });

  const cancelMutation = useMutation({
    mutationFn: () => cancelRequestGroup(id),
    onSuccess: () => {
      message.success(t('requestGroups.detail.cancelled'));
      refresh();
    },
    onError: () => message.error(t('requestGroups.error')),
  });

  const decideMutation = useMutation({
    mutationFn: (kind: 'approve' | 'reject') =>
      kind === 'approve' ? approveRequestGroup(id, comment) : rejectRequestGroup(id, comment),
    onSuccess: (_data, kind) => {
      message.success(
        kind === 'approve' ? t('requestGroups.detail.approved') : t('requestGroups.detail.rejected'),
      );
      setDecisionFor(null);
      setComment('');
      queryClient.invalidateQueries({ queryKey: ['request-groups', 'reviews'] });
      refresh();
    },
    onError: () => message.error(t('requestGroups.error')),
  });

  const group = groupQuery.data;
  const canExecute = group?.status === 'APPROVED';
  const canCancel =
    group?.status === 'PENDING_AI' ||
    group?.status === 'PENDING_REVIEW' ||
    group?.status === 'APPROVED';
  const canReview = isReviewer && group?.status === 'PENDING_REVIEW';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={group?.name ?? t('requestGroups.detail.title')}
        subtitle={group?.description ?? undefined}
        actions={
          <Button onClick={() => navigate('/request-groups')}>{t('common.back')}</Button>
        }
      />
      <div
        style={{ flex: 1, overflow: 'auto', padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}
      >
        {groupQuery.isLoading && <Skeleton active paragraph={{ rows: 8 }} />}
        {!groupQuery.isLoading && !group && <Empty description={t('requestGroups.detail.notFound')} />}
        {group && (
          <>
            <Card size="small">
              <Descriptions
                column={{ xs: 1, sm: 1, md: 2 }}
                size="small"
                items={[
                  {
                    key: 'status',
                    label: t('requestGroups.detail.status'),
                    children: <RequestGroupStatusPill status={group.status} />,
                  },
                  {
                    key: 'risk',
                    label: t('requestGroups.detail.risk'),
                    children:
                      group.ai_risk_level != null ? (
                        <RiskPill level={group.ai_risk_level} score={group.ai_risk_score} />
                      ) : (
                        '—'
                      ),
                  },
                  {
                    key: 'members',
                    label: t('requestGroups.detail.members'),
                    children: group.items.length,
                  },
                  {
                    key: 'continueOnError',
                    label: t('requestGroups.detail.continueOnError'),
                    children: group.continue_on_error
                      ? t('common.yes')
                      : t('common.no'),
                  },
                  {
                    key: 'submitter',
                    label: t('requestGroups.detail.submitter'),
                    children: group.submitted_by_display_name ?? group.submitted_by_user_id,
                  },
                  {
                    key: 'scheduled',
                    label: t('requestGroups.detail.scheduled'),
                    children: group.scheduled_for ? fmtDate(group.scheduled_for) : '—',
                  },
                  {
                    key: 'created',
                    label: t('requestGroups.detail.created'),
                    children: fmtDate(group.created_at),
                  },
                  {
                    key: 'completed',
                    label: t('requestGroups.detail.completed'),
                    children: group.execution_completed_at
                      ? fmtDate(group.execution_completed_at)
                      : '—',
                  },
                ]}
              />
            </Card>

            {group.error_message && (
              <Card size="small" title={t('requestGroups.detail.error')}>
                <span style={{ color: 'var(--risk-high)' }}>{group.error_message}</span>
              </Card>
            )}

            {(canExecute || canCancel || canReview) && (
              <Space>
                {canExecute && (
                  <Button
                    type="primary"
                    loading={executeMutation.isPending}
                    onClick={() => executeMutation.mutate()}
                  >
                    {t('requestGroups.detail.execute')}
                  </Button>
                )}
                {canReview && (
                  <>
                    <Button type="primary" onClick={() => setDecisionFor('approve')}>
                      {t('requestGroups.detail.approve')}
                    </Button>
                    <Button danger onClick={() => setDecisionFor('reject')}>
                      {t('requestGroups.detail.reject')}
                    </Button>
                  </>
                )}
                {canCancel && (
                  <Button danger loading={cancelMutation.isPending} onClick={() => cancelMutation.mutate()}>
                    {t('requestGroups.detail.cancel')}
                  </Button>
                )}
              </Space>
            )}

            <Card size="small" title={t('requestGroups.detail.sequence')}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {[...group.items]
                  .sort((a, b) => a.sequence_order - b.sequence_order)
                  .map((item) => (
                    <RequestGroupMemberPanel key={item.id} item={item} />
                  ))}
              </div>
            </Card>
          </>
        )}
      </div>
      <Modal
        open={decisionFor !== null}
        title={
          decisionFor === 'approve'
            ? t('requestGroups.detail.approve')
            : t('requestGroups.detail.reject')
        }
        onCancel={() => setDecisionFor(null)}
        confirmLoading={decideMutation.isPending}
        onOk={() => decisionFor && decideMutation.mutate(decisionFor)}
      >
        <Input.TextArea
          rows={3}
          placeholder={t('requestGroups.detail.comment')}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </Modal>
    </div>
  );
}
