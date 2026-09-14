import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  ENVIRONMENT_NOT_SET,
  datasourceEnvironmentOptions,
  toEnvironmentCreate,
  toEnvironmentFormValue,
  toEnvironmentUpdate,
} from '../datasourceEnvironment';

const t = ((key: string) => key) as unknown as TFunction;

describe('datasourceEnvironment helpers (#865)', () => {
  it('lists the explicit not-set option first, then every environment', () => {
    expect(datasourceEnvironmentOptions(t, 'Not set')).toEqual([
      { value: ENVIRONMENT_NOT_SET, label: 'Not set' },
      { value: 'DEVELOPMENT', label: 'enums.datasource_environment.DEVELOPMENT' },
      { value: 'TEST', label: 'enums.datasource_environment.TEST' },
      { value: 'STAGING', label: 'enums.datasource_environment.STAGING' },
      { value: 'PRODUCTION', label: 'enums.datasource_environment.PRODUCTION' },
    ]);
  });

  it('maps a stored environment to the form value and back', () => {
    expect(toEnvironmentFormValue('STAGING')).toBe('STAGING');
    expect(toEnvironmentFormValue(null)).toBe(ENVIRONMENT_NOT_SET);
    expect(toEnvironmentFormValue(undefined)).toBe(ENVIRONMENT_NOT_SET);
  });

  it('clears explicitly on update, since a null environment means unchanged', () => {
    expect(toEnvironmentUpdate('PRODUCTION')).toEqual({ environment: 'PRODUCTION' });
    expect(toEnvironmentUpdate(ENVIRONMENT_NOT_SET)).toEqual({
      environment: null,
      clear_environment: true,
    });
    expect(toEnvironmentUpdate(undefined)).toEqual({ environment: null, clear_environment: true });
  });

  it('omits the environment from a create body when not set', () => {
    expect(toEnvironmentCreate('TEST')).toBe('TEST');
    expect(toEnvironmentCreate(ENVIRONMENT_NOT_SET)).toBeUndefined();
    expect(toEnvironmentCreate(undefined)).toBeUndefined();
  });
});
