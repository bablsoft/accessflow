import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import i18n from '@/i18n';
import {
  ACCESS_SOURCE_KINDS,
  API_DECISION_STEP_KINDS,
  DECISION_STEP_OUTCOMES,
  DEPLOYMENT_DECISION_STEP_KINDS,
  EFFECTIVE_ACCESS_TABLE_SCOPES,
  QUERY_DECISION_STEP_KINDS,
  SIMULATED_AI_OUTCOMES,
  STATEMENT_CAPABILITIES,
  accessSourceKindLabel,
  apiDecisionStepLabel,
  decisionStepOutcomeLabel,
  deploymentDecisionStepLabel,
  effectiveAccessTableScopeLabel,
  queryDecisionStepLabel,
  simulatedAiOutcomeLabel,
  statementCapabilityLabel,
} from '../enumLabels';
import { decisionStepOutcomeColor } from '../statusColors';

const stubT = ((key: string) => key) as unknown as TFunction;
const t = i18n.t.bind(i18n) as unknown as TFunction;

// Each helper builds `enums.<namespace>.<VALUE>`, and every value resolves in en.json — a missing
// key would render the raw key path in the trace.
const CASES: [string, readonly string[], (t: TFunction, v: never) => string][] = [
  ['decision_step_outcome', DECISION_STEP_OUTCOMES, decisionStepOutcomeLabel],
  ['query_decision_step', QUERY_DECISION_STEP_KINDS, queryDecisionStepLabel],
  ['api_decision_step', API_DECISION_STEP_KINDS, apiDecisionStepLabel],
  ['deployment_decision_step', DEPLOYMENT_DECISION_STEP_KINDS, deploymentDecisionStepLabel],
  ['simulated_ai_outcome', SIMULATED_AI_OUTCOMES, simulatedAiOutcomeLabel],
  ['statement_capability', STATEMENT_CAPABILITIES, statementCapabilityLabel],
  ['access_source_kind', ACCESS_SOURCE_KINDS, accessSourceKindLabel],
  ['effective_access_table_scope', EFFECTIVE_ACCESS_TABLE_SCOPES, effectiveAccessTableScopeLabel],
];

describe('decision-trace enum labels', () => {
  it.each(CASES)('%s builds the key and resolves every value', (ns, values, label) => {
    for (const v of values) {
      expect(label(stubT, v as never)).toBe(`enums.${ns}.${v}`);
      expect(label(t, v as never)).not.toBe(`enums.${ns}.${v}`);
    }
  });

  it('keeps the query step enum at the fourteen backend values', () => {
    expect(QUERY_DECISION_STEP_KINDS).toHaveLength(14);
    expect(API_DECISION_STEP_KINDS).toHaveLength(9);
    expect(DEPLOYMENT_DECISION_STEP_KINDS).toHaveLength(9);
  });
});

describe('decisionStepOutcomeColor', () => {
  it.each([
    ['ALLOW', 'var(--risk-low)'],
    ['DENY', 'var(--risk-crit)'],
    ['MATCH', 'var(--status-warn)'],
    ['NO_MATCH', 'var(--fg-muted)'],
    ['SKIP', 'var(--fg-muted)'],
  ] as const)('maps %s to %s', (outcome, fg) => {
    const c = decisionStepOutcomeColor(outcome);
    expect(c.fg).toBe(fg);
    expect(c.bg).toBeDefined();
    expect(c.border).toBeDefined();
  });
});
