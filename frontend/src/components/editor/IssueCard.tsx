import { useState } from 'react';
import { DownOutlined, UpOutlined, WarningFilled } from '@ant-design/icons';
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
          <WarningFilled style={{ fontSize: 11 }} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
            <span
              className="mono"
              style={{ fontSize: 10, fontWeight: 600, color: c.fg, textTransform: 'uppercase' }}
            >
              {issue.severity}
            </span>
            <span className="mono muted" style={{ fontSize: 10 }}>
              · {issue.category}
            </span>
          </div>
          <div style={{ fontSize: 12, lineHeight: 1.4 }}>{issue.message}</div>
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
          }}
        >
          <div style={{ fontWeight: 500, color: 'var(--fg)', marginBottom: 4 }}>Suggestion</div>
          <div style={{ lineHeight: 1.5 }}>{issue.suggestion}</div>
        </div>
      )}
    </div>
  );
}
