import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Button, Progress } from 'antd';
import {
  CheckCircleFilled,
  RightOutlined,
  DownOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import { getSetupProgress, setupProgressKeys } from '@/api/admin';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore, type SetupStepId } from '@/store/preferencesStore';
import type { SetupProgress } from '@/types/api';
import './setup-progress-widget.css';

type StepStatusKey =
  | 'admin.setup_progress.step_status_done'
  | 'admin.setup_progress.step_status_skipped';

type StepLabelKey =
  | 'admin.setup_progress.step_datasources_label'
  | 'admin.setup_progress.step_review_plans_label'
  | 'admin.setup_progress.step_ai_provider_label';

interface SetupStep {
  id: SetupStepId;
  configured: boolean;
  labelKey: StepLabelKey;
  to: string;
}

// Order is deliberate — review plans first because every datasource references one. AI
// provider is last because it is also the most likely to be skipped on a fresh install.
function buildSteps(data: SetupProgress): SetupStep[] {
  return [
    {
      id: 'review_plans',
      configured: data.review_plans_configured,
      labelKey: 'admin.setup_progress.step_review_plans_label',
      to: '/admin/review-plans',
    },
    {
      id: 'datasources',
      configured: data.datasources_configured,
      labelKey: 'admin.setup_progress.step_datasources_label',
      to: '/datasources/new',
    },
    {
      id: 'ai_provider',
      configured: data.ai_provider_configured,
      labelKey: 'admin.setup_progress.step_ai_provider_label',
      to: '/admin/ai-config',
    },
  ];
}

export function SetupProgressWidget() {
  const { t } = useTranslation();
  const user = useAuthStore((s) => s.user);
  const collapsed = usePreferencesStore((s) => s.setupProgressCollapsed);
  const toggleCollapsed = usePreferencesStore((s) => s.toggleSetupProgress);
  const skipped = usePreferencesStore((s) => s.setupProgressSkipped);
  const skipStep = usePreferencesStore((s) => s.skipSetupStep);
  const unskipStep = usePreferencesStore((s) => s.unskipSetupStep);
  const isAdmin = user?.role === 'ADMIN';

  const { data } = useQuery({
    queryKey: setupProgressKeys.current(),
    queryFn: getSetupProgress,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const steps = useMemo(() => (data ? buildSteps(data) : []), [data]);
  const effectivelyDoneCount = useMemo(() => steps.filter(
    (s) => s.configured || skipped.includes(s.id),
  ).length, [steps, skipped]);

  if (!isAdmin || !data || effectivelyDoneCount === steps.length) {
    return null;
  }

  const percent = Math.round((effectivelyDoneCount / steps.length) * 100);

  return (
    <section
      className="af-setup-progress"
      aria-label={t('admin.setup_progress.progress_aria')}
    >
      <header className="af-setup-progress-header">
        <div className="af-setup-progress-title-block">
          <div className="af-setup-progress-title-row">
            <span className="af-setup-progress-title-icon" aria-hidden="true">
              <RocketOutlined />
            </span>
            <div>
              <div className="af-setup-progress-title">
                {t('admin.setup_progress.title')}
              </div>
              {!collapsed && (
                <div className="muted af-setup-progress-subtitle">
                  {t('admin.setup_progress.subtitle')}
                </div>
              )}
            </div>
          </div>
        </div>
        <div className="af-setup-progress-meta">
          <Progress
            type="line"
            size="small"
            percent={percent}
            showInfo={false}
            aria-label={t('admin.setup_progress.progress_aria')}
            className="af-setup-progress-bar"
          />
          <span className="mono af-setup-progress-count">
            {effectivelyDoneCount}/{steps.length}
          </span>
          <button
            type="button"
            className="af-icon-btn af-setup-progress-toggle"
            onClick={toggleCollapsed}
            aria-label={collapsed
              ? t('admin.setup_progress.expand')
              : t('admin.setup_progress.collapse')}
            aria-expanded={!collapsed}
          >
            {collapsed ? <RightOutlined /> : <DownOutlined />}
          </button>
        </div>
      </header>
      {!collapsed && (
        <ol className="af-setup-progress-steps">
          {steps.map((step, index) => {
            const isSkipped = skipped.includes(step.id);
            const isDone = step.configured;
            const isPending = !isDone && !isSkipped;
            const statusKey: StepStatusKey | null = isDone
              ? 'admin.setup_progress.step_status_done'
              : isSkipped
                ? 'admin.setup_progress.step_status_skipped'
                : null;
            return (
              <li
                key={step.id}
                className={[
                  'af-setup-progress-step',
                  isDone ? 'done' : '',
                  isSkipped ? 'skipped' : '',
                  isPending ? 'pending' : '',
                ].filter(Boolean).join(' ')}
              >
                <span className="af-setup-progress-step-icon" aria-hidden="true">
                  {isDone ? (
                    <CheckCircleFilled style={{ color: 'var(--success-fg, #52c41a)' }} />
                  ) : (
                    <span
                      className={`af-setup-progress-step-number${isSkipped ? ' skipped' : ''}`}
                    >
                      {index + 1}
                    </span>
                  )}
                </span>
                <span className="af-setup-progress-step-label">
                  {t(step.labelKey)}
                </span>
                {statusKey && (
                  <span
                    className={`af-setup-progress-step-status${isSkipped ? ' skipped' : ' done'}`}
                  >
                    {t(statusKey)}
                  </span>
                )}
                <span className="af-setup-progress-step-actions">
                  {isPending && (
                    <>
                      <Link to={step.to}>
                        <Button size="small" type="primary">
                          {t('admin.setup_progress.step_action_setup')}
                        </Button>
                      </Link>
                      <button
                        type="button"
                        className="af-setup-progress-skip-link"
                        onClick={() => skipStep(step.id)}
                      >
                        {t('admin.setup_progress.step_action_skip')}
                      </button>
                    </>
                  )}
                  {isSkipped && (
                    <button
                      type="button"
                      className="af-setup-progress-skip-link"
                      onClick={() => unskipStep(step.id)}
                    >
                      {t('admin.setup_progress.step_action_undo_skip')}
                    </button>
                  )}
                </span>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
