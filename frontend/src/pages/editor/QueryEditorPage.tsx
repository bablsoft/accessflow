import { useMemo, useState } from 'react';
import { App, Button, Input } from 'antd';
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
import { DATASOURCES } from '@/mocks/data';
import { buildMockSchema } from '@/mocks/schema';
import { analyzeOnly, queryKeys, submitQuery } from '@/api/queries';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { formatSql } from '@/utils/sqlFormat';
import './editor.css';

const DEFAULT_SQL = `-- Customer support ticket #8821: order stuck in 'processing'
SELECT id, status, total_cents, customer_id, created_at
FROM orders
WHERE id = 88210
LIMIT 1;`;

const ANALYZE_DEBOUNCE_MS = 700;

export function QueryEditorPage() {
  const { t } = useTranslation();
  const datasources = DATASOURCES.filter((d) => d.active);
  const [dsId, setDsId] = useState(datasources[0]!.id);
  const [sql, setSql] = useState(DEFAULT_SQL);
  const [justification, setJustification] = useState(
    'Customer support ticket #8821 — investigating order stuck in processing status.',
  );
  const ds = datasources.find((d) => d.id === dsId)!;
  const schema = useMemo(() => buildMockSchema(ds), [ds]);
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const debouncedSql = useDebouncedValue(sql, ANALYZE_DEBOUNCE_MS);
  const trimmedSql = debouncedSql.trim();
  const enableAnalysis = trimmedSql.length > 0 && ds.ai_enabled;

  const analysisQuery = useQuery({
    queryKey: ['queries', 'analyze', dsId, trimmedSql],
    queryFn: () => analyzeOnly({ datasource_id: dsId, sql: debouncedSql }),
    enabled: enableAnalysis,
    staleTime: 60_000,
    retry: false,
  });

  const submitMutation = useMutation({
    mutationFn: () =>
      submitQuery({
        datasource_id: dsId,
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

  const analyzing = analysisQuery.isFetching;
  const analysis = analysisQuery.data ?? null;
  const submitState = submitMutation.isPending ? 'submitting' : 'idle';

  const lineCount = (sql.match(/\n/g) ?? []).length + 1;

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
            <Button
              type="primary"
              icon={
                submitState === 'submitting' ? <LoadingOutlined /> : <PlayCircleOutlined />
              }
              disabled={submitState !== 'idle' || !sql.trim()}
              onClick={() => submitMutation.mutate()}
            >
              {submitState === 'submitting' ? t('editor.submitting_button') : t('editor.submit_button')}
            </Button>
          </>
        }
      />
      <div className="af-editor-grid">
        <SchemaTree
          ds={ds}
          schema={schema}
          datasources={datasources}
          onChangeDs={setDsId}
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
            {analyzing ? (
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  color: 'var(--accent)',
                  fontSize: 11,
                }}
              >
                <span className="spinner" /> {t('editor.ai_analyzing')}
              </span>
            ) : analysis ? (
              <RiskPill level={analysis.risk_level} score={analysis.risk_score} size="sm" />
            ) : null}
            <Button
              size="small"
              icon={<ThunderboltOutlined />}
              onClick={() => setSql(formatSql(sql, ds.db_type))}
            >
              {t('editor.format_button')} <span className="kbd" style={{ marginLeft: 4 }}>⌘⇧F</span>
            </Button>
          </div>
          <div className="af-editor-body">
            <SqlEditor
              value={sql}
              onChange={setSql}
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
            <ReviewPlanPreview ds={ds} analysis={analysis} />
          </div>
        </div>
        <AiHintPanel analyzing={analyzing} analysis={analysis} aiEnabled={ds.ai_enabled} />
      </div>
    </div>
  );
}
