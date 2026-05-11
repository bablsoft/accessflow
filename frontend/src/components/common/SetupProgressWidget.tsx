import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Button, Progress } from 'antd';
import {
  CheckCircleTwoTone,
  RightOutlined,
  DownOutlined,
} from '@ant-design/icons';
import { getSetupProgress, setupProgressKeys } from '@/api/admin';
import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import type { SetupProgress } from '@/types/api';
import './setup-progress-widget.css';

interface SetupStep {
  id: 'datasources' | 'review_plans' | 'ai_provider';
  done: boolean;
  labelKey:
    | 'admin.setup_progress.step_datasources_label'
    | 'admin.setup_progress.step_review_plans_label'
    | 'admin.setup_progress.step_ai_provider_label';
  to: string;
}

function buildSteps(data: SetupProgress): SetupStep[] {
  return [
    {
      id: 'datasources',
      done: data.datasources_configured,
      labelKey: 'admin.setup_progress.step_datasources_label',
      to: '/datasources/new',
    },
    {
      id: 'review_plans',
      done: data.review_plans_configured,
      labelKey: 'admin.setup_progress.step_review_plans_label',
      to: '/admin/review-plans',
    },
    {
      id: 'ai_provider',
      done: data.ai_provider_configured,
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
  const isAdmin = user?.role === 'ADMIN';

  const { data } = useQuery({
    queryKey: setupProgressKeys.current(),
    queryFn: getSetupProgress,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  if (!isAdmin || !data || data.complete) {
    return null;
  }

  const steps = buildSteps(data);
  const percent = Math.round((data.completed_steps / data.total_steps) * 100);

  return (
    <section className="af-setup-progress" aria-label={t('admin.setup_progress.progress_aria')}>
      <header className="af-setup-progress-header">
        <div className="af-setup-progress-title-block">
          <div className="af-setup-progress-title">{t('admin.setup_progress.title')}</div>
          {!collapsed && (
            <div className="muted af-setup-progress-subtitle">
              {t('admin.setup_progress.subtitle')}
            </div>
          )}
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
            {data.completed_steps}/{data.total_steps}
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
        <ul className="af-setup-progress-steps">
          {steps.map((step) => (
            <li
              key={step.id}
              className={`af-setup-progress-step${step.done ? ' done' : ''}`}
            >
              <span className="af-setup-progress-step-icon" aria-hidden="true">
                {step.done ? (
                  <CheckCircleTwoTone twoToneColor="#52c41a" />
                ) : (
                  <span className="af-setup-progress-step-dot" />
                )}
              </span>
              <span className="af-setup-progress-step-label">{t(step.labelKey)}</span>
              {step.done ? (
                <span className="muted af-setup-progress-step-status">
                  {t('admin.setup_progress.step_status_done')}
                </span>
              ) : (
                <Link to={step.to}>
                  <Button size="small" type="primary">
                    {t('admin.setup_progress.step_action_setup')}
                  </Button>
                </Link>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
