import type { Text } from '@codemirror/state';
import type { Diagnostic } from '@codemirror/lint';
import type { AiIssue, SqlReviewFinding } from '@/types/api';

export interface DiagnosticSourceLabels {
  /** Tooltip label for a deterministic SQL review finding (user-visible, so a `t()` string). */
  sqlReview: string;
  /** Tooltip label for an AI-analysis issue. */
  ai: string;
}

export interface DiagnosticsInput {
  findings: readonly SqlReviewFinding[];
  issues: readonly AiIssue[];
  sources: DiagnosticSourceLabels;
}

/**
 * The [from, to) range of a one-based line, clamped into the document: the findings were computed
 * against the debounced draft, so a line past the end (the author deleted lines since) must fold
 * onto the last line — CodeMirror's gutter throws on a position beyond `doc.length`.
 */
function lineRange(doc: Text, lineNumber: number): { from: number; to: number } {
  const line = doc.line(Math.min(Math.max(lineNumber, 1), doc.lines));
  return { from: line.from, to: line.to };
}

/**
 * Folds SQL review findings and AI issues onto one CodeMirror diagnostic surface (#865). WARN and
 * the lower AI severities render as warnings; BLOCK and CRITICAL/HIGH AI issues as errors. A
 * finding with no line number (a BEGIN … COMMIT envelope member) has no position to mark and is
 * left to the findings strip, which lists it by statement instead.
 */
export function toDiagnostics(doc: Text, input: DiagnosticsInput): Diagnostic[] {
  const diagnostics: Diagnostic[] = [];
  for (const finding of input.findings) {
    if (finding.line_number === undefined) continue;
    diagnostics.push({
      ...lineRange(doc, finding.line_number),
      severity: finding.severity === 'BLOCK' ? 'error' : 'warning',
      source: input.sources.sqlReview,
      message: finding.message,
    });
  }
  for (const issue of input.issues) {
    diagnostics.push({
      ...lineRange(doc, issue.line ?? 1),
      severity: issue.severity === 'CRITICAL' || issue.severity === 'HIGH' ? 'error' : 'warning',
      source: input.sources.ai,
      message: issue.message,
    });
  }
  // CodeMirror builds a RangeSet from these and requires them sorted by position.
  return diagnostics.sort((a, b) => a.from - b.from || a.to - b.to);
}
