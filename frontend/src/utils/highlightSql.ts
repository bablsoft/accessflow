export type SqlTokenKind = 'kw' | 'string' | 'num' | 'comment' | 'fn' | 'op' | 'id' | 'ws';
export interface SqlToken {
  kind: SqlTokenKind;
  value: string;
}

const KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'INSERT', 'INTO', 'VALUES',
  'UPDATE', 'SET', 'DELETE', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'ON',
  'GROUP', 'BY', 'ORDER', 'LIMIT', 'OFFSET', 'HAVING', 'AS', 'DISTINCT',
  'CREATE', 'ALTER', 'DROP', 'TABLE', 'INDEX', 'ADD', 'COLUMN', 'RENAME',
  'TRUNCATE', 'UNION', 'ALL', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'IS',
  'NULL', 'TRUE', 'FALSE', 'LIKE', 'IN', 'BETWEEN', 'EXISTS', 'WITH',
  'RETURNING', 'INTERVAL', 'NOW', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
]);

export function highlightSql(text: string): SqlToken[] {
  const out: SqlToken[] = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i]!;
    if (/\s/.test(c)) {
      out.push({ kind: 'ws', value: c });
      i++;
      continue;
    }
    if (c === '-' && text[i + 1] === '-') {
      const end = text.indexOf('\n', i);
      out.push({ kind: 'comment', value: text.slice(i, end === -1 ? text.length : end) });
      i = end === -1 ? text.length : end;
      continue;
    }
    if (c === "'") {
      let j = i + 1;
      while (j < text.length && text[j] !== "'") j++;
      out.push({ kind: 'string', value: text.slice(i, j + 1) });
      i = j + 1;
      continue;
    }
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < text.length && /[0-9.]/.test(text[j]!)) j++;
      out.push({ kind: 'num', value: text.slice(i, j) });
      i = j;
      continue;
    }
    if (/[a-zA-Z_]/.test(c)) {
      let j = i;
      while (j < text.length && /[a-zA-Z0-9_.]/.test(text[j]!)) j++;
      const word = text.slice(i, j);
      const isKw = KEYWORDS.has(word.toUpperCase());
      const next = text.slice(j).match(/^\s*\(/);
      out.push({ kind: isKw ? 'kw' : next ? 'fn' : 'id', value: word });
      i = j;
      continue;
    }
    out.push({ kind: 'op', value: c });
    i++;
  }
  return out;
}

export const sqlTokenColor = (k: SqlTokenKind): string => {
  switch (k) {
    case 'kw': return 'var(--sql-keyword)';
    case 'string': return 'var(--sql-string)';
    case 'num': return 'var(--sql-number)';
    case 'comment': return 'var(--sql-comment)';
    case 'fn': return 'var(--sql-fn)';
    case 'op': return 'var(--sql-op)';
    default: return 'inherit';
  }
};
