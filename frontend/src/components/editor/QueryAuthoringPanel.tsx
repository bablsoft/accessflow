import type { ReactNode } from 'react';
import { Button, Segmented } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { RiskPill } from '@/components/common/RiskPill';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { AiHintPanel } from '@/components/editor/AiHintPanel';
import { DryRunPanel } from '@/components/editor/DryRunPanel';
import { TextToSqlBar } from '@/components/editor/TextToSqlBar';
import { SchemaTree } from '@/components/editor/SchemaTree';
import { QueryTemplatesDrawer } from '@/components/editor/QueryTemplatesDrawer';
import { SaveTemplateModal } from '@/components/editor/SaveTemplateModal';
import { LoadTemplateModal } from '@/components/editor/LoadTemplateModal';
import { useSchemaIntrospect } from '@/hooks/useSchemaIntrospect';
import type { Datasource } from '@/types/api';
import type { QueryAuthoring } from './useQueryAuthoring';
import '@/pages/editor/editor.css';

const EMPTY_SCHEMA = { schemas: [] };

interface QueryAuthoringPanelProps {
  authoring: QueryAuthoring;
  /** The selected datasource — hosts guard the no-datasource case before rendering the panel. */
  ds: Datasource;
  /** Active-only list for the SchemaTree selector (hosts filter). */
  datasources: Datasource[];
  onChangeDs: (id: string) => void;
  sql: string;
  /** 'drawer' narrows the grid for the group-member edit drawer (#559). */
  variant?: 'page' | 'drawer';
  editorHeight?: number;
  /** Rendered under the editor — the page puts justification/schedule here, the drawer options. */
  footer?: ReactNode;
}

/**
 * The shared query-authoring surface (#559): schema tree + selector, toolbar (syntax toggle,
 * format), text-to-SQL, the CodeMirror editor with schema autocomplete + AI gutter markers, the
 * AI/plan right rail, and the query-template drawer/modals. Rendered by both the standalone
 * Query Editor page and the group-builder member drawer so the two never drift.
 */
export function QueryAuthoringPanel({
  authoring,
  ds,
  datasources,
  onChangeDs,
  sql,
  variant = 'page',
  editorHeight = 300,
  footer,
}: QueryAuthoringPanelProps) {
  const { t } = useTranslation();
  const schemaQuery = useSchemaIntrospect(ds.id);
  const schema = schemaQuery.data ?? EMPTY_SCHEMA;
  const lineCount = (sql.match(/\n/g) ?? []).length + 1;

  return (
    <div className={`af-editor-grid${variant === 'drawer' ? ' af-editor-grid--drawer' : ''}`}>
      <SchemaTree ds={ds} datasources={datasources} onChangeDs={onChangeDs} />
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
          {authoring.analysis && !authoring.analyzing ? (
            <RiskPill
              level={authoring.analysis.risk_level}
              score={authoring.analysis.risk_score}
              size="sm"
            />
          ) : null}
          {authoring.mode.syntaxes.length > 1 && (
            <Segmented<string>
              size="small"
              value={authoring.effectiveSyntax}
              onChange={authoring.setSyntax}
              aria-label={t('editor.syntax_label')}
              options={authoring.mode.syntaxes.map((s) => ({
                label: t(s.labelKey),
                value: s.value,
              }))}
            />
          )}
          {authoring.canFormat && (
            <Button size="small" icon={<ThunderboltOutlined />} onClick={authoring.format}>
              {t('editor.format_button')}{' '}
              <span className="kbd" style={{ marginLeft: 4 }}>⌘⇧F</span>
            </Button>
          )}
        </div>
        <div className="af-editor-body">
          {authoring.textToSqlSupported && (
            <TextToSqlBar datasourceId={ds.id} onGenerated={authoring.applyGenerated} />
          )}
          <SqlEditor
            value={sql}
            onChange={authoring.handleSqlChange}
            schema={schema}
            dbType={ds.db_type}
            syntax={authoring.effectiveSyntax}
            issues={authoring.hasFreshAnalysis ? authoring.analysis?.issues : undefined}
            height={editorHeight}
          />
          {footer}
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
        <div
          style={{
            padding: 8,
            borderBottom: '1px solid var(--border)',
            background: 'var(--bg-sunken)',
          }}
        >
          <Segmented<'ai' | 'plan'>
            size="small"
            block
            value={authoring.rightPanel}
            onChange={authoring.setRightPanel}
            aria-label={t('editor.panel_toggle_label')}
            options={[
              { label: t('editor.panel_ai'), value: 'ai' },
              { label: t('editor.panel_plan'), value: 'plan' },
            ]}
          />
        </div>
        {authoring.rightPanel === 'ai' ? (
          <AiHintPanel
            analyzing={authoring.analyzing}
            analysis={authoring.analysis}
            stale={authoring.analysisStale}
            aiEnabled={ds.ai_analysis_enabled}
            onApplySuggestion={authoring.applySuggestion}
            onReanalyze={authoring.canAnalyze ? authoring.analyze : undefined}
          />
        ) : (
          <DryRunPanel
            running={authoring.dryRunning}
            result={authoring.dryRunResult}
            stale={authoring.dryRunStale}
            onRun={authoring.canDryRun ? authoring.dryRun : undefined}
          />
        )}
      </div>
      <QueryTemplatesDrawer
        open={authoring.templatesOpen}
        onClose={authoring.closeTemplates}
        currentDatasourceId={ds.id}
        dbType={ds.db_type}
        onOpen={authoring.setPendingTemplate}
      />
      <SaveTemplateModal
        open={authoring.saveTemplateOpen}
        sql={sql}
        datasourceId={ds.id}
        onClose={authoring.closeSaveTemplate}
      />
      <LoadTemplateModal
        template={authoring.pendingTemplate}
        onCancel={() => authoring.setPendingTemplate(null)}
        onConfirm={authoring.applyTemplate}
      />
    </div>
  );
}
