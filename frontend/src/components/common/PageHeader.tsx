import type { ReactNode } from 'react';
import { RightOutlined } from '@ant-design/icons';
import './page-header.css';

interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  breadcrumbs?: ReactNode[];
  actions?: ReactNode;
}

export function PageHeader({ title, subtitle, breadcrumbs, actions }: PageHeaderProps) {
  return (
    <div className="af-page-header">
      {breadcrumbs && breadcrumbs.length > 0 && (
        <div className="af-page-breadcrumbs">
          {breadcrumbs.map((b, i) => (
            <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              {i > 0 && <RightOutlined style={{ fontSize: 9, color: 'var(--fg-faint)' }} />}
              <span className={i === breadcrumbs.length - 1 ? '' : 'muted'}>{b}</span>
            </span>
          ))}
        </div>
      )}
      <div className="af-page-header-row">
        <div style={{ minWidth: 0 }}>
          <h1 className="af-page-title">{title}</h1>
          {subtitle && <div className="muted af-page-subtitle">{subtitle}</div>}
        </div>
        {actions && <div className="af-page-actions">{actions}</div>}
      </div>
    </div>
  );
}
