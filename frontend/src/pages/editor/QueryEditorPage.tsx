import { useMemo, useState } from 'react';
import { App, Button, Input, Tooltip } from 'antd';
import {
  ThunderboltOutlined,
  PlayCircleOutlined,
  LoadingOutlined,
  FolderOpenOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { RiskPill } from '@/components/common/RiskPill';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { AiHintPanel } from '@/components/editor/AiHintPanel';
import { SchemaTree } from '@/components/editor/SchemaTree';
import { ReviewPlanPreview } from '@/components/editor/ReviewPlanPreview';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { useSchemaIntrospect } from '@/hooks/useSchemaIntrospect';
import { analyzeOnly, queryKeys, submitQuery } from '@/api/queries';
import { formatSql } from '@/utils/sqlFormat';
import './editor.css';

const EMPTY_SCHEMA = { schemas: [] };

export function QueryEditorPage() {
  const { t } = useTranslation();
  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ size: 100 }),
    queryFn: () => listDatasources({ size: 100 }),
  });
  const datasources = useMemo(
    () => (datasourcesQuery.data?.content ?? []).filter((d) => d.active),
    [datasourcesQuery.data],
  );
  const [selectedDsId, setSelectedDsId] = useState<string | null>(null);
  const dsId = selectedDsId ?? datasources[0]?.id ?? null;
  const ds = datasources.find((d) => d.id === dsId) ?? null;
  const [sql, setSql] = useState('');
  const [justification, setJustification] = useState('');
  const schemaQuery = useSchemaIntrospect(ds?.id);
  const schema = schemaQuery.data ?? EMPTY_SCHEMA;
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const analyzeMutation = useMutation({
    mutationFn: () => analyzeOnly({ datasource_id: ds!.id, sql: sql.trim() }),
    onError: () => {
      message.error(t('editor.analyze_error'));
    },
  });

  const handleSqlChange = (next: string) => {
    setSql(next);
    if (analyzeMutation.data || analyzeMutation.isError) {
      analyzeMutation.reset();
    }
  };

  const submitMutation = useMutation({
    mutationFn: () =>
      submitQuery({
        datasource_id: ds!.id,
        sql,
        justification,
      }),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.lists() });
      message.success({
        content: t('editor.submit_success', { id: created.id }),
        duration: 2.5,
      });
      navigate(`/queries/${created.id}`);
    },
    onError: () => {
      message.error(t('editor.submit_error'));
    },
  });

  const analyzing = analyzeMutation.isPending;
  const analysis = analyzeMutation.data ?? null;

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

  const aiSupported = ds.ai_analysis_enabled && !!ds.ai_config_id;
  const sqlNonEmpty = sql.trim().length > 0;
  const hasFreshAnalysis = !!analyzeMutation.data;
  const canAnalyze = aiSupported && sqlNonEmpty && !analyzeMutation.isPending;
  const submitGatedByAnalysis = aiSupported && !hasFreshAnalysis;
  const canSubmit = sqlNonEmpty && !submitMutation.isPending && !submitGatedByAnalysis;

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
            {aiSupported && (
              <Button
                icon={analyzeMutation.isPending ? <LoadingOutlined /> : <ThunderboltOutlined />}
                disabled={!canAnalyze}
                onClick={() => analyzeMutation.mutate()}
              >
                {analyzeMutation.isPending
                  ? t('editor.analyzing_button')
                  : t('editor.analyze_button')}
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
            <Button
              size="small"
              icon={<ThunderboltOutlined />}
              onClick={() => handleSqlChange(formatSql(sql, ds.db_type))}
            >
              {t('editor.format_button')} <span className="kbd" style={{ marginLeft: 4 }}>⌘⇧F</span>
            </Button>
          </div>
          <div className="af-editor-body">
            <SqlEditor
              value={sql}
              onChange={handleSqlChange}
              schema={schema}
              dbType={ds.db_type}
              issues={analysis?.issues}
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
            <ReviewPlanPreview ds={ds} />
          </div>
        </div>
        <AiHintPanel analyzing={analyzing} analysis={analysis} aiEnabled={ds.ai_analysis_enabled} />
      </div>
    </div>
  );
}
