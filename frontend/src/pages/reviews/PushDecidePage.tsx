import { useState } from 'react';
import { App, Alert, Button, Card, Form, Input, Skeleton, Space } from 'antd';
import { CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { SqlBlock } from '@/components/common/SqlBlock';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { RiskPill } from '@/components/common/RiskPill';
import { EmptyState } from '@/components/common/EmptyState';
import { useAuthStore } from '@/store/authStore';
import { getQuery, queryKeys } from '@/api/queries';
import { decideFromPush, reviewKeys, type PushDecision } from '@/api/reviews';
import { requestStepUp } from '@/api/stepup';
import { reviewErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';

/**
 * Focused, mobile-friendly landing for the one-tap push approve/reject flow (AF-444). Reached via
 * the service worker's notificationclick deep link (`?action=approve|reject`). The decision only
 * commits after the reviewer re-verifies (password, or TOTP when 2FA is enrolled) — a single tap
 * never approves a query. The self-approval guard is enforced server-side regardless.
 */
export default function PushDecidePage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const initialAction = searchParams.get('action');
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const [credential, setCredential] = useState('');

  const usesTotp = user?.totp_enabled ?? false;

  const { data: query, isLoading, isError } = useQuery({
    queryKey: queryKeys.detail(id ?? ''),
    queryFn: () => getQuery(id ?? ''),
    enabled: Boolean(id),
  });

  const isOwnQuery = Boolean(query && user && query.submitted_by.id === user.id);
  const notPending = Boolean(query && query.status !== 'PENDING_REVIEW');

  const decide = useMutation({
    mutationFn: async (decision: PushDecision) => {
      const step = await requestStepUp(
        usesTotp ? { totpCode: credential } : { password: credential },
      );
      return decideFromPush(id ?? '', { decision, stepUpToken: step.step_up_token });
    },
    onSuccess: (_result, decision) => {
      void queryClient.invalidateQueries({ queryKey: reviewKeys.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.detail(id ?? '') });
      message.success(
        decision === 'APPROVE' ? t('reviews.decide.approved') : t('reviews.decide.rejected'),
      );
      navigate('/reviews');
    },
    onError: (err) => showApiError(message, err, reviewErrorMessage),
  });

  const canDecide = credential.trim().length > 0 && !isOwnQuery && !notPending;

  return (
    <div style={{ maxWidth: 520, margin: '0 auto', padding: 16 }}>
      <PageHeader title={t('reviews.decide.title')} subtitle={t('reviews.decide.subtitle')} />
      {isLoading && <Skeleton active paragraph={{ rows: 6 }} />}
      {isError && (
        <EmptyState title={t('reviews.decide.not_found')} />
      )}
      {query && (
        <Card>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space wrap>
              <QueryTypePill type={query.query_type} />
              {query.ai_analysis && (
                <RiskPill level={query.ai_analysis.risk_level} score={query.ai_analysis.risk_score} />
              )}
            </Space>
            <div className="muted">
              {t('reviews.decide.context', {
                datasource: query.datasource.name,
                submitter: query.submitted_by.display_name,
              })}
            </div>
            <SqlBlock sql={query.sql_text} style={{ maxHeight: 220 }} />

            {isOwnQuery && (
              <Alert type="warning" showIcon message={t('reviews.decide.own_query')} />
            )}
            {notPending && !isOwnQuery && (
              <Alert type="info" showIcon message={t('reviews.decide.not_pending')} />
            )}

            {!isOwnQuery && !notPending && (
              <Form layout="vertical" onSubmitCapture={(e) => e.preventDefault()}>
                <Form.Item
                  label={usesTotp ? t('reviews.decide.totp_label') : t('reviews.decide.password_label')}
                  required
                  help={t('reviews.decide.step_up_help')}
                >
                  <Input.Password
                    value={credential}
                    onChange={(e) => setCredential(e.target.value)}
                    autoComplete={usesTotp ? 'one-time-code' : 'current-password'}
                    inputMode={usesTotp ? 'numeric' : 'text'}
                    aria-label={
                      usesTotp ? t('reviews.decide.totp_label') : t('reviews.decide.password_label')
                    }
                  />
                </Form.Item>
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Button
                    danger
                    icon={<CloseOutlined />}
                    loading={decide.isPending && decide.variables === 'REJECT'}
                    disabled={!canDecide || decide.isPending}
                    onClick={() => decide.mutate('REJECT')}
                  >
                    {t('reviews.decide.reject')}
                  </Button>
                  <Button
                    type="primary"
                    icon={<CheckOutlined />}
                    loading={decide.isPending && decide.variables === 'APPROVE'}
                    disabled={!canDecide || decide.isPending}
                    onClick={() => decide.mutate('APPROVE')}
                    className={initialAction === 'approve' ? 'af-decide-suggested' : undefined}
                  >
                    {t('reviews.decide.approve')}
                  </Button>
                </Space>
              </Form>
            )}
          </Space>
        </Card>
      )}
    </div>
  );
}
