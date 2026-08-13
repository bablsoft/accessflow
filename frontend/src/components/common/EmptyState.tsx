import type { ReactNode } from 'react';
import { InboxOutlined } from '@ant-design/icons';

interface EmptyStateProps {
  title: string;
  description?: string;
  icon?: ReactNode;
  action?: ReactNode;
  /** `sm` is the compact variant for dashboard widgets and other dense hosts. */
  size?: 'md' | 'sm';
}

export function EmptyState({ title, description, icon, action, size = 'md' }: EmptyStateProps) {
  const sm = size === 'sm';
  return (
    <div style={{ padding: sm ? '20px 16px' : '60px 24px', textAlign: 'center' }}>
      <div
        style={{
          width: sm ? 36 : 48,
          height: sm ? 36 : 48,
          margin: sm ? '0 auto 10px' : '0 auto 16px',
          borderRadius: sm ? 10 : 12,
          background: 'var(--bg-sunken)',
          border: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--fg-muted)',
        }}
      >
        {icon ?? <InboxOutlined style={{ fontSize: sm ? 16 : 20 }} />}
      </div>
      <div style={{ fontWeight: 600, marginBottom: 4, fontSize: sm ? 13 : undefined }}>{title}</div>
      {description && (
        <div className="muted" style={{ fontSize: sm ? 12 : 13, maxWidth: 360, margin: '0 auto' }}>
          {description}
        </div>
      )}
      {action && <div style={{ marginTop: sm ? 10 : 16 }}>{action}</div>}
    </div>
  );
}
