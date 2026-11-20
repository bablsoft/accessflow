import type { CSSProperties } from 'react';
import { highlightSql, sqlTokenColor } from '@/utils/highlightSql';

export function SqlBlock({ sql, style }: { sql: string; style?: CSSProperties }) {
  const tokens = highlightSql(sql);
  return (
    <pre
      className="u-m-0 u-mono u-text-sm u-bg-code u-rounded u-border u-overflow-auto u-pre-wrap u-break-word u-p-4 u-leading-normal" style={{ ...style }}
    >
      {tokens.map((tk, i) => (
        <span
          key={i}
          style={{
            color: sqlTokenColor(tk.kind),
            fontWeight: tk.kind === 'kw' ? 600 : 400,
          }}
        >
          {tk.value}
        </span>
      ))}
    </pre>
  );
}
