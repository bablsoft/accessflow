import type { ReactNode } from 'react';
import { Button } from 'antd';
import { ArrowRightOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import './triage.css';

export interface ReviewPreviewContent {
  id: string;
  /** Short heading, e.g. the first eight characters of the id or a version. */
  heading: ReactNode;
  /** Pills shown beside the heading. */
  badges?: ReactNode;
  meta: { label: string; value: ReactNode }[];
  /** Titled blocks under the meta list; a section whose content is empty is left out. */
  sections: { label: string; content: ReactNode }[];
  detailsHref: string;
  /** Hides the decision buttons, e.g. on the reviewer's own submission. */
  decidable?: boolean;
}

interface ReviewPreviewProps {
  content: ReviewPreviewContent | null;
  onApprove: (id: string) => void;
  onReject: (id: string) => void;
}

/** The side panel of a review queue: enough of the selected request to decide without leaving the list. */
export function ReviewPreview({ content, onApprove, onReject }: ReviewPreviewProps) {
  const { t } = useTranslation();
  if (!content) {
    return (
      <EmptyState
        size="sm"
        title={t('reviews.preview.empty_title')}
        description={t('reviews.preview.empty_description')}
      />
    );
  }
  const sections = content.sections.filter(
    (s) => s.content !== null && s.content !== undefined && s.content !== '',
  );
  return (
    <section className="af-preview" aria-label={t('reviews.preview.title')}>
      <header className="af-preview-header">
        <span className="af-preview-id">{content.heading}</span>
        {content.badges}
      </header>
      <div className="af-preview-body">
        <dl className="af-preview-meta">
          {content.meta.map((row) => (
            <div key={row.label} className="af-preview-meta-row">
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
        {sections.map((s) => (
          <div key={s.label}>
            <h3 className="af-preview-label">{s.label}</h3>
            {typeof s.content === 'string' ? (
              <p className="af-preview-text">{s.content}</p>
            ) : (
              s.content
            )}
          </div>
        ))}
      </div>
      <footer className="af-preview-footer">
        <Link to={content.detailsHref}>
          {t('reviews.preview.open_details')} <ArrowRightOutlined aria-hidden />
        </Link>
        <span className="af-preview-footer-spacer" />
        {content.decidable !== false && (
          <>
            <Button danger icon={<CloseOutlined />} onClick={() => onReject(content.id)}>
              {t('common.reject')}
            </Button>
            <Button type="primary" icon={<CheckOutlined />} onClick={() => onApprove(content.id)}>
              {t('common.approve')}
            </Button>
          </>
        )}
      </footer>
    </section>
  );
}
