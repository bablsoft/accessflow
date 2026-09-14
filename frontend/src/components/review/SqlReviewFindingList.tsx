import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import { sqlReviewSeverityColor } from '@/utils/riskColors';
import { sqlReviewSeverityLabel } from '@/utils/enumLabels';
import type { SqlReviewFinding } from '@/types/api';

interface SqlReviewFindingListProps {
  findings: readonly SqlReviewFinding[];
  /** 'compact' is the editor strip; 'card' the reviewer detail card. */
  density?: 'compact' | 'card';
}

/**
 * One row per deterministic SQL review finding (#865): severity pill, the line it sits on (or the
 * statement inside a BEGIN … COMMIT envelope when the parser gave no position), and the message the
 * backend already rendered in the reader's locale. Shared by the editor strip and the reviewer
 * surfaces so the two never describe a finding differently.
 */
export function SqlReviewFindingList({ findings, density = 'card' }: SqlReviewFindingListProps) {
  const { t } = useTranslation();
  return (
    <ul
      style={{
        listStyle: 'none',
        margin: 0,
        padding: 0,
        display: 'flex',
        flexDirection: 'column',
        gap: density === 'compact' ? 4 : 8,
      }}
    >
      {findings.map((finding, index) => {
        const colors = sqlReviewSeverityColor(finding.severity);
        return (
          <li
            key={`${finding.rule_id}-${finding.statement_index}-${finding.line_number ?? 'x'}-${index}`}
            data-testid="sql-review-finding"
            data-severity={finding.severity}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 8,
              fontSize: density === 'compact' ? 12 : 13,
              lineHeight: 1.5,
            }}
          >
            <Pill fg={colors.fg} bg={colors.bg} border={colors.border} size="sm">
              {sqlReviewSeverityLabel(t, finding.severity)}
            </Pill>
            <span className="mono muted" style={{ fontSize: 11, flexShrink: 0, marginTop: 2 }}>
              {finding.line_number !== undefined
                ? t('editor.sql_review_line', { line: finding.line_number })
                : t('editor.sql_review_statement', { index: finding.statement_index + 1 })}
            </span>
            <span style={{ minWidth: 0 }}>{finding.message}</span>
          </li>
        );
      })}
    </ul>
  );
}
