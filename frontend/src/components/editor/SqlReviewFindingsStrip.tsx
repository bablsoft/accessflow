import { LoadingOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { SqlReviewFindingList } from '@/components/review/SqlReviewFindingList';
import type { SqlReviewLintState } from '@/hooks/useSqlReviewLint';
import { countWarningFindings } from '@/utils/sqlReview';

interface SqlReviewFindingsStripProps {
  state: SqlReviewLintState;
  /** Whether the editor holds a non-blank draft; the strip is silent on an empty editor. */
  hasSql: boolean;
}

/**
 * Live rule feedback under the editor (#865). Renders nothing for an engine the catalog does not
 * cover or an empty draft; a quiet hint while the draft does not parse; otherwise the counts and
 * the findings. A BLOCK never disables submission — it only tells the author a human will review.
 */
export function SqlReviewFindingsStrip({ state, hasSql }: SqlReviewFindingsStripProps) {
  const { t } = useTranslation();
  if (!state.supported || !hasSql) return null;

  const warningCount = countWarningFindings(state.findings);
  const hasFindings = state.findings.length > 0;
  if (!hasFindings && !state.unparseable && !state.evaluating) return null;

  return (
    <div
      data-testid="sql-review-strip"
      style={{
        marginTop: 8,
        padding: '8px 10px',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius)',
        background: 'var(--bg-sunken)',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
        <span style={{ fontWeight: 600 }}>{t('editor.sql_review_title')}</span>
        {state.evaluating && (
          <span className="muted" data-testid="sql-review-evaluating">
            <LoadingOutlined style={{ marginRight: 4 }} />
            {t('editor.sql_review_evaluating')}
          </span>
        )}
        {hasFindings && (
          <span className="muted">
            {state.blockingCount > 0 && (
              <span style={{ color: 'var(--risk-high)' }} data-testid="sql-review-blocking-count">
                {t('editor.sql_review_count_blocking', { count: state.blockingCount })}
              </span>
            )}
            {state.blockingCount > 0 && warningCount > 0 && ' · '}
            {warningCount > 0 && t('editor.sql_review_count_warning', { count: warningCount })}
          </span>
        )}
      </div>
      {state.unparseable && (
        <div className="muted" style={{ fontSize: 12 }} data-testid="sql-review-unparseable">
          {t('editor.sql_review_unparseable')}
        </div>
      )}
      {hasFindings && <SqlReviewFindingList findings={state.findings} density="compact" />}
      {state.blockingCount > 0 && (
        <div className="muted" style={{ fontSize: 11 }}>
          {t('editor.sql_review_blocking_note', { count: state.blockingCount })}
        </div>
      )}
    </div>
  );
}
