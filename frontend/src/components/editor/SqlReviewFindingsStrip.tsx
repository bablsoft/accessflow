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
 * cover, an empty draft, or a clean one; a quiet hint while the draft does not parse; otherwise
 * the counts and the findings. A BLOCK never disables submission — it only tells the author a
 * human will review.
 */
export function SqlReviewFindingsStrip({ state, hasSql }: SqlReviewFindingsStripProps) {
  const { t } = useTranslation();
  if (!state.supported || !hasSql) return null;

  const warningCount = countWarningFindings(state.findings);
  const hasFindings = state.findings.length > 0;
  // Mount only when there is something to show — a clean draft must not flash a "Checking…"
  // box on every typing pause and shove the footer around. The indicator renders inside an
  // already-visible strip while the previous findings await replacement.
  if (!hasFindings && !state.unparseable) return null;

  return (
    <div
      data-testid="sql-review-strip"
      className="u-mt-2 u-flex u-col" style={{ padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 'var(--radius)', background: 'var(--bg-sunken)', gap: 6 }}
    >
      <div className="u-flex u-items-center u-gap-2 u-text-xs">
        <span className="u-fw-600">{t('editor.sql_review_title')}</span>
        {state.evaluating && (
          <span className="muted" data-testid="sql-review-evaluating">
            <LoadingOutlined className="u-mr-1" />
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
        <div className="muted u-text-xs" data-testid="sql-review-unparseable">
          {t('editor.sql_review_unparseable')}
        </div>
      )}
      {hasFindings && <SqlReviewFindingList findings={state.findings} density="compact" />}
      {state.blockingCount > 0 && (
        <div className="muted u-text-2xs">
          {t('editor.sql_review_blocking_note', { count: state.blockingCount })}
        </div>
      )}
    </div>
  );
}
