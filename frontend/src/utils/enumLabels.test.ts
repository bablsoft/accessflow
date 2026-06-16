import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import { queryTemplateChangeLabel } from './enumLabels';

const t = ((key: string) => key) as unknown as TFunction;

describe('queryTemplateChangeLabel', () => {
  it('maps each change type to its enum translation key', () => {
    expect(queryTemplateChangeLabel(t, 'CREATED')).toBe('enums.query_template_change_type.CREATED');
    expect(queryTemplateChangeLabel(t, 'UPDATED')).toBe('enums.query_template_change_type.UPDATED');
    expect(queryTemplateChangeLabel(t, 'RESTORED')).toBe('enums.query_template_change_type.RESTORED');
  });
});
