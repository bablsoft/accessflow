import type {
  SqlReviewCustomRule,
  SqlReviewCustomRuleWriteRequest,
  SqlReviewRuleCategory,
  SqlReviewSeverity,
  SqlRuleCondition,
  SqlRuleConditionLeaf,
  SqlRuleConditionOperand,
  SqlRuleQueryType,
} from '@/types/api';
import {
  rowsToTree,
  treeToRows,
  type ConditionMatchType,
  type ConditionRowBase,
} from '@/components/conditions/conditionTreeForm';

/** The fixed prefix of every custom rule id; the admin types only the slug after it. */
export const CUSTOM_RULE_PREFIX = 'custom_';

/** Mirrors `SqlReviewCustomRuleRequest.rule_id` `@Pattern` minus the fixed prefix. */
export const SLUG_PATTERN = /^[a-z][a-z0-9_]{2,60}$/;
/** Mirrors `SqlRuleConditionValidator.GLOB` (whole-string). */
export const GLOB_PATTERN = /^[A-Za-z0-9_$*.-]+$/;
/** Mirrors `SqlRuleConditionValidator.FUNCTION` (whole-string). */
export const FUNCTION_NAME_PATTERN = /^[A-Za-z0-9_$.-]+$/;
/** `SqlRuleConditionValidator.MAX_LEAVES` — the flat builder emits one leaf per row. */
export const MAX_CONDITION_ROWS = 20;
/** `SafeRegex.MAX_PATTERN_LENGTH`. */
export const MAX_REGEX_LENGTH = 500;
/** `@Size(max = 500)` on `message`. */
export const MAX_MESSAGE_LENGTH = 500;
/** `@Size(max = 100_000)` on the test request's `sql`. */
export const MAX_TEST_SQL_LENGTH = 100_000;

/** Placeholders the backend substitutes into a custom rule's message. */
export const MESSAGE_PLACEHOLDERS = ['{tables}', '{functions}', '{statement_type}'] as const;

export interface SqlRuleConditionRow extends ConditionRowBase<SqlRuleConditionOperand> {
  query_types?: SqlRuleQueryType[];
  table_globs?: string[];
  column_globs?: string[];
  function_names?: string[];
  bool_value?: boolean;
  pattern?: string;
  ignore_case?: boolean;
}

export interface SqlReviewCustomRuleFormValues {
  /** The part after `custom_`; immutable once the rule exists. */
  slug: string;
  name: string;
  description?: string;
  message: string;
  category: SqlReviewRuleCategory;
  default_severity: SqlReviewSeverity;
  enabled: boolean;
  match_type: ConditionMatchType;
  conditions: SqlRuleConditionRow[];
}

/** The criterion a freshly picked operand starts from — the value that makes it interesting. */
export function defaultSqlRuleRow(operand: SqlRuleConditionOperand): SqlRuleConditionRow {
  switch (operand) {
    case 'query_type':
      return { operand, negate: false, query_types: ['DELETE'] };
    case 'referenced_table':
      return { operand, negate: false, table_globs: [] };
    case 'referenced_column':
      return { operand, negate: false, column_globs: [] };
    case 'function_called':
      return { operand, negate: false, function_names: [] };
    case 'has_where':
    case 'has_limit':
    case 'has_order_by':
    case 'transactional':
      return { operand, negate: false, bool_value: false };
    case 'where_always_true':
    case 'join_without_condition':
    case 'like_leading_wildcard':
      return { operand, negate: false, bool_value: true };
    case 'sql_matches':
      return { operand, negate: false, pattern: '', ignore_case: true };
  }
}

function rowToLeaf(row: SqlRuleConditionRow): SqlRuleConditionLeaf {
  switch (row.operand) {
    case 'query_type':
      return { type: 'query_type', any_of: row.query_types ?? [] };
    case 'referenced_table':
      return { type: 'referenced_table', globs: row.table_globs ?? [] };
    case 'referenced_column':
      return { type: 'referenced_column', globs: row.column_globs ?? [] };
    case 'function_called':
      return { type: 'function_called', names: row.function_names ?? [] };
    case 'has_where':
    case 'has_limit':
    case 'has_order_by':
    case 'where_always_true':
    case 'join_without_condition':
    case 'like_leading_wildcard':
    case 'transactional':
      // `expected` is a primitive boolean on the backend record — never omit it.
      return { type: row.operand, expected: row.bool_value ?? false };
    case 'sql_matches':
      return {
        type: 'sql_matches',
        pattern: row.pattern ?? '',
        ignore_case: row.ignore_case ?? false,
      };
  }
}

/**
 * Null for a leaf type this build does not know (a backend ahead of the frontend), so the tree is
 * flagged unsupported instead of being rewritten as something else on save.
 */
function leafToRow(leaf: SqlRuleConditionLeaf, negate: boolean): SqlRuleConditionRow | null {
  switch (leaf.type) {
    case 'query_type':
      return { operand: 'query_type', negate, query_types: leaf.any_of };
    case 'referenced_table':
      return { operand: 'referenced_table', negate, table_globs: leaf.globs };
    case 'referenced_column':
      return { operand: 'referenced_column', negate, column_globs: leaf.globs };
    case 'function_called':
      return { operand: 'function_called', negate, function_names: leaf.names };
    case 'sql_matches':
      return { operand: 'sql_matches', negate, pattern: leaf.pattern, ignore_case: leaf.ignore_case };
    case 'has_where':
    case 'has_limit':
    case 'has_order_by':
    case 'where_always_true':
    case 'join_without_condition':
    case 'like_leading_wildcard':
    case 'transactional':
      return { operand: leaf.type, negate, bool_value: leaf.expected };
    default:
      return null;
  }
}

export function rowsToSqlRuleCondition(
  matchType: ConditionMatchType,
  rows: readonly SqlRuleConditionRow[],
): SqlRuleCondition {
  return rowsToTree(matchType, rows, rowToLeaf);
}

export function sqlRuleConditionToRows(condition: SqlRuleCondition | null | undefined) {
  return treeToRows<SqlRuleConditionLeaf, SqlRuleConditionRow>(condition, leafToRow);
}

export function slugOf(ruleId: string): string {
  return ruleId.startsWith(CUSTOM_RULE_PREFIX) ? ruleId.slice(CUSTOM_RULE_PREFIX.length) : ruleId;
}

export const NEW_RULE_DEFAULT_VALUES: SqlReviewCustomRuleFormValues = {
  slug: '',
  name: '',
  description: '',
  message: '',
  category: 'STATEMENT_SAFETY',
  default_severity: 'WARN',
  enabled: true,
  match_type: 'ALL',
  conditions: [defaultSqlRuleRow('query_type')],
};

/**
 * Seeds the drawer. `supported: false` means the stored tree (authored through the API or
 * Terraform) is nested deeper than the flat builder can show; saving replaces it.
 */
export function toFormValues(rule: SqlReviewCustomRule | null): {
  values: SqlReviewCustomRuleFormValues;
  supported: boolean;
} {
  if (!rule) {
    return { values: NEW_RULE_DEFAULT_VALUES, supported: true };
  }
  const parsed = sqlRuleConditionToRows(rule.condition);
  return {
    values: {
      slug: slugOf(rule.rule_id),
      name: rule.name,
      description: rule.description ?? '',
      message: rule.message,
      category: rule.category,
      default_severity: rule.default_severity,
      enabled: rule.enabled,
      match_type: parsed.matchType,
      conditions: parsed.rows,
    },
    supported: parsed.supported,
  };
}

const cleanList = (values: readonly string[] | undefined): string[] =>
  (values ?? []).map((v) => v.trim()).filter((v) => v.length > 0);

function cleanRow(row: SqlRuleConditionRow): SqlRuleConditionRow {
  return {
    ...row,
    negate: row.negate ?? false,
    ...(row.table_globs ? { table_globs: cleanList(row.table_globs) } : {}),
    ...(row.column_globs ? { column_globs: cleanList(row.column_globs) } : {}),
    ...(row.function_names ? { function_names: cleanList(row.function_names) } : {}),
  };
}

export function toWriteRequest(values: SqlReviewCustomRuleFormValues): SqlReviewCustomRuleWriteRequest {
  const description = values.description?.trim();
  return {
    rule_id: `${CUSTOM_RULE_PREFIX}${values.slug.trim()}`,
    name: values.name.trim(),
    ...(description ? { description } : {}),
    message: values.message.trim(),
    category: values.category,
    default_severity: values.default_severity,
    enabled: values.enabled ?? true,
    condition: rowsToSqlRuleCondition(values.match_type, (values.conditions ?? []).map(cleanRow)),
  };
}

/** The stored rule verbatim with a new `enabled` — PUT is a full replace. */
export function toggleEnabledRequest(
  rule: SqlReviewCustomRule,
  enabled: boolean,
): SqlReviewCustomRuleWriteRequest {
  return {
    rule_id: rule.rule_id,
    name: rule.name,
    ...(rule.description ? { description: rule.description } : {}),
    message: rule.message,
    category: rule.category,
    default_severity: rule.default_severity,
    enabled,
    condition: rule.condition,
  };
}

/** Entries of a glob / function list that fail the backend's syntax check. */
export function invalidEntries(values: readonly string[], pattern: RegExp): string[] {
  return cleanList(values).filter((v) => !pattern.test(v));
}

