import { useEffect, useMemo, useState } from 'react';
import { App, Button, Input } from 'antd';
import {
  ThunderboltOutlined,
  PlayCircleOutlined,
  LoadingOutlined,
  FolderOpenOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { RiskPill } from '@/components/common/RiskPill';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { AiHintPanel } from '@/components/editor/AiHintPanel';
import { SchemaTree } from '@/components/editor/SchemaTree';
import { ReviewPlanPreview } from '@/components/editor/ReviewPlanPreview';
import { DATASOURCES } from '@/mocks/data';
import { buildMockSchema } from '@/mocks/schema';
import { mockAnalyze } from '@/mocks/analyzer';
import type { AiAnalysis } from '@/types/api';
import { submitQuery } from '@/api/queries';
import { formatSql } from '@/utils/sqlFormat';
import './editor.css';

const DEFAULT_SQL = `-- Customer support ticket #8821: order stuck in 'processing'
SELECT id, status, total_cents, customer_id, created_at
FROM orders
WHERE id = 88210
LIMIT 1;`;

export function QueryEditorPage() {
  const datasources = DATASOURCES.filter((d) => d.active);
  const [dsId, setDsId] = useState(datasources[0]!.id);
  const [sql, setSql] = useState(DEFAULT_SQL);
  const [justification, setJustification] = useState(
    'Customer support ticket #8821 — investigating order stuck in processing status.',
  );
  const [analyzing, setAnalyzing] = useState(false);
  const [analysis, setAnalysis] = useState<AiAnalysis | null>(null);
  const [submitState, setSubmitState] = useState<'idle' | 'submitting'>('idle');
  const ds = datasources.find((d) => d.id === dsId)!;
  const schema = useMemo(() => buildMockSchema(ds), [ds]);
  const { message } = App.useApp();
  const navigate = useNavigate();

  useEffect(() => {
    if (!sql.trim()) {
      setAnalysis(null);
      return;
    }
    setAnalyzing(true);
    const t = setTimeout(() => {
      setAnalysis(mockAnalyze(sql));
      setAnalyzing(false);
    }, 700);
    return () => clearTimeout(t);
  }, [sql]);

  const handleSubmit = async () => {
    setSubmitState('submitting');
    try {
      const created = await submitQuery({
        datasource_id: dsId,
        sql,
        justification,
      });
      message.success({
        content: `Query submitted · ${created.id}`,
        duration: 2.5,
      });
      navigate(`/queries/${created.id}`);
    } finally {
      setSubmitState('idle');
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title="SQL editor"
        subtitle="Submit a query for AI and human review."
        actions={
          <>
            <Button icon={<FolderOpenOutlined />} onClick={() => navigate('/queries')}>
              History
            </Button>
            <Button
              type="primary"
              icon={
                submitState === 'submitting' ? <LoadingOutlined /> : <PlayCircleOutlined />
              }
              disabled={submitState !== 'idle' || !sql.trim()}
              onClick={handleSubmit}
            >
              {submitState === 'submitting' ? 'Submitting' : 'Submit for review'}
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
              {(sql.match(/\n/g) ?? []).length + 1} lines
            </span>
            <span style={{ color: 'var(--fg-faint)' }}>·</span>
            <span className="mono muted" style={{ fontSize: 11 }}>
              {sql.length} chars
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
                <span className="spinner" /> AI analyzing…
              </span>
            ) : analysis ? (
              <RiskPill level={analysis.risk_level} score={analysis.risk_score} size="sm" />
            ) : null}
            <Button
              size="small"
              icon={<ThunderboltOutlined />}
              onClick={() => setSql(formatSql(sql, ds.db_type))}
            >
              Format <span className="kbd" style={{ marginLeft: 4 }}>⌘⇧F</span>
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
                Justification{' '}
                <span className="muted" style={{ fontWeight: 400 }}>
                  · required for review
                </span>
              </label>
              <Input.TextArea
                value={justification}
                onChange={(e) => setJustification(e.target.value)}
                placeholder="Why are you running this query?"
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

