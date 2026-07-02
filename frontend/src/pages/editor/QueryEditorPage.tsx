import { useMemo, useState } from 'react';
import { Alert, App, Button, DatePicker, Input, Modal, Segmented, Tooltip } from 'antd';
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
import { RiskPill } from '@/components/common/RiskPill';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { AiHintPanel } from '@/components/editor/AiHintPanel';
import { DryRunPanel } from '@/components/editor/DryRunPanel';
import { TextToSqlBar } from '@/components/editor/TextToSqlBar';
import { SchemaTree } from '@/components/editor/SchemaTree';
import { ReviewPlanPreview } from '@/components/editor/ReviewPlanPreview';
import { QueryTemplatesDrawer } from '@/components/editor/QueryTemplatesDrawer';
import { SaveTemplateModal } from '@/components/editor/SaveTemplateModal';
import { LoadTemplateModal } from '@/components/editor/LoadTemplateModal';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { useSchemaIntrospect } from '@/hooks/useSchemaIntrospect';
import { analyzeOnly, breakGlassSubmit, dryRunQuery, queryKeys, submitQuery } from '@/api/queries';
import { getBreakGlassEligibility, meKeys } from '@/api/me';
import { formatSql } from '@/utils/sqlFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { activeSyntax, engineMode, syntaxForQuery } from '@/utils/engineModes';
import type { QueryTemplate, SubmissionReason } from '@/types/api';
import './editor.css';

const EMPTY_SCHEMA = { schemas: [] };

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
  const [analyzedSql, setAnalyzedSql] = useState<string | null>(null);
  const [rightPanel, setRightPanel] = useState<'ai' | 'plan'>('ai');
  const [dryRunSql, setDryRunSql] = useState<string | null>(null);
  const [syntax, setSyntax] = useState<string | undefined>(undefined);
  const [justification, setJustification] = useState('');
  const [scheduledFor, setScheduledFor] = useState<Dayjs | null>(null);
  const [submissionReason, setSubmissionReason] = useState<SubmissionReason>('USER_SUBMITTED');
  const [templatesOpen, setTemplatesOpen] = useState(false);
  const [saveTemplateOpen, setSaveTemplateOpen] = useState(false);
  const [pendingTemplate, setPendingTemplate] = useState<QueryTemplate | null>(null);
  const [breakGlassOpen, setBreakGlassOpen] = useState(false);
  const [breakGlassJustification, setBreakGlassJustification] = useState('');
  const schemaQuery = useSchemaIntrospect(ds?.id);
  const schema = schemaQuery.data ?? EMPTY_SCHEMA;
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const analyzeMutation = useMutation({
    mutationFn: (sqlToAnalyze: string) => analyzeOnly({ datasource_id: ds!.id, sql: sqlToAnalyze }),
    onSuccess: (_data, sqlToAnalyze) => {
      // Remember the exact SQL this analysis ran against so we can detect staleness later.
      setAnalyzedSql(sqlToAnalyze);
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('editor.analyze_error')));
    },
  });

  const dryRunMutation = useMutation({
    mutationFn: (sqlToRun: string) => dryRunQuery({ datasource_id: ds!.id, sql: sqlToRun }),
    onSuccess: (_data, sqlToRun) => {
      setDryRunSql(sqlToRun);
    },
    onError: (err) => {
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('editor.dry_run_error')));
    },
  });

  const handleSqlChange = (next: string) => {
    setSql(next);
    // A manual edit clears the "came from an AI suggestion" flag.
    setSubmissionReason('USER_SUBMITTED');
    // A successful analysis is kept on screen (marked stale below); only clear a *failed* one so the
    // empty prompt returns and the user can re-analyze.
    if (analyzeMutation.isError) {
      analyzeMutation.reset();
    }
    if (dryRunMutation.isError) {
      dryRunMutation.reset();
    }
  };

  const handleDryRun = () => {
    setRightPanel('plan');
    dryRunMutation.mutate(sql.trim());
  };

  const handleApplySuggestion = (suggestionSql: string) => {
    // handleSqlChange resets the reason; set the AI flag after it. The user must re-analyze the
    // applied draft before submitting (analysis was reset), and the flag survives that re-analysis.
    handleSqlChange(suggestionSql);
    setSubmissionReason('AI_SUGGESTION');
    // Mount the editor in the engine's native mode for the applied draft (e.g. MongoDB shell vs
    // JSON, CQL, Cypher, Query DSL …) so NoSQL suggestions render in the right syntax.
    setSyntax(syntaxForQuery(ds?.db_type, suggestionSql));
  };

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

  const analyzing = analyzeMutation.isPending;
  const analysis = analyzeMutation.data ?? null;
  // Stale = an analysis exists but the live SQL has diverged from what it ran against. We keep it on
  // screen (so the user can still read the risks and apply remaining suggestions) but mark it stale.
  const analysisStale = !!analysis && analyzedSql !== sql.trim();

  const lineCount = (sql.match(/\n/g) ?? []).length + 1;

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

  const mode = engineMode(ds.db_type);
  // Falls back to the mode's default when the stored value is stale (datasource switch).
  const effectiveSyntax = activeSyntax(mode, syntax).value;
  const aiSupported = ds.ai_analysis_enabled && !!ds.ai_config_id;
  const textToSqlSupported = mode.supportsTextToSql && ds.text_to_sql_enabled && !!ds.ai_config_id;
  const sqlNonEmpty = sql.trim().length > 0;
  const hasFreshAnalysis = !!analysis && !analysisStale;
  const canAnalyze = aiSupported && sqlNonEmpty && !analyzeMutation.isPending;
  const submitGatedByAnalysis = aiSupported && !hasFreshAnalysis;
  const scheduleInPast = !!scheduledFor && !scheduledFor.isAfter(dayjs());
  const canSubmit =
    sqlNonEmpty && !submitMutation.isPending && !submitGatedByAnalysis && !scheduleInPast;
  const dryRunResult = dryRunMutation.data ?? null;
  const dryRunning = dryRunMutation.isPending;
  const dryRunStale = !!dryRunResult && dryRunSql !== sql.trim();
  const canDryRun = sqlNonEmpty && !dryRunning;

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
            <Button icon={<BookOutlined />} onClick={() => setTemplatesOpen(true)}>
              {t('editor.templates_button')}
            </Button>
            <Button
              icon={<SaveOutlined />}
              disabled={!sqlNonEmpty}
              onClick={() => setSaveTemplateOpen(true)}
            >
              {t('editor.save_template_button')}
            </Button>
            <Button
              icon={dryRunning ? <LoadingOutlined /> : <ExperimentOutlined />}
              disabled={!canDryRun}
              onClick={handleDryRun}
            >
              {dryRunning ? t('editor.dry_running_button') : t('editor.dry_run_button')}
            </Button>
            {aiSupported && (
              <Button
                icon={analyzeMutation.isPending ? <LoadingOutlined /> : <ThunderboltOutlined />}
                disabled={!canAnalyze}
                onClick={() => analyzeMutation.mutate(sql.trim())}
              >
                {analyzeMutation.isPending
                  ? t('editor.analyzing_button')
                  : t('editor.analyze_button')}
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
      <div className="af-editor-grid">
        <SchemaTree
          ds={ds}
          datasources={datasources}
          onChangeDs={setSelectedDsId}
        />
        <div className="af-editor-center">
          <div className="af-editor-toolbar">
            <span className="mono muted" style={{ fontSize: 11 }}>
              {ds.database_name}
            </span>
            <span style={{ color: 'var(--fg-faint)' }}>·</span>
            <span className="mono muted" style={{ fontSize: 11 }}>
              {t('editor.lines_count', { count: lineCount })}
            </span>
            <span style={{ color: 'var(--fg-faint)' }}>·</span>
            <span className="mono muted" style={{ fontSize: 11 }}>
              {t('editor.chars_count', { count: sql.length })}
            </span>
            <div style={{ flex: 1 }} />
            {analysis && !analyzing ? (
              <RiskPill level={analysis.risk_level} score={analysis.risk_score} size="sm" />
            ) : null}
            {mode.syntaxes.length > 1 && (
              <Segmented<string>
                size="small"
                value={effectiveSyntax}
                onChange={setSyntax}
                aria-label={t('editor.syntax_label')}
                options={mode.syntaxes.map((s) => ({ label: t(s.labelKey), value: s.value }))}
              />
            )}
            {mode.canFormat && (
              <Button
                size="small"
                icon={<ThunderboltOutlined />}
                onClick={() => handleSqlChange(formatSql(sql, ds.db_type))}
              >
                {t('editor.format_button')}{' '}
                <span className="kbd" style={{ marginLeft: 4 }}>⌘⇧F</span>
              </Button>
            )}
          </div>
          <div className="af-editor-body">
            {textToSqlSupported && (
              <TextToSqlBar
                datasourceId={ds.id}
                onGenerated={(next, generatedSyntax) => {
                  // Switch the editor to the draft's syntax (e.g. MongoDB shell vs JSON) when the
                  // backend hint names one valid for this engine; the CodeMirror mode follows.
                  if (generatedSyntax && mode.syntaxes.some((s) => s.value === generatedSyntax)) {
                    setSyntax(generatedSyntax);
                  }
                  handleSqlChange(next);
                }}
              />
            )}
            <SqlEditor
              value={sql}
              onChange={handleSqlChange}
              schema={schema}
              dbType={ds.db_type}
              syntax={effectiveSyntax}
              issues={hasFreshAnalysis ? analysis?.issues : undefined}
              height={300}
            />
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
          </div>
        </div>
        <div
          style={{
            display: 'grid',
            gridTemplateRows: 'auto 1fr',
            minHeight: 0,
            borderLeft: '1px solid var(--border)',
          }}
        >
          <div style={{ padding: 8, borderBottom: '1px solid var(--border)', background: 'var(--bg-sunken)' }}>
            <Segmented<'ai' | 'plan'>
              size="small"
              block
              value={rightPanel}
              onChange={setRightPanel}
              aria-label={t('editor.panel_toggle_label')}
              options={[
                { label: t('editor.panel_ai'), value: 'ai' },
                { label: t('editor.panel_plan'), value: 'plan' },
              ]}
            />
          </div>
          {rightPanel === 'ai' ? (
            <AiHintPanel
              analyzing={analyzing}
              analysis={analysis}
              stale={analysisStale}
              aiEnabled={ds.ai_analysis_enabled}
              onApplySuggestion={handleApplySuggestion}
              onReanalyze={canAnalyze ? () => analyzeMutation.mutate(sql.trim()) : undefined}
            />
          ) : (
            <DryRunPanel
              running={dryRunning}
              result={dryRunResult}
              stale={dryRunStale}
              onRun={canDryRun ? handleDryRun : undefined}
            />
          )}
        </div>
      </div>
      <QueryTemplatesDrawer
        open={templatesOpen}
        onClose={() => setTemplatesOpen(false)}
        currentDatasourceId={ds.id}
        dbType={ds.db_type}
        onOpen={(template) => setPendingTemplate(template)}
      />
      <SaveTemplateModal
        open={saveTemplateOpen}
        sql={sql}
        datasourceId={ds.id}
        onClose={() => setSaveTemplateOpen(false)}
      />
      <LoadTemplateModal
        template={pendingTemplate}
        onCancel={() => setPendingTemplate(null)}
        onConfirm={(rendered) => {
          handleSqlChange(rendered);
          setPendingTemplate(null);
          setTemplatesOpen(false);
        }}
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
