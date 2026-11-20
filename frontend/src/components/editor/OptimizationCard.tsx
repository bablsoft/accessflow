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
      className="af-box u-overflow-hidden"
    >
      <div
        onClick={() => setOpen(!open)}
        className="u-flex u-items-start u-pointer" style={{ padding: '10px 12px', gap: 10 }}
      >
        <div
          className="u-flex u-items-center u-justify-center u-rounded-sm u-shrink-0" style={{ width: 20, height: 20, background: c.bg, color: c.fg, marginTop: 1 }}
        >
          <BulbOutlined className="u-text-2xs" />
        </div>
        <div className="u-flex-1 u-min-w-0">
          <div className="u-flex u-items-center" style={{ gap: 6, marginBottom: 2 }}>
            <span
              className="mono u-text-2xs u-fw-600 u-uppercase"
              style={{ color: c.fg }}
            >
              {optimizationTypeLabel(t, optimization.type)}
            </span>
          </div>
          <div className="u-text-xs" style={{ lineHeight: 1.4 }}>{optimization.title}</div>
        </div>
        {open ? (
          <UpOutlined className="u-text-2xs u-fg-faint u-mt-1" />
        ) : (
          <DownOutlined className="u-text-2xs u-fg-faint u-mt-1" />
        )}
      </div>
      {open && (
        <div
          className="u-text-xs u-fg-muted u-flex u-col u-border-t" style={{ padding: '10px 12px 12px 42px', gap: 10 }}
        >
          <div>
            <div className="u-fw-500 u-fg u-mb-1">
              {t('ai_panel.optimization_rationale_label')}
            </div>
            <div className="u-leading-normal">{optimization.rationale}</div>
          </div>
          <div>
            <div className="u-fw-500 u-fg u-mb-1">
              {t('ai_panel.optimization_sql_label')}
            </div>
            <pre
              className="mono u-m-0 u-p-2 u-text-xs u-bg-sunken u-border u-rounded-sm u-pre-wrap u-break-word"
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
