import { describe, expect, it } from 'vitest';
import {
  formValuesFromStrategyParams,
  isValidBoundaries,
  matchesEmptyString,
  strategyParamsFromForm,
} from './maskingStrategyParams';

describe('strategyParamsFromForm', () => {
  it('sends only the keys each strategy accepts', () => {
    const values = {
      visible_suffix: 2,
      visible_prefix: 3,
      replacement: 'R',
      pattern: 'a',
      bucket_mode: 'SIZE' as const,
      bucket_size: 10,
      boundaries: '1,2',
      precision: 'MONTH' as const,
    };
    expect(strategyParamsFromForm('PARTIAL', values)).toEqual({ visible_suffix: '2' });
    expect(strategyParamsFromForm('KEEP_FIRST', values)).toEqual({ visible_prefix: '3' });
    expect(strategyParamsFromForm('CONSTANT', values)).toEqual({ replacement: 'R' });
    expect(strategyParamsFromForm('REGEX_REPLACE', values)).toEqual({ pattern: 'a', replacement: 'R' });
    expect(strategyParamsFromForm('NUMERIC_BUCKET', values)).toEqual({ bucket_size: '10' });
    expect(strategyParamsFromForm('NUMERIC_BUCKET', { ...values, bucket_mode: 'BOUNDARIES' })).toEqual({
      boundaries: '1,2',
    });
    expect(strategyParamsFromForm('DATE_GENERALIZE', values)).toEqual({ precision: 'MONTH' });
    expect(strategyParamsFromForm('FULL', values)).toBeUndefined();
    expect(strategyParamsFromForm(undefined, values)).toBeUndefined();
  });

  it('fills defaults for empty values', () => {
    expect(strategyParamsFromForm('PARTIAL', {})).toBeUndefined();
    expect(strategyParamsFromForm('KEEP_FIRST', {})).toBeUndefined();
    expect(strategyParamsFromForm('CONSTANT', {})).toEqual({ replacement: '' });
    expect(strategyParamsFromForm('REGEX_REPLACE', {})).toEqual({ pattern: '', replacement: '' });
    expect(strategyParamsFromForm('NUMERIC_BUCKET', {})).toEqual({ bucket_size: '' });
    expect(strategyParamsFromForm('NUMERIC_BUCKET', { bucket_mode: 'BOUNDARIES' })).toEqual({
      boundaries: '',
    });
    expect(strategyParamsFromForm('DATE_GENERALIZE', {})).toEqual({ precision: 'YEAR' });
  });
});

describe('formValuesFromStrategyParams', () => {
  it('round-trips stored params into form values', () => {
    expect(formValuesFromStrategyParams({ visible_prefix: '4', bucket_size: '10' })).toMatchObject({
      visible_prefix: 4,
      bucket_mode: 'SIZE',
      bucket_size: 10,
      precision: 'YEAR',
    });
    expect(formValuesFromStrategyParams({ boundaries: '1,2', precision: 'MONTH' })).toMatchObject({
      bucket_mode: 'BOUNDARIES',
      boundaries: '1,2',
      precision: 'MONTH',
    });
    expect(formValuesFromStrategyParams(null)).toMatchObject({
      visible_suffix: undefined,
      bucket_mode: 'SIZE',
    });
    expect(formValuesFromStrategyParams({ visible_suffix: 'abc' }).visible_suffix).toBeUndefined();
  });
});

describe('isValidBoundaries', () => {
  it('mirrors the backend rule', () => {
    expect(isValidBoundaries('18,30,65')).toBe(true);
    expect(isValidBoundaries('30,18')).toBe(false);
    expect(isValidBoundaries(undefined)).toBe(false);
    expect(isValidBoundaries(Array.from({ length: 51 }, (_, i) => i).join(','))).toBe(false);
  });
});

describe('matchesEmptyString', () => {
  it('flags patterns that match the empty string and ignores ones the browser cannot compile', () => {
    expect(matchesEmptyString('\\d*')).toBe(true);
    expect(matchesEmptyString('\\d+')).toBe(false);
    expect(matchesEmptyString('(')).toBe(false);
  });
});
