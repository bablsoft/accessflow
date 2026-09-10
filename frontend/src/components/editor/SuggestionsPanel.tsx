import { Button, Empty, Spin, Tooltip } from 'antd';
import { BulbOutlined, WarningOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { fetchQuerySuggestions, querySuggestionKeys } from '@/api/querySuggestions';
import type { QuerySuggestion } from '@/types/api';
import { timeAgo } from '@/utils/dateFormat';
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
      style={{
        background: 'var(--bg-sunken)',
        overflow: 'auto',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <div
        style={{
          padding: '12px 16px',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <BulbOutlined style={{ color: 'var(--accent)' }} />
        <span style={{ fontWeight: 600, fontSize: 13 }}>{t('editor.suggestions.title')}</span>
      </div>
      <div style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 10 }}>
        <p style={{ margin: 0, fontSize: 11, color: 'var(--text-muted)' }}>
          {t('editor.suggestions.subtitle')}
        </p>
        {suggestionsQuery.isPending && (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 16 }}>
            <Spin size="small" />
          </div>
        )}
        {suggestionsQuery.isError && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              fontSize: 12,
              color: 'var(--risk-med)',
            }}
          >
            <WarningOutlined />
            {t('editor.suggestions.load_error')}
          </div>
        )}
        {suggestionsQuery.isSuccess && suggestionsQuery.data.length === 0 && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span style={{ fontSize: 12 }}>{t('editor.suggestions.empty')}</span>
            }
          />
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
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        padding: 10,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span className="mono" style={{ fontSize: 10, color: 'var(--text-muted)' }}>
          {queryTypeLabel(t, suggestion.query_type)}
        </span>
        <Tooltip title={suggestion.referenced_tables.join(', ')}>
          <span
            style={{
              fontSize: 10,
              color: 'var(--text-muted)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {suggestion.referenced_tables.join(', ')}
          </span>
        </Tooltip>
      </div>
      <pre
        className="mono"
        style={{
          margin: 0,
          fontSize: 11,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          maxHeight: 140,
          overflow: 'auto',
          color: 'var(--text)',
        }}
      >
        {suggestion.sql}
      </pre>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 10, color: 'var(--text-muted)' }}>
          {/* `times`, not `count` — a `count` option would put i18next into plural resolution
              and look for keys (evidence_one / evidence_other) that do not exist. */}
          {t('editor.suggestions.evidence', {
            times: suggestion.approved_count,
            people: suggestion.distinct_submitter_count,
            when: timeAgo(suggestion.last_submitted_at),
          })}
        </span>
        <Button
          size="small"
          type="primary"
          style={{ marginLeft: 'auto' }}
          onClick={() => onApply(suggestion.sql)}
        >
          {t('editor.suggestions.apply')}
        </Button>
      </div>
    </div>
  );
}
