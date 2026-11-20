import { Button, Tooltip } from 'antd';
import { BulbOutlined, WarningOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { fetchQuerySuggestions, querySuggestionKeys } from '@/api/querySuggestions';
import { apiErrorMessage } from '@/utils/apiErrors';
import type { QuerySuggestion } from '@/types/api';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { queryTypeLabel } from '@/utils/enumLabels';

interface SuggestionsPanelProps {
  datasourceId: string;
  onApply: (sql: string) => void;
}

/**
 * Right-rail panel offering draft queries mined from the organisation's own approved history
 * (#776). Everything the panel receives has already been filtered server-side to what this viewer
 * may run, so it renders the list as given — there is no client-side permission logic to get wrong.
 *
 * <p>Applying one only seeds the editor. The draft still goes through the normal submit → AI →
 * review pipeline, and the panel says so, so nobody reads the rail as pre-approval.
 */
export function SuggestionsPanel({ datasourceId, onApply }: SuggestionsPanelProps) {
  const { t } = useTranslation();
  const suggestionsQuery = useQuery({
    queryKey: querySuggestionKeys.list(datasourceId),
    queryFn: () => fetchQuerySuggestions(datasourceId),
  });

  return (
    <div
      className="u-flex u-col" style={{ background: 'var(--bg-sunken)', overflow: 'auto', minHeight: 0 }}
    >
      <div
        className="u-flex u-items-center u-gap-2" style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}
      >
        <BulbOutlined className="u-fg-accent" />
        <span className="u-fw-600 u-text-sm">{t('editor.suggestions.title')}</span>
      </div>
      <div className="u-p-3 u-flex u-col" style={{ gap: 10 }}>
        <p className="u-m-0 u-text-2xs u-fg-muted">
          {t('editor.suggestions.subtitle')}
        </p>
        {suggestionsQuery.isPending && (
          <>
            <div className="skeleton" style={{ height: 96, borderRadius: 'var(--radius-md)' }} />
            <div className="skeleton" style={{ height: 96, borderRadius: 'var(--radius-md)' }} />
          </>
        )}
        {suggestionsQuery.isError && (
          <div
            className="u-flex u-items-center u-text-xs" style={{ gap: 6, color: 'var(--risk-med)' }}
          >
            <WarningOutlined />
            {apiErrorMessage(suggestionsQuery.error, () => t('editor.suggestions.load_error'))}
          </div>
        )}
        {suggestionsQuery.isSuccess && suggestionsQuery.data.length === 0 && (
          <EmptyState size="sm" title={t('editor.suggestions.empty')} />
        )}
        {suggestionsQuery.data?.map((suggestion) => (
          <SuggestionCard key={suggestion.id} suggestion={suggestion} onApply={onApply} />
        ))}
      </div>
    </div>
  );
}

function SuggestionCard({
  suggestion,
  onApply,
}: {
  suggestion: QuerySuggestion;
  onApply: (sql: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="u-flex u-col u-gap-2" style={{ background: 'var(--bg-elev)', border: '1px solid var(--border)', borderRadius: 'var(--radius-md)', padding: 10 }}
    >
      <div className="u-flex u-items-center" style={{ gap: 6 }}>
        <span className="mono u-text-2xs u-fg-muted">
          {queryTypeLabel(t, suggestion.query_type)}
        </span>
        <Tooltip title={suggestion.referenced_tables.join(', ')}>
          <span
            className="u-text-2xs u-fg-muted u-min-w-0 u-nowrap" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}
          >
            {suggestion.referenced_tables.join(', ')}
          </span>
        </Tooltip>
      </div>
      <pre
        className="mono u-m-0 u-text-2xs u-fg"
        style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 140, overflow: 'auto' }}
      >
        {suggestion.sql}
      </pre>
      <div className="u-flex u-items-center u-gap-2">
        <span className="u-text-2xs u-fg-muted">
          {/* `times`, not `count`: a `count` option puts i18next into plural resolution, so the
              string would silently change meaning the day someone adds an `evidence_other` key —
              and `count` is auto-interpolated, which this sentence does not want. */}
          {t('editor.suggestions.evidence', {
            times: suggestion.approved_count,
            people: suggestion.distinct_submitter_count,
          })}
        </span>
        {/* Rendered outside the translated sentence: timeAgo() emits English ("3d ago"), which
            would read as a bug spliced into the middle of a localized string. */}
        <Tooltip title={fmtDate(suggestion.last_submitted_at)}>
          <span className="u-text-2xs u-fg-faint">
            {timeAgo(suggestion.last_submitted_at)}
          </span>
        </Tooltip>
        <Button
          size="small"
          type="primary"
          className="u-ml-auto"
          onClick={() => onApply(suggestion.sql)}
        >
          {t('editor.suggestions.apply')}
        </Button>
      </div>
    </div>
  );
}
