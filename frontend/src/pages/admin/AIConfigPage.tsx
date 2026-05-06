import { useState } from 'react';
import { Button, Form, Input, Switch } from 'antd';
import { CheckOutlined, LoadingOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { PageHeader } from '@/components/common/PageHeader';

const PROVIDERS = [
  { id: 'OPENAI', label: 'OpenAI', desc: 'GPT-4o, GPT-4o-mini', model: 'gpt-4o' },
  { id: 'ANTHROPIC', label: 'Anthropic', desc: 'Claude Sonnet, Haiku', model: 'claude-sonnet-4-20250514' },
  { id: 'OLLAMA', label: 'Ollama', desc: 'Self-hosted local models', model: 'llama3.1:70b' },
] as const;

export function AIConfigPage() {
  const [provider, setProvider] = useState<(typeof PROVIDERS)[number]['id']>('ANTHROPIC');
  const [model, setModel] = useState('claude-sonnet-4-20250514');
  const [testing, setTesting] = useState<'idle' | 'running' | 'ok'>('idle');

  const onTest = () => {
    setTesting('running');
    setTimeout(() => setTesting('ok'), 800);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title="AI configuration"
        subtitle="Configure the AI backend used for query risk analysis."
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28, maxWidth: 760 }}>
        <Section title="Provider">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
            {PROVIDERS.map((p) => (
              <button
                key={p.id}
                onClick={() => {
                  setProvider(p.id);
                  setModel(p.model);
                }}
                style={{
                  padding: 14,
                  textAlign: 'left',
                  background: provider === p.id ? 'var(--accent-bg)' : 'var(--bg-elev)',
                  border: `1px solid ${provider === p.id ? 'var(--accent)' : 'var(--border)'}`,
                  borderRadius: 8,
                  cursor: 'pointer',
                  color: 'var(--fg)',
                }}
              >
                <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 4 }}>{p.label}</div>
                <div className="muted" style={{ fontSize: 11 }}>{p.desc}</div>
              </button>
            ))}
          </div>
        </Section>
        <Section title="Connection">
          <Grid>
            <Form.Item label="Model">
              <Input className="mono" value={model} onChange={(e) => setModel(e.target.value)} />
            </Form.Item>
            <Form.Item label="API endpoint">
              <Input
                className="mono"
                defaultValue={
                  provider === 'OLLAMA'
                    ? 'http://ollama:11434/api'
                    : provider === 'ANTHROPIC'
                      ? 'https://api.anthropic.com/v1'
                      : 'https://api.openai.com/v1'
                }
              />
            </Form.Item>
            <Form.Item label="API key">
              <Input.Password className="mono" defaultValue="sk-ant-••••••••••••••••••••" />
            </Form.Item>
            <Form.Item label="Timeout (ms)">
              <Input className="mono" defaultValue="30000" />
            </Form.Item>
            <Form.Item label="Max prompt tokens">
              <Input className="mono" defaultValue="8000" />
            </Form.Item>
            <Form.Item label="Max completion tokens">
              <Input className="mono" defaultValue="2000" />
            </Form.Item>
          </Grid>
        </Section>
        <Section title="Behavior">
          <Grid>
            <Form.Item label="Enable AI for new datasources by default" valuePropName="checked" initialValue>
              <Switch defaultChecked />
            </Form.Item>
            <Form.Item label="Auto-approve LOW risk SELECT queries" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item label="Block CRITICAL risk queries from execution" valuePropName="checked">
              <Switch defaultChecked />
            </Form.Item>
            <Form.Item label="Include schema in AI context" valuePropName="checked">
              <Switch defaultChecked />
            </Form.Item>
          </Grid>
        </Section>
        <Section title="Cost & usage (last 30 days)">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
            <Stat label="analyses" value="2,847" />
            <Stat label="prompt tokens" value="1.2M" />
            <Stat label="completion tokens" value="412K" />
            <Stat label="est. cost" value="$48.72" />
          </div>
        </Section>
        <div
          style={{
            display: 'flex',
            gap: 8,
            paddingTop: 16,
            borderTop: '1px solid var(--border)',
          }}
        >
          <Button type="primary" icon={<CheckOutlined />}>Save</Button>
          <Button
            icon={
              testing === 'running' ? (
                <LoadingOutlined />
              ) : testing === 'ok' ? (
                <CheckOutlined style={{ color: 'var(--risk-low)' }} />
              ) : (
                <PlayCircleOutlined />
              )
            }
            onClick={onTest}
          >
            {testing === 'ok' ? 'Test passed' : 'Send test prompt'}
          </Button>
        </div>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 32 }}>
      <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 14 }}>{title}</div>
      {children}
    </div>
  );
}
function Grid({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>{children}</div>
  );
}
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div
        className="muted mono"
        style={{
          fontSize: 10,
          textTransform: 'uppercase',
          letterSpacing: '0.04em',
          marginBottom: 4,
        }}
      >
        {label}
      </div>
      <div style={{ fontSize: 18, fontWeight: 600, fontFamily: 'var(--font-mono)' }}>{value}</div>
    </div>
  );
}
