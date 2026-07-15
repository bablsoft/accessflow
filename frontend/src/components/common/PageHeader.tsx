import type { ReactNode } from 'react';
import { BookOutlined, RightOutlined } from '@ant-design/icons';
import { Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { docsUrl, type DocsAnchor } from '@/config/docs';
import './page-header.css';

interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  breadcrumbs?: ReactNode[];
  actions?: ReactNode;
  /** Deep-links to the matching section of the public docs site. */
  docsAnchor?: DocsAnchor;
}

export function PageHeader({ title, subtitle, breadcrumbs, actions, docsAnchor }: PageHeaderProps) {
  const { t } = useTranslation();
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
        {(docsAnchor || actions) && (
          <div className="af-page-header-aside">
            {docsAnchor && (
              <Typography.Link
                className="af-page-docs-link"
                href={docsUrl(docsAnchor)}
                target="_blank"
                rel="noopener noreferrer"
                aria-label={t('common.view_docs_aria')}
              >
                <BookOutlined aria-hidden />
                <span>{t('common.view_docs')}</span>
              </Typography.Link>
            )}
            {actions && <div className="af-page-actions">{actions}</div>}
          </div>
        )}
      </div>
    </div>
  );
}
