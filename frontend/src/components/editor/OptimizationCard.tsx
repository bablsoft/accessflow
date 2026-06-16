import { useState } from 'react';
import { Button } from 'antd';
import { DownOutlined, UpOutlined, BulbOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { OptimizationSuggestion } from '@/types/api';
import { optimizationTypeLabel } from '@/utils/enumLabels';

const typeColor = (t: OptimizationSuggestion['type']) => {
  switch (t) {
    case 'INDEX':
      return { fg: 'var(--accent)', bg: 'var(--bg-elev)' };
    case 'REWRITE':
      return { fg: 'var(--risk-med)', bg: 'var(--risk-med-bg)' };
  }
};

interface OptimizationCardProps {
  optimization: OptimizationSuggestion;
  onApply?: (sql: string) => void;
}

export function OptimizationCard({ optimization, onApply }: OptimizationCardProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const c = typeColor(optimization.type);
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        overflow: 'hidden',
      }}
    >
      <div
        onClick={() => setOpen(!open)}
        style={{
          padding: '10px 12px',
          display: 'flex',
          alignItems: 'flex-start',
          gap: 10,
          cursor: 'pointer',
        }}
      >
        <div
          style={{
            width: 20,
            height: 20,
            borderRadius: 4,
            background: c.bg,
            color: c.fg,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
            marginTop: 1,
          }}
        >
          <BulbOutlined style={{ fontSize: 11 }} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
            <span
              className="mono"
              style={{ fontSize: 10, fontWeight: 600, color: c.fg, textTransform: 'uppercase' }}
            >
              {optimizationTypeLabel(t, optimization.type)}
            </span>
          </div>
          <div style={{ fontSize: 12, lineHeight: 1.4 }}>{optimization.title}</div>
        </div>
        {open ? (
          <UpOutlined style={{ fontSize: 11, color: 'var(--fg-faint)', marginTop: 4 }} />
        ) : (
          <DownOutlined style={{ fontSize: 11, color: 'var(--fg-faint)', marginTop: 4 }} />
        )}
      </div>
      {open && (
        <div
          style={{
            padding: '10px 12px 12px 42px',
            fontSize: 12,
            color: 'var(--fg-muted)',
            borderTop: '1px solid var(--border)',
            display: 'flex',
            flexDirection: 'column',
            gap: 10,
          }}
        >
          <div>
            <div style={{ fontWeight: 500, color: 'var(--fg)', marginBottom: 4 }}>
              {t('ai_panel.optimization_rationale_label')}
            </div>
            <div style={{ lineHeight: 1.5 }}>{optimization.rationale}</div>
          </div>
          <div>
            <div style={{ fontWeight: 500, color: 'var(--fg)', marginBottom: 4 }}>
              {t('ai_panel.optimization_sql_label')}
            </div>
            <pre
              className="mono"
              style={{
                margin: 0,
                padding: 8,
                background: 'var(--bg-sunken)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-sm)',
                fontSize: 11.5,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              <code>{optimization.sql}</code>
            </pre>
          </div>
          {onApply && (
            <div>
              <Button size="small" type="primary" onClick={() => onApply(optimization.sql)}>
                {t('ai_panel.optimization_apply')}
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
