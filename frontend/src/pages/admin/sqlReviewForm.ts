import type { TFunction } from 'i18next';
import type {
  DatasourceEnvironment,
  SqlReviewRule,
  SqlReviewRuleConfigWriteRequest,
  SqlReviewRuleParam,
  SqlReviewRuleset,
  SqlReviewRulesetWriteRequest,
  SqlReviewSeverity,
} from '@/types/api';
import { DATASOURCE_ENVIRONMENTS, datasourceEnvironmentLabel } from '@/utils/enumLabels';

/**
 * Form sentinel for the organization-wide default ruleset: the wire shape expresses it as an
 * *absent* `environment`, which an AntD `Select` cannot hold as a real option.
 */
export const ORG_DEFAULT_ENVIRONMENT = 'ORG_DEFAULT';

export type RulesetEnvironmentFormValue = DatasourceEnvironment | typeof ORG_DEFAULT_ENVIRONMENT;

export interface RuleRowFormValues {
  severity: SqlReviewSeverity;
  /** Keyed by the rule's declared param key; an empty list means "not configured". */
  params: Record<string, string[]>;
}

export interface SqlReviewRulesetFormValues {
  name: string;
  description?: string;
  environment: RulesetEnvironmentFormValue;
  enabled: boolean;
  /** One row per catalog rule, keyed by rule_id — nested Form.Item paths, not a Form.List. */
  rules: Record<string, RuleRowFormValues>;
}

export function environmentOptions(
  t: TFunction,
): { value: RulesetEnvironmentFormValue; label: string }[] {
  return [
    { value: ORG_DEFAULT_ENVIRONMENT, label: t('admin.sql_review.environment_org_default') },
    ...DATASOURCE_ENVIRONMENTS.map((env) => ({
      value: env,
      label: datasourceEnvironmentLabel(t, env),
    })),
  ];
}

/** The list-column label for a ruleset's binding. */
export function rulesetEnvironmentLabel(t: TFunction, ruleset: SqlReviewRuleset): string {
  return ruleset.environment
    ? datasourceEnvironmentLabel(t, ruleset.environment)
    : t('admin.sql_review.environment_org_default');
}

/**
 * Seeds every catalog rule so the editor always shows the full table: the configured severity
 * where the ruleset names the rule, else the built-in default the rule runs at when unlisted.
 */
export function toFormValues(
  ruleset: SqlReviewRuleset | null,
  catalog: readonly SqlReviewRule[],
): SqlReviewRulesetFormValues {
  const configured = new Map((ruleset?.rules ?? []).map((r) => [r.rule_id, r]));
  const rules: Record<string, RuleRowFormValues> = {};
  for (const rule of catalog) {
    const config = configured.get(rule.rule_id);
    const params: Record<string, string[]> = {};
    for (const param of rule.params) {
      params[param.key] = config?.params?.[param.key] ?? [];
    }
    rules[rule.rule_id] = { severity: config?.severity ?? rule.default_severity, params };
  }
  return {
    name: ruleset?.name ?? '',
    description: ruleset?.description ?? '',
    environment: ruleset?.environment ?? ORG_DEFAULT_ENVIRONMENT,
    enabled: ruleset?.enabled ?? true,
    rules,
  };
}

const cleanValues = (values: readonly string[] | undefined): string[] =>
  (values ?? []).map((v) => v.trim()).filter((v) => v.length > 0);

/** Whether a rule row is stored at all: only a severity off its default or supplied params are. */
export function isRuleRowSent(rule: SqlReviewRule, row: RuleRowFormValues | undefined): boolean {
  if (!row) return false;
  if (row.severity !== rule.default_severity) return true;
  return rule.params.some((p) => cleanValues(row.params?.[p.key]).length > 0);
}

/**
 * Maps the form onto the write body. Unlisted rules run at their built-in default (the backend
 * resolves them that way), so a row is emitted only when it differs from the default or carries
 * params — and an empty param list is omitted rather than sent as `[]`, which the API refuses.
 */
export function toWriteRequest(
  values: SqlReviewRulesetFormValues,
  catalog: readonly SqlReviewRule[],
): SqlReviewRulesetWriteRequest {
  const rules: SqlReviewRuleConfigWriteRequest[] = [];
  for (const rule of catalog) {
    const row = values.rules[rule.rule_id];
    if (!row || !isRuleRowSent(rule, row)) continue;
    const params: Record<string, string[]> = {};
    for (const param of rule.params) {
      const entries = cleanValues(row.params?.[param.key]);
      if (entries.length > 0) params[param.key] = entries;
    }
    rules.push({
      rule_id: rule.rule_id,
      severity: row.severity,
      ...(Object.keys(params).length > 0 ? { params } : {}),
    });
  }
  const description = values.description?.trim();
  return {
    name: values.name.trim(),
    ...(description ? { description } : {}),
    ...(values.environment === ORG_DEFAULT_ENVIRONMENT ? {} : { environment: values.environment }),
    enabled: values.enabled,
    rules,
  };
}

/** The stored rule set of an existing ruleset, verbatim — for a list-level enabled toggle. */
export function toggleEnabledRequest(
  ruleset: SqlReviewRuleset,
  enabled: boolean,
): SqlReviewRulesetWriteRequest {
  return {
    name: ruleset.name,
    ...(ruleset.description ? { description: ruleset.description } : {}),
    ...(ruleset.environment ? { environment: ruleset.environment } : {}),
    enabled,
    rules: ruleset.rules.map((r) => ({
      rule_id: r.rule_id,
      severity: r.severity,
      ...(Object.keys(r.params ?? {}).length > 0 ? { params: r.params } : {}),
    })),
  };
}

/** Compiles the catalog's whole-string Java regex; null when it cannot be compiled client-side. */
function compilePattern(pattern: string): RegExp | null {
  try {
    return new RegExp(`^(?:${pattern})$`);
  } catch {
    return null;
  }
}

export type ParamValidationError =
  | { kind: 'required' }
  | { kind: 'invalid'; value: string };

/**
 * Mirrors the backend's write-time param validation (422 SQL_REVIEW_RULESET_INVALID): a required
 * param with no built-in defaults needs at least one entry whenever the row is stored, and every
 * entry must match the param's whole-string pattern. The server stays authoritative — an
 * uncompilable pattern simply skips the client check.
 */
export function validateParam(
  rule: SqlReviewRule,
  param: SqlReviewRuleParam,
  row: RuleRowFormValues | undefined,
): ParamValidationError | null {
  const entries = cleanValues(row?.params?.[param.key]);
  const rowSent = isRuleRowSent(rule, row);
  if (rowSent && param.required && param.defaults.length === 0 && entries.length === 0) {
    return { kind: 'required' };
  }
  const regex = compilePattern(param.value_pattern);
  if (regex) {
    const bad = entries.find((v) => !regex.test(v));
    if (bad !== undefined) return { kind: 'invalid', value: bad };
  }
  return null;
}

export interface RulesSummary {
  block: number;
  warn: number;
  off: number;
}

/** Effective severity counts across the whole catalog — configured rows plus built-in defaults. */
export function rulesSummary(
  ruleset: SqlReviewRuleset,
  catalog: readonly SqlReviewRule[],
): RulesSummary {
  const configured = new Map(ruleset.rules.map((r) => [r.rule_id, r.severity]));
  const summary: RulesSummary = { block: 0, warn: 0, off: 0 };
  for (const rule of catalog) {
    const severity = configured.get(rule.rule_id) ?? rule.default_severity;
    if (severity === 'BLOCK') summary.block += 1;
    else if (severity === 'WARN') summary.warn += 1;
    else summary.off += 1;
  }
  return summary;
}
