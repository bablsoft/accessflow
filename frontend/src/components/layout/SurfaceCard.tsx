import type { ReactNode } from 'react';
import './layout.css';

interface SurfaceCardProps {
  title?: ReactNode;
  icon?: ReactNode;
  /** Rendered at the end of the header row. */
  extra?: ReactNode;
  /** Adds the standard inner padding; leave off for tables and other full-bleed content. */
  padded?: boolean;
  children: ReactNode;
}

/** The app's one bordered surface. Use it instead of a hand-rolled box. */
export function SurfaceCard({ title, icon, extra, padded = false, children }: SurfaceCardProps) {
  const hasHeader = title !== undefined || icon !== undefined || extra !== undefined;
  return (
    <section className="af-surface">
      {hasHeader && (
        <div className="af-surface-header">
          {icon && <span className="af-surface-icon">{icon}</span>}
          {title !== undefined && <span className="af-surface-title">{title}</span>}
          {extra}
        </div>
      )}
      {padded ? <div className="af-surface-body--padded">{children}</div> : children}
    </section>
  );
}

interface SectionProps {
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
}

/** A titled group of fields on a settings form. */
export function Section({ title, description, children }: SectionProps) {
  return (
    <section className="af-section">
      <h2 className="af-section-title">{title}</h2>
      {description && <p className="af-section-description">{description}</p>}
      <div className="af-section-content">{children}</div>
    </section>
  );
}
