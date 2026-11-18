import type { ReactNode } from 'react';
import { InboxOutlined } from '@ant-design/icons';
import './common.css';

interface EmptyStateProps {
  title: string;
  description?: string;
  icon?: ReactNode;
  action?: ReactNode;
  /** `sm` is the compact variant for dashboard widgets and other dense hosts. */
  size?: 'md' | 'sm';
}

export function EmptyState({ title, description, icon, action, size = 'md' }: EmptyStateProps) {
  return (
    <div className={size === 'sm' ? 'af-empty af-empty--sm' : 'af-empty'}>
      <div className="af-empty-icon" aria-hidden>
        {icon ?? <InboxOutlined />}
      </div>
      <div className="af-empty-title">{title}</div>
      {description && <div className="af-empty-description">{description}</div>}
      {action && <div className="af-empty-action">{action}</div>}
    </div>
  );
}
