import { useState } from 'react';
import { DownOutlined, UpOutlined, WarningFilled } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AiIssue } from '@/types/api';

const sevColor = (s: AiIssue['severity']) => {
  switch (s) {
    case 'CRITICAL':
      return { fg: 'var(--risk-crit)', bg: 'var(--risk-crit-bg)' };
    case 'HIGH':
      return { fg: 'var(--risk-high)', bg: 'var(--risk-high-bg)' };
    case 'MEDIUM':
      return { fg: 'var(--risk-med)', bg: 'var(--risk-med-bg)' };
    case 'LOW':
      return { fg: 'var(--risk-low)', bg: 'var(--risk-low-bg)' };
  }
};

export function IssueCard({ issue }: { issue: AiIssue }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const c = sevColor(issue.severity);
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
        className="u-flex u-items-start" style={{ padding: '10px 12px', gap: 10, cursor: 'pointer' }}
      >
        <div
          className="u-flex u-items-center u-justify-center" style={{ width: 20, height: 20, borderRadius: 4, background: c.bg, color: c.fg, flexShrink: 0, marginTop: 1 }}
        >
          <WarningFilled className="u-text-2xs" />
        </div>
        <div className="u-flex-1 u-min-w-0">
          <div className="u-flex u-items-center" style={{ gap: 6, marginBottom: 2 }}>
            <span
              className="mono u-text-2xs u-fw-600"
              style={{ color: c.fg, textTransform: 'uppercase' }}
            >
              {issue.severity}
            </span>
            <span className="mono muted u-text-2xs">
              · {issue.category}
            </span>
          </div>
          <div className="u-text-xs" style={{ lineHeight: 1.4 }}>{issue.message}</div>
        </div>
        {open ? (
          <UpOutlined className="u-text-2xs u-fg-faint u-mt-1" />
        ) : (
          <DownOutlined className="u-text-2xs u-fg-faint u-mt-1" />
        )}
      </div>
      {open && (
        <div
          className="u-text-xs u-fg-muted" style={{ padding: '10px 12px 12px 42px', borderTop: '1px solid var(--border)' }}
        >
          <div className="u-fw-500 u-fg u-mb-1">
            {t('ai_panel.issue_suggestion_label')}
          </div>
          <div className="u-leading-normal">{issue.suggestion}</div>
        </div>
      )}
    </div>
  );
}
