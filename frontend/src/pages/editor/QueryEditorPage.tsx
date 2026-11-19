import { useEffect, useMemo, useRef, useState } from 'react';
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
import { Page } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { RouteFallback } from '@/components/layout/RouteFallback';
import { useAuthStore } from '@/store/authStore';
import { clearEditorDraft, loadEditorDraft, saveEditorDraft } from './editorDraft';
import { QueryAuthoringPanel } from '@/components/editor/QueryAuthoringPanel';
import {
  RecurrencePicker,
  cronLooksValid,
  type RecurrenceMode,
} from '@/components/editor/RecurrencePicker';
import { useQueryAuthoring } from '@/components/editor/useQueryAuthoring';
import { DataBudgetIndicator } from '@/components/editor/DataBudgetIndicator';
import { ReviewPlanPreview } from '@/components/editor/ReviewPlanPreview';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { breakGlassSubmit, queryKeys, submitQuery } from '@/api/queries';
import { getBreakGlassEligibility, meKeys } from '@/api/me';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { SubmissionReason } from '@/types/api';
import type { SqlChangeSource } from '@/components/editor/useQueryAuthoring';
import './editor.css';

const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);
const MOD = IS_MAC ? '⌘' : 'Ctrl+';

const SUBMISSION_REASON_BY_SOURCE: Partial<Record<SqlChangeSource, SubmissionReason>> = {
  ai_suggestion: 'AI_SUGGESTION',
  history_suggestion: 'HISTORY_SUGGESTION',
};

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
  const userId = useAuthStore((s) => s.user?.id);
  // A preset (dashboard suggestion, Duplicate) wins over a kept draft; the draft is read once.
  const [initialDraft] = useState(() =>
    presetState?.presetSql ? null : loadEditorDraft(userId),
  );
  const [draftRestored, setDraftRestored] = useState(initialDraft !== null);
  const [selectedDsId, setSelectedDsId] = useState<string | null>(
    presetState?.datasourceId ?? initialDraft?.datasourceId ?? null,
  );
  // A remembered datasource that is gone (deleted, deactivated, access revoked) falls back to
  // the first one instead of leaving the editor without a target.
  const dsId =
    (datasources.some((d) => d.id === selectedDsId) ? selectedDsId : datasources[0]?.id) ?? null;
  const ds = datasources.find((d) => d.id === dsId) ?? null;
  const [sql, setSql] = useState(() => presetState?.presetSql ?? initialDraft?.sql ?? '');
  const [justification, setJustification] = useState(initialDraft?.justification ?? '');
  const [scheduledFor, setScheduledFor] = useState<Dayjs | null>(null);
  // #627 recurring series — mutually exclusive with scheduledFor (setting one clears the other).
  const [recurrenceMode, setRecurrenceMode] = useState<RecurrenceMode>('none');
  const [recurrenceRule, setRecurrenceRule] = useState('');
  const [recurrenceUntil, setRecurrenceUntil] = useState<Dayjs | null>(null);
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
      // Provenance follows the draft's origin and survives the required re-analysis. A manual edit,
      // a template or a text-to-SQL draft is the analyst's own work; the two suggestion sources stay
      // distinct so AI-suggestion adoption (#451/#498) and history-suggestion adoption (#776) can
      // be told apart in the audit trail.
      setSubmissionReason(SUBMISSION_REASON_BY_SOURCE[source] ?? 'USER_SUBMITTED');
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
        recurrence_rule: recurrenceMode !== 'none' && recurrenceRule ? recurrenceRule : null,
        recurrence_until:
          recurrenceMode !== 'none' && recurrenceUntil ? recurrenceUntil.toISOString() : null,
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      clearEditorDraft(userId);
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
      clearEditorDraft(userId);
      message.success({ content: t('editor.break_glass_success'), duration: 2.5 });
      setBreakGlassOpen(false);
      setBreakGlassJustification('');
      navigate(`/queries/${result.id}`);
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('editor.break_glass_error')));
    },
  });

  const sqlNonEmpty = sql.trim().length > 0;
  const submitGatedByAnalysis = authoring.aiSupported && !authoring.hasFreshAnalysis;
  const blockingFindings = authoring.sqlReview.blockingCount;
  const scheduleInPast = !!scheduledFor && !scheduledFor.isAfter(dayjs());
  // #627: a recurring submission needs a rule and a future end time; mirror the backend rules.
  const recurring = recurrenceMode !== 'none';
  const recurrenceInvalid =
    recurring &&
    (!recurrenceRule.trim() ||
      !recurrenceUntil ||
      !recurrenceUntil.isAfter(dayjs()) ||
      (recurrenceMode === 'cron' && !cronLooksValid(recurrenceRule)));
  const canSubmit =
    sqlNonEmpty &&
    !submitMutation.isPending &&
    !submitGatedByAnalysis &&
    !scheduleInPast &&
    !recurrenceInvalid;

  useEffect(() => {
    saveEditorDraft(userId, { datasourceId: dsId, sql, justification });
  }, [userId, dsId, sql, justification]);

  // Latest actions for the window-level shortcuts, so the listener is registered once.
  const shortcuts = useRef({ submit: () => {}, analyze: () => {}, dryRun: () => {} });
  useEffect(() => {
    shortcuts.current = {
      submit: () => { if (canSubmit && ds) submitMutation.mutate(); },
      analyze: () => { if (authoring.aiSupported && authoring.canAnalyze) authoring.analyze(); },
      dryRun: () => { if (authoring.canDryRun) authoring.dryRun(); },
    };
  });
  useEffect(() => {
    // Capture phase: CodeMirror binds Mod-Enter itself and would swallow the event otherwise.
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Enter' || document.querySelector('.ant-modal-wrap:not([style*="display: none"])')) return;
      const mod = event.metaKey || event.ctrlKey;
      let action: 'submit' | 'analyze' | 'dryRun' | null = null;
      if (mod && event.shiftKey && !event.altKey) action = 'analyze';
      else if (mod && !event.shiftKey && !event.altKey) action = 'submit';
      else if (event.altKey && !mod && !event.shiftKey) action = 'dryRun';
      if (!action) return;
      event.preventDefault();
      event.stopPropagation();
      shortcuts.current[action]();
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, []);

  const discardDraft = () => {
    clearEditorDraft(userId);
    setSql('');
    setJustification('');
    setDraftRestored(false);
  };

  if (datasourcesQuery.isLoading) {
    return <RouteFallback />;
  }

  if (!ds) {
    return (
      <EmptyState
        title={t('editor.no_datasource_title')}
        description={t('datasources.list.empty')}
      />
    );
  }

  return (
    <Page>
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
              <span className="kbd af-editor-kbd" aria-hidden>{IS_MAC ? '⌥↵' : 'Alt+↵'}</span>
            </Button>
            {authoring.aiSupported && (
              <Button
                icon={authoring.analyzing ? <LoadingOutlined /> : <ThunderboltOutlined />}
                disabled={!authoring.canAnalyze}
                onClick={authoring.analyze}
              >
                {authoring.analyzing ? t('editor.analyzing_button') : t('editor.analyze_button')}
                <span className="kbd af-editor-kbd" aria-hidden>{`${MOD}⇧↵`}</span>
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
                  : // A BLOCK finding never disables Submit — it escalates to a human (#865).
                    blockingFindings > 0
                    ? t('editor.submit_blocking_findings_tooltip', { count: blockingFindings })
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
                <span className="kbd af-editor-kbd" aria-hidden>{`${MOD}↵`}</span>
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
            {draftRestored && (
              <Alert
                type="info"
                showIcon
                closable={{ onClose: () => setDraftRestored(false) }}
                title={t('editor.draft_restored')}
                action={
                  <Button size="small" onClick={discardDraft}>
                    {t('editor.draft_discard')}
                  </Button>
                }
              />
            )}
            {submitGatedByAnalysis && sqlNonEmpty && (
              <div className="af-editor-submit-hint" role="status">
                {t('editor.submit_disabled_needs_analysis_tooltip')}
              </div>
            )}
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
                onChange={(value) => {
                  setScheduledFor(value);
                  // One-time scheduling and recurrence are mutually exclusive (#627).
                  if (value) {
                    setRecurrenceMode('none');
                    setRecurrenceRule('');
                    setRecurrenceUntil(null);
                  }
                }}
                placeholder={t('editor.schedule_placeholder')}
                disabledDate={(d) => !!d && d.isBefore(dayjs().startOf('day'))}
                style={{ width: 280 }}
              />
              <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>
                {scheduleInPast
                  ? t('editor.schedule_in_past_error')
                  : recurring
                    ? t('editor.recurrence_exclusive_note')
                    : t('editor.schedule_help')}
              </div>
            </div>
            <RecurrencePicker
              mode={recurrenceMode}
              onModeChange={(mode) => {
                setRecurrenceMode(mode);
                if (mode !== 'none') {
                  setScheduledFor(null);
                }
              }}
              rule={recurrenceRule}
              onRuleChange={setRecurrenceRule}
              until={recurrenceUntil}
              onUntilChange={setRecurrenceUntil}
            />
            <DataBudgetIndicator dsId={ds.id} />
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
          title={t('editor.break_glass_modal_warning_title')}
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
    </Page>
  );
}
