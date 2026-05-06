import type { ReactNode } from 'react';
import { InboxOutlined } from '@ant-design/icons';

interface EmptyStateProps {
  title: string;
  description?: string;
  icon?: ReactNode;
  action?: ReactNode;
}

export function EmptyState({ title, description, icon, action }: EmptyStateProps) {
  return (
    <div style={{ padding: '60px 24px', textAlign: 'center' }}>
      <div
        style={{
          width: 48,
          height: 48,
          margin: '0 auto 16px',
          borderRadius: 12,
          background: 'var(--bg-sunken)',
          border: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--fg-muted)',
        }}
      >
        {icon ?? <InboxOutlined style={{ fontSize: 20 }} />}
      </div>
      <div style={{ fontWeight: 600, marginBottom: 4 }}>{title}</div>
      {description && (
        <div className="muted" style={{ fontSize: 13, maxWidth: 360, margin: '0 auto' }}>
          {description}
        </div>
      )}
      {action && <div style={{ marginTop: 16 }}>{action}</div>}
    </div>
  );
}
