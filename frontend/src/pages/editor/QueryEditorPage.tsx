import { useMemo, useState } from 'react';
import { Alert, App, Button, DatePicker, Input, Modal, Tooltip } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import {
  ThunderboltOutlined,
  PlayCircleOutlined,
  LoadingOutlined,
  FolderOpenOutlined,
  BookOutlined,
  SaveOutlined,
  ExperimentOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { QueryAuthoringPanel } from '@/components/editor/QueryAuthoringPanel';
import { useQueryAuthoring } from '@/components/editor/useQueryAuthoring';
import { ReviewPlanPreview } from '@/components/editor/ReviewPlanPreview';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { breakGlassSubmit, queryKeys, submitQuery } from '@/api/queries';
import { getBreakGlassEligibility, meKeys } from '@/api/me';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { SubmissionReason } from '@/types/api';
import './editor.css';

export function QueryEditorPage() {
  const { t } = useTranslation();
  // A dashboard AI suggestion ("open in editor", AF-498) navigates here with a preset SQL + datasource.
  const presetState = useLocation().state as { presetSql?: string; datasourceId?: string } | null;
  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ size: 100 }),
    queryFn: () => listDatasources({ size: 100 }),
  });
  const datasources = useMemo(
    () => (datasourcesQuery.data?.content ?? []).filter((d) => d.active),
    [datasourcesQuery.data],
  );
  const [selectedDsId, setSelectedDsId] = useState<string | null>(presetState?.datasourceId ?? null);
  const dsId = selectedDsId ?? datasources[0]?.id ?? null;
  const ds = datasources.find((d) => d.id === dsId) ?? null;
  const [sql, setSql] = useState(() => presetState?.presetSql ?? '');
  const [justification, setJustification] = useState('');
  const [scheduledFor, setScheduledFor] = useState<Dayjs | null>(null);
  const [submissionReason, setSubmissionReason] = useState<SubmissionReason>('USER_SUBMITTED');
  const [breakGlassOpen, setBreakGlassOpen] = useState(false);
  const [breakGlassJustification, setBreakGlassJustification] = useState('');
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const authoring = useQueryAuthoring({
    ds,
    sql,
    onSqlChange: (next, source) => {
      setSql(next);
      // A manual edit (or template/generated draft) clears the "came from an AI suggestion" flag;
      // an applied suggestion sets it. The flag survives the required re-analysis.
      setSubmissionReason(source === 'ai_suggestion' ? 'AI_SUGGESTION' : 'USER_SUBMITTED');
    },
  });

  const submitMutation = useMutation({
    mutationFn: () =>
      submitQuery({
        datasource_id: ds!.id,
        sql,
        justification,
        scheduled_for: scheduledFor ? scheduledFor.toISOString() : null,
        submission_reason: submissionReason,
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.success({
        content: t('editor.submit_success', { id: created.id }),
        duration: 2.5,
      });
      navigate(`/queries/${created.id}`);
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('editor.submit_error')));
    },
  });

  const breakGlassEligibilityQuery = useQuery({
    queryKey: meKeys.breakGlass,
    queryFn: getBreakGlassEligibility,
  });
  const breakGlassEligible = (breakGlassEligibilityQuery.data?.eligible_datasources ?? []).some(
    (e) => e.datasource_id === dsId,
  );

  const breakGlassMutation = useMutation({
    mutationFn: () =>
      breakGlassSubmit({ datasource_id: ds!.id, sql, justification: breakGlassJustification }),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.success({ content: t('editor.break_glass_success'), duration: 2.5 });
      setBreakGlassOpen(false);
      setBreakGlassJustification('');
      navigate(`/queries/${result.id}`);
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('editor.break_glass_error')));
    },
  });

  if (datasourcesQuery.isLoading) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">{t('datasources.list.loading')}</div>
      </div>
    );
  }

  if (!ds) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">{t('datasources.list.empty')}</div>
      </div>
    );
  }

  const sqlNonEmpty = sql.trim().length > 0;
  const submitGatedByAnalysis = authoring.aiSupported && !authoring.hasFreshAnalysis;
  const scheduleInPast = !!scheduledFor && !scheduledFor.isAfter(dayjs());
  const canSubmit =
    sqlNonEmpty && !submitMutation.isPending && !submitGatedByAnalysis && !scheduleInPast;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('editor.title')}
        subtitle={t('editor.subtitle')}
        actions={
          <>
            <Button icon={<FolderOpenOutlined />} onClick={() => navigate('/queries')}>
              {t('editor.history_button')}
            </Button>
            <Button icon={<BookOutlined />} onClick={authoring.openTemplates}>
              {t('editor.templates_button')}
            </Button>
            <Button
              icon={<SaveOutlined />}
              disabled={!sqlNonEmpty}
              onClick={authoring.openSaveTemplate}
            >
              {t('editor.save_template_button')}
            </Button>
            <Button
              icon={authoring.dryRunning ? <LoadingOutlined /> : <ExperimentOutlined />}
              disabled={!authoring.canDryRun}
              onClick={authoring.dryRun}
            >
              {authoring.dryRunning ? t('editor.dry_running_button') : t('editor.dry_run_button')}
            </Button>
            {authoring.aiSupported && (
              <Button
                icon={authoring.analyzing ? <LoadingOutlined /> : <ThunderboltOutlined />}
                disabled={!authoring.canAnalyze}
                onClick={authoring.analyze}
              >
                {authoring.analyzing ? t('editor.analyzing_button') : t('editor.analyze_button')}
              </Button>
            )}
            {breakGlassEligible && (
              <Button
                danger
                icon={<WarningOutlined />}
                disabled={!sqlNonEmpty}
                onClick={() => setBreakGlassOpen(true)}
                data-testid="break-glass-button"
              >
                {t('editor.break_glass_button')}
              </Button>
            )}
            <Tooltip
              title={
                submitGatedByAnalysis && sqlNonEmpty
                  ? t('editor.submit_disabled_needs_analysis_tooltip')
                  : ''
              }
            >
              <Button
                type="primary"
                icon={
                  submitMutation.isPending ? <LoadingOutlined /> : <PlayCircleOutlined />
                }
                disabled={!canSubmit}
                onClick={() => submitMutation.mutate()}
              >
                {submitMutation.isPending
                  ? t('editor.submitting_button')
                  : t('editor.submit_button')}
              </Button>
            </Tooltip>
          </>
        }
      />
      <QueryAuthoringPanel
        authoring={authoring}
        ds={ds}
        datasources={datasources}
        onChangeDs={setSelectedDsId}
        sql={sql}
        footer={
          <>
            <div>
              <label
                className="muted"
                style={{ display: 'block', fontSize: 11.5, fontWeight: 500, marginBottom: 5 }}
              >
                {t('editor.justification_label')}{' '}
                <span className="muted" style={{ fontWeight: 400 }}>
                  {t('editor.justification_required_note')}
                </span>
              </label>
              <Input.TextArea
                value={justification}
                onChange={(e) => setJustification(e.target.value)}
                placeholder={t('editor.justification_placeholder')}
                rows={3}
              />
            </div>
            <div>
              <label
                className="muted"
                style={{ display: 'block', fontSize: 11.5, fontWeight: 500, marginBottom: 5 }}
              >
                {t('editor.schedule_label')}{' '}
                <span className="muted" style={{ fontWeight: 400 }}>
                  {t('editor.schedule_optional_note')}
                </span>
              </label>
              <DatePicker
                showTime
                value={scheduledFor}
                onChange={(value) => setScheduledFor(value)}
                placeholder={t('editor.schedule_placeholder')}
                disabledDate={(d) => !!d && d.isBefore(dayjs().startOf('day'))}
                style={{ width: 280 }}
              />
              <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>
                {scheduleInPast
                  ? t('editor.schedule_in_past_error')
                  : t('editor.schedule_help')}
              </div>
            </div>
            <ReviewPlanPreview ds={ds} />
          </>
        }
      />
      <Modal
        open={breakGlassOpen}
        title={t('editor.break_glass_modal_title')}
        okText={t('editor.break_glass_confirm')}
        cancelText={t('common.cancel')}
        okButtonProps={{
          danger: true,
          disabled: !breakGlassJustification.trim(),
          'data-testid': 'break-glass-confirm',
        }}
        confirmLoading={breakGlassMutation.isPending}
        onCancel={() => setBreakGlassOpen(false)}
        onOk={() => breakGlassMutation.mutate()}
      >
        <Alert
          type="error"
          showIcon
          message={t('editor.break_glass_modal_warning_title')}
          description={t('editor.break_glass_modal_warning_body')}
          style={{ marginBottom: 12 }}
        />
        <label
          className="muted"
          style={{ display: 'block', fontSize: 11.5, fontWeight: 500, marginBottom: 5 }}
        >
          {t('editor.justification_label')}{' '}
          <span className="muted" style={{ fontWeight: 400 }}>
            {t('editor.justification_required_note')}
          </span>
        </label>
        <Input.TextArea
          value={breakGlassJustification}
          onChange={(e) => setBreakGlassJustification(e.target.value)}
          placeholder={t('editor.break_glass_justification_placeholder')}
          maxLength={4000}
          rows={3}
          data-testid="break-glass-justification"
        />
      </Modal>
    </div>
  );
}
