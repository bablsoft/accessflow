import { useMemo } from 'react';
import { sql, PostgreSQL, MySQL, StandardSQL } from '@codemirror/lang-sql';
import type { DbType } from '@/types/api';
import { MergeDiffView } from './MergeDiffView';

interface SqlDiffViewProps {
  /** Left pane — the older / base version. */
  oldValue: string;
  /** Right pane — the newer / compared version. */
  newValue: string;
  dbType?: DbType;
  height?: number;
  /** Accessible labels for each read-only pane. */
  oldLabel?: string;
  newLabel?: string;
}

function dialectFor(dbType: DbType | undefined) {
  if (dbType === 'POSTGRESQL') return PostgreSQL;
  if (dbType === 'MYSQL' || dbType === 'MARIADB') return MySQL;
  return StandardSQL;
}

export function SqlDiffView({
  oldValue,
  newValue,
  dbType = 'POSTGRESQL',
  height = 360,
  oldLabel,
  newLabel,
}: SqlDiffViewProps) {
  const extensions = useMemo(() => [sql({ dialect: dialectFor(dbType) })], [dbType]);
  return (
    <MergeDiffView
      oldValue={oldValue}
      newValue={newValue}
      extensions={extensions}
      height={height}
      oldLabel={oldLabel}
      newLabel={newLabel}
      testId="sql-diff-view"
    />
  );
}
