import type { AiAnalysis, AiIssue, RiskLevel } from '@/types/api';

const findLine = (sql: string, re: RegExp): number => {
  const lines = sql.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (re.test(lines[i]!)) return i + 1;
  }
  return 1;
};

const SUMMARIES: Record<RiskLevel, string> = {
  LOW: 'Single-statement query with bounded scope. No issues detected.',
  MEDIUM:
    'Query passes structural checks but operates on multiple rows. Suggest reviewing scope before approval.',
  HIGH:
    'Query may return or modify a large number of rows or expose sensitive data. Recommend narrowing the projection and adding LIMIT.',
  CRITICAL:
    'CRITICAL: this statement is destructive or unbounded and affects production data without sufficient guardrails. Strong manual review recommended.',
};

export function mockAnalyze(sql: string): AiAnalysis {
  const upper = sql.toUpperCase();
  let score = 10;
  const issues: AiIssue[] = [];

  if (upper.includes('SELECT *')) {
    score += 40;
    issues.push({
      severity: 'HIGH', category: 'SELECT_STAR',
      line: findLine(sql, /select\s*\*/i),
      message: 'SELECT * returns all columns including potentially sensitive PII.',
      suggestion: 'Project only the columns you need: id, email, name.',
    });
  }
  if (upper.includes('DROP TABLE') || upper.includes('TRUNCATE')) {
    score += 60;
    issues.push({
      severity: 'CRITICAL', category: 'DESTRUCTIVE_DDL',
      line: findLine(sql, /drop\s+table|truncate/i),
      message: 'Destructive statement — irreversible without a backup.',
      suggestion: 'Take a snapshot first; consider RENAME to a quarantine schema.',
    });
  }
  if (upper.includes('DELETE') && !upper.includes('WHERE')) {
    score += 50;
    issues.push({
      severity: 'CRITICAL', category: 'NO_WHERE',
      line: findLine(sql, /delete/i),
      message: 'DELETE without WHERE clause will affect all rows.',
      suggestion: 'Add a WHERE clause to scope the delete.',
    });
  }
  if (upper.includes('UPDATE') && !upper.includes('WHERE')) {
    score += 45;
    issues.push({
      severity: 'HIGH', category: 'NO_WHERE',
      line: findLine(sql, /update/i),
      message: 'UPDATE without WHERE clause will modify all rows.',
      suggestion: 'Always scope writes with a primary-key WHERE.',
    });
  }
  if (upper.includes("LIKE '%")) {
    score += 15;
    issues.push({
      severity: 'MEDIUM', category: 'NON_SARGABLE',
      line: findLine(sql, /like\s+'/i),
      message: 'Leading-wildcard LIKE prevents index usage.',
      suggestion: 'Consider a trigram index or refactor the predicate.',
    });
  }
  if (
    upper.includes('SELECT') &&
    !upper.includes('LIMIT') &&
    !upper.includes('= ') &&
    !upper.includes("='") &&
    !upper.includes('WHERE ID')
  ) {
    if (!issues.some((i) => i.category === 'NO_WHERE')) {
      score += 10;
      issues.push({
        severity: 'MEDIUM', category: 'NO_LIMIT', line: 1,
        message: 'SELECT without LIMIT may return many rows.',
        suggestion: 'Add LIMIT 1000 or a more selective WHERE.',
      });
    }
  }

  score = Math.min(95, score);
  const level: RiskLevel =
    score >= 80 ? 'CRITICAL' : score >= 60 ? 'HIGH' : score >= 35 ? 'MEDIUM' : 'LOW';

  return {
    risk_score: score,
    risk_level: level,
    summary: SUMMARIES[level],
    issues,
    affects_rows: upper.includes('= ') || upper.includes('LIMIT 1') ? 1 : 50 + Math.floor(Math.random() * 5000),
    prompt_tokens: 412,
    completion_tokens: 187,
  };
}

export function deriveQueryType(
  sql: string,
): 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'DDL' {
  const upper = sql.toUpperCase().replace(/^\s*--.*$/gm, '').trim();
  const m = upper.match(/\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE)\b/);
  const kw = (m?.[0] ?? 'SELECT') as string;
  if (['CREATE', 'ALTER', 'DROP', 'TRUNCATE'].includes(kw)) return 'DDL';
  return kw as 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE';
}
