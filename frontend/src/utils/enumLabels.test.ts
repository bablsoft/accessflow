import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  OPTIMIZATION_TYPES,
  optimizationTypeLabel,
  queryTemplateChangeLabel,
  submissionReasonLabel,
} from './enumLabels';

const t = ((key: string) => key) as unknown as TFunction;

describe('queryTemplateChangeLabel', () => {
  it('maps each change type to its enum translation key', () => {
    expect(queryTemplateChangeLabel(t, 'CREATED')).toBe('enums.query_template_change_type.CREATED');
    expect(queryTemplateChangeLabel(t, 'UPDATED')).toBe('enums.query_template_change_type.UPDATED');
    expect(queryTemplateChangeLabel(t, 'RESTORED')).toBe('enums.query_template_change_type.RESTORED');
  });
});

describe('optimizationTypeLabel', () => {
  it('maps each optimization type to its enum translation key', () => {
    expect(OPTIMIZATION_TYPES).toEqual(['INDEX', 'REWRITE']);
    expect(optimizationTypeLabel(t, 'INDEX')).toBe('enums.optimization_type.INDEX');
    expect(optimizationTypeLabel(t, 'REWRITE')).toBe('enums.optimization_type.REWRITE');
  });
});

describe('submissionReasonLabel', () => {
  it('maps each submission reason to its enum translation key', () => {
    expect(submissionReasonLabel(t, 'USER_SUBMITTED')).toBe('enums.submission_reason.USER_SUBMITTED');
    expect(submissionReasonLabel(t, 'AI_SUGGESTION')).toBe('enums.submission_reason.AI_SUGGESTION');
  });
});
