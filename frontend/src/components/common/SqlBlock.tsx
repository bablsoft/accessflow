import type { CSSProperties } from 'react';
import { highlightSql, sqlTokenColor } from '@/utils/highlightSql';

export function SqlBlock({ sql, style }: { sql: string; style?: CSSProperties }) {
  const tokens = highlightSql(sql);
  return (
    <pre
      style={{
        margin: 0,
        padding: 14,
        background: 'var(--bg-code)',
        borderRadius: 'var(--radius)',
        border: '1px solid var(--border)',
        fontFamily: 'var(--font-mono)',
        fontSize: 12.5,
        lineHeight: 1.6,
        overflow: 'auto',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        ...style,
      }}
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
