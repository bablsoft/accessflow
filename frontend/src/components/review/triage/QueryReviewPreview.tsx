import { Button } from 'antd';
import { ArrowRightOutlined, CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { RiskPill } from '@/components/common/RiskPill';
import { SqlBlock } from '@/components/common/SqlBlock';
import { ApprovalPredictionBadge } from '@/components/review/ApprovalPredictionBadge';
import { timeAgo } from '@/utils/dateFormat';
import type { PendingReviewItem } from '@/types/api';
import './triage.css';

interface QueryReviewPreviewProps {
  item: PendingReviewItem | null;
  onApprove: (id: string) => void;
  onReject: (id: string) => void;
}

/** The side panel of the query review queue: everything needed to decide without leaving the list. */
export function QueryReviewPreview({ item, onApprove, onReject }: QueryReviewPreviewProps) {
  const { t } = useTranslation();
  if (!item) {
    return (
      <EmptyState
        size="sm"
        title={t('reviews.preview.empty_title')}
        description={t('reviews.preview.empty_description')}
      />
    );
  }
  const ai = item.ai_analysis;
  const blocking = item.sql_review_blocking_count ?? 0;
  return (
    <section className="af-preview" aria-label={t('reviews.preview.title')}>
      <header className="af-preview-header">
        <span className="af-preview-id">{item.id.slice(0, 8)}</span>
        <QueryTypePill type={item.query_type} size="sm" />
        {ai && <RiskPill level={ai.risk_level} score={ai.risk_score} size="sm" />}
        {item.approval_probability != null && (
          <ApprovalPredictionBadge probability={item.approval_probability} size="sm" />
        )}
      </header>
      <div className="af-preview-body">
        <dl className="af-preview-meta">
          <dt>{t('reviews.col_submitter')}</dt>
          <dd>{item.submitted_by.email}</dd>
          <dt>{t('reviews.col_datasource')}</dt>
          <dd className="mono">{item.datasource.name}</dd>
          <dt>{t('reviews.col_created')}</dt>
          <dd>{timeAgo(item.created_at)}</dd>
          {blocking > 0 && (
            <>
              <dt>{t('reviews.preview.sql_review')}</dt>
              <dd>{t('reviews.sql_review_blocking_tooltip', { count: blocking })}</dd>
            </>
          )}
        </dl>
        <div>
          <h3 className="af-preview-label">{t('reviews.preview.sql')}</h3>
          <SqlBlock sql={item.sql_text} />
        </div>
        {item.justification && (
          <div>
            <h3 className="af-preview-label">{t('reviews.preview.justification')}</h3>
            <p className="af-preview-text">{item.justification}</p>
          </div>
        )}
        {ai?.summary && (
          <div>
            <h3 className="af-preview-label">{t('reviews.preview.ai_summary')}</h3>
            <p className="af-preview-text">{ai.summary}</p>
          </div>
        )}
      </div>
      <footer className="af-preview-footer">
        <Link to={`/queries/${item.id}`}>
          {t('reviews.preview.open_details')} <ArrowRightOutlined aria-hidden />
        </Link>
        <span className="af-preview-footer-spacer" />
        <Button danger icon={<CloseOutlined />} onClick={() => onReject(item.id)}>
          {t('common.reject')}
        </Button>
        <Button type="primary" icon={<CheckOutlined />} onClick={() => onApprove(item.id)}>
          {t('common.approve')}
        </Button>
      </footer>
    </section>
  );
}
