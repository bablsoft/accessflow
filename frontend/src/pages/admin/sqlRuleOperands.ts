import type { TFunction } from 'i18next';
import type { SqlRuleConditionOperand } from '@/types/api';
import type {
  ConditionOperandSpec,
  ConditionValueEditorSpec,
} from '@/components/conditions/ConditionTreeEditor';
import {
  SQL_RULE_CONDITION_OPERANDS,
  SQL_RULE_QUERY_TYPES,
  enumOptions,
  sqlRuleOperandLabel,
  sqlRuleQueryTypeLabel,
} from '@/utils/enumLabels';
import {
  FUNCTION_NAME_PATTERN,
  GLOB_PATTERN,
  MAX_REGEX_LENGTH,
  defaultSqlRuleRow,
  invalidEntries,
} from './sqlReviewCustomRuleForm';

function listValidator(t: TFunction, pattern: RegExp) {
  return (values: string[]) => {
    const bad = invalidEntries(values, pattern);
    return bad.length
      ? t('admin.sql_review.custom_rules.invalid_entries', { value: bad.join(', ') })
      : null;
  };
}

function sqlRuleEditor(operand: SqlRuleConditionOperand, t: TFunction): ConditionValueEditorSpec {
  switch (operand) {
    case 'query_type':
      return {
        kind: 'multi',
        field: 'query_types',
        options: enumOptions(SQL_RULE_QUERY_TYPES, sqlRuleQueryTypeLabel, t),
      };
    case 'referenced_table':
      return {
        kind: 'tags',
        field: 'table_globs',
        separators: [',', ' '],
        placeholder: t('admin.sql_review.custom_rules.table_globs_placeholder'),
        validate: listValidator(t, GLOB_PATTERN),
      };
    case 'referenced_column':
      return {
        kind: 'tags',
        field: 'column_globs',
        separators: [',', ' '],
        placeholder: t('admin.sql_review.custom_rules.column_globs_placeholder'),
        validate: listValidator(t, GLOB_PATTERN),
      };
    case 'function_called':
      return {
        kind: 'tags',
        field: 'function_names',
        separators: [',', ' '],
        placeholder: t('admin.sql_review.custom_rules.function_names_placeholder'),
        validate: listValidator(t, FUNCTION_NAME_PATTERN),
      };
    case 'sql_matches':
      return {
        kind: 'regex',
        patternField: 'pattern',
        ignoreCaseField: 'ignore_case',
        maxLength: MAX_REGEX_LENGTH,
        patternLabel: t('admin.sql_review.custom_rules.regex_label'),
        ignoreCaseLabel: t('admin.sql_review.custom_rules.ignore_case_label'),
        placeholder: t('admin.sql_review.custom_rules.regex_placeholder'),
      };
    default:
      return {
        kind: 'bool',
        field: 'bool_value',
        label: t('admin.sql_review.custom_rules.expected_label'),
      };
  }
}

/** The pure-AST SQL review operand set (epic #1008) for the shared condition editor. */
export function sqlRuleOperandSpecs(t: TFunction): ConditionOperandSpec<SqlRuleConditionOperand>[] {
  return SQL_RULE_CONDITION_OPERANDS.map((operand) => ({
    value: operand,
    label: sqlRuleOperandLabel(t, operand),
    editor: sqlRuleEditor(operand, t),
    defaultRow: () => defaultSqlRuleRow(operand),
  }));
}
