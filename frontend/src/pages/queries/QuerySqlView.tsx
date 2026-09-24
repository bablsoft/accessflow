import { useState } from 'react';
import { Segmented } from 'antd';
import { useTranslation } from 'react-i18next';
import { SqlBlock } from '@/components/common/SqlBlock';
import { SqlDiffView } from '@/components/editor/SqlDiffView';
import type { DbType } from '@/types/api';

type SqlView = 'submitted' | 'effective' | 'diff';

interface QuerySqlViewProps {
  sql: string;
  /** The statement as actually executed (#937); absent when no rewrite occurred. */
  effectiveSql?: string | null;
  dbType?: DbType;
}

/**
 * The query detail SQL card body. When the snapshot recorded an effective statement (row-security
 * predicates / soft-delete rewrite spliced in, bound values shown as `?`), offers the submitted
 * text, the effective text, and a side-by-side diff of the two.
 */
export function QuerySqlView({ sql, effectiveSql, dbType }: QuerySqlViewProps) {
  const { t } = useTranslation();
  const [view, setView] = useState<SqlView>('submitted');

  if (!effectiveSql) {
    return <SqlBlock sql={sql} />;
  }

  return (
    <div data-testid="query-effective-sql">
      <Segmented<SqlView>
        size="small"
        value={view}
        onChange={setView}
        aria-label={t('queries.detail.effective_sql.view_aria')}
        options={[
          { value: 'submitted', label: t('queries.detail.effective_sql.view_submitted') },
          { value: 'effective', label: t('queries.detail.effective_sql.view_effective') },
          { value: 'diff', label: t('queries.detail.effective_sql.view_diff') },
        ]}
      />
      <div className="muted" style={{ fontSize: 12, margin: '8px 0' }}>
        {t('queries.detail.effective_sql.hint')}
      </div>
      {view === 'submitted' && <SqlBlock sql={sql} />}
      {view === 'effective' && <SqlBlock sql={effectiveSql} />}
      {view === 'diff' && (
        <SqlDiffView
          oldValue={sql}
          newValue={effectiveSql}
          dbType={dbType}
          height={260}
          oldLabel={t('queries.detail.effective_sql.diff_submitted_label')}
          newLabel={t('queries.detail.effective_sql.diff_effective_label')}
        />
      )}
    </div>
  );
}
