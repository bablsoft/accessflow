import { useState } from 'react';
import { App } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { analyzeOnly, dryRunQuery } from '@/api/queries';
import { formatSql } from '@/utils/sqlFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { activeSyntax, engineMode, syntaxForQuery, type EngineMode } from '@/utils/engineModes';
import type { AiAnalysis, Datasource, QueryDryRunResult, QueryTemplate } from '@/types/api';

/**
 * Where an SQL change came from, so hosts can keep their own semantics on top (the standalone
 * editor maps 'ai_suggestion' onto submission_reason=AI_SUGGESTION and everything else back to
 * USER_SUBMITTED).
 */
export type SqlChangeSource = 'user' | 'ai_suggestion' | 'template' | 'generated';

export interface UseQueryAuthoringArgs {
  ds: Datasource | null;
  sql: string;
  onSqlChange: (next: string, source: SqlChangeSource) => void;
}

/**
 * Transient authoring state shared by the standalone Query Editor and the group-builder member
 * drawer (#559): engine mode + syntax toggle, AI analysis + staleness, dry-run + staleness, the
 * AI/plan rail toggle, formatting, and the template drawer/modal choreography. The host stays in
 * charge of the SQL value, target selection, and submission concerns.
 */
export interface QueryAuthoring {
  mode: EngineMode;
  effectiveSyntax: string;
  setSyntax: (syntax: string) => void;
  aiSupported: boolean;
  textToSqlSupported: boolean;
  // AI analysis
  analyzing: boolean;
  analysis: AiAnalysis | null;
  analysisStale: boolean;
  hasFreshAnalysis: boolean;
  canAnalyze: boolean;
  analyze: () => void;
  // dry-run
  dryRunning: boolean;
  dryRunResult: QueryDryRunResult | null;
  dryRunStale: boolean;
  canDryRun: boolean;
  dryRun: () => void;
  // right rail
  rightPanel: 'ai' | 'plan';
  setRightPanel: (panel: 'ai' | 'plan') => void;
  // formatting
  canFormat: boolean;
  format: () => void;
  // templates (open state is owned here; the panel renders the drawer/modals)
  templatesOpen: boolean;
  openTemplates: () => void;
  closeTemplates: () => void;
  saveTemplateOpen: boolean;
  openSaveTemplate: () => void;
  closeSaveTemplate: () => void;
  pendingTemplate: QueryTemplate | null;
  setPendingTemplate: (template: QueryTemplate | null) => void;
  applyTemplate: (renderedSql: string) => void;
  // change funnel (resets failed mutations, tags the source)
  handleSqlChange: (next: string, source?: SqlChangeSource) => void;
  applySuggestion: (suggestionSql: string) => void;
  applyGenerated: (generatedSql: string, generatedSyntax?: string) => void;
}

export function useQueryAuthoring({ ds, sql, onSqlChange }: UseQueryAuthoringArgs): QueryAuthoring {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [analyzedSql, setAnalyzedSql] = useState<string | null>(null);
  const [dryRunSql, setDryRunSql] = useState<string | null>(null);
  const [rightPanel, setRightPanel] = useState<'ai' | 'plan'>('ai');
  const [syntax, setSyntax] = useState<string | undefined>(undefined);
  const [templatesOpen, setTemplatesOpen] = useState(false);
  const [saveTemplateOpen, setSaveTemplateOpen] = useState(false);
  const [pendingTemplate, setPendingTemplate] = useState<QueryTemplate | null>(null);

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

  const handleSqlChange = (next: string, source: SqlChangeSource = 'user') => {
    onSqlChange(next, source);
    // A successful analysis is kept on screen (marked stale below); only clear a *failed* one so
    // the empty prompt returns and the user can re-analyze.
    if (analyzeMutation.isError) {
      analyzeMutation.reset();
    }
    if (dryRunMutation.isError) {
      dryRunMutation.reset();
    }
  };

  const mode = engineMode(ds?.db_type);
  // Falls back to the mode's default when the stored value is stale (datasource switch).
  const effectiveSyntax = activeSyntax(mode, syntax).value;
  const aiSupported = !!ds && ds.ai_analysis_enabled && !!ds.ai_config_id;
  const textToSqlSupported =
    !!ds && mode.supportsTextToSql && ds.text_to_sql_enabled && !!ds.ai_config_id;
  const sqlNonEmpty = sql.trim().length > 0;

  const analysis = analyzeMutation.data ?? null;
  // Stale = an analysis exists but the live SQL has diverged from what it ran against. We keep it
  // on screen (so the user can still read the risks and apply remaining suggestions) but mark it.
  const analysisStale = !!analysis && analyzedSql !== sql.trim();
  const hasFreshAnalysis = !!analysis && !analysisStale;
  const canAnalyze = aiSupported && sqlNonEmpty && !analyzeMutation.isPending;

  const dryRunResult = dryRunMutation.data ?? null;
  const dryRunning = dryRunMutation.isPending;
  const dryRunStale = !!dryRunResult && dryRunSql !== sql.trim();
  const canDryRun = sqlNonEmpty && !dryRunning;

  return {
    mode,
    effectiveSyntax,
    setSyntax,
    aiSupported,
    textToSqlSupported,
    analyzing: analyzeMutation.isPending,
    analysis,
    analysisStale,
    hasFreshAnalysis,
    canAnalyze,
    analyze: () => analyzeMutation.mutate(sql.trim()),
    dryRunning,
    dryRunResult,
    dryRunStale,
    canDryRun,
    dryRun: () => {
      setRightPanel('plan');
      dryRunMutation.mutate(sql.trim());
    },
    rightPanel,
    setRightPanel,
    canFormat: mode.canFormat,
    format: () => handleSqlChange(formatSql(sql, ds?.db_type)),
    templatesOpen,
    openTemplates: () => setTemplatesOpen(true),
    closeTemplates: () => setTemplatesOpen(false),
    saveTemplateOpen,
    openSaveTemplate: () => setSaveTemplateOpen(true),
    closeSaveTemplate: () => setSaveTemplateOpen(false),
    pendingTemplate,
    setPendingTemplate,
    applyTemplate: (renderedSql) => {
      handleSqlChange(renderedSql, 'template');
      setPendingTemplate(null);
      setTemplatesOpen(false);
    },
    handleSqlChange,
    applySuggestion: (suggestionSql) => {
      // The user must re-analyze the applied draft before submitting; mount the editor in the
      // engine's native mode for the applied draft (e.g. MongoDB shell vs JSON, CQL, Cypher …).
      handleSqlChange(suggestionSql, 'ai_suggestion');
      setSyntax(syntaxForQuery(ds?.db_type, suggestionSql));
    },
    applyGenerated: (generatedSql, generatedSyntax) => {
      // Switch the editor to the draft's syntax (e.g. MongoDB shell vs JSON) when the backend hint
      // names one valid for this engine; the CodeMirror mode follows.
      if (generatedSyntax && mode.syntaxes.some((s) => s.value === generatedSyntax)) {
        setSyntax(generatedSyntax);
      }
      handleSqlChange(generatedSql, 'generated');
    },
  };
}
