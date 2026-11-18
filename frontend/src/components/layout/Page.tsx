import type { ReactNode } from 'react';
import './layout.css';

/** Root of every routed page: a full-height column holding the header, filters and body. */
export function Page({ children }: { children: ReactNode }) {
  return <div className="af-page">{children}</div>;
}

interface PageBodyProps {
  children: ReactNode;
  /** `full` for tables and dashboards, `reading` for detail pages, `form` for settings forms. */
  width?: 'full' | 'reading' | 'form';
  /** Edge-to-edge body for a table that supplies its own cell padding. */
  flush?: boolean;
}

/** The page's single scroll container, with the standard gutter and content width. */
export function PageBody({ children, width = 'full', flush = false }: PageBodyProps) {
  const classes = ['af-page-body', `af-page-body--${width}`, flush && 'af-page-body--flush']
    .filter(Boolean)
    .join(' ');
  return (
    <div className={classes}>
      <div className="af-page-body-inner">{children}</div>
    </div>
  );
}
