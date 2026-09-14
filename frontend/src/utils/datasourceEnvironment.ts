import type { TFunction } from 'i18next';
import type { DatasourceEnvironment, UpdateDatasourceInput } from '@/types/api';
import { DATASOURCE_ENVIRONMENTS, datasourceEnvironmentLabel } from './enumLabels';

/**
 * Form sentinel for "no environment" (#865). AntD `Select` cannot hold `null` as a real option, and
 * the datasource form must offer an explicit "not set" choice rather than only a clear icon.
 */
export const ENVIRONMENT_NOT_SET = 'NOT_SET';

export type DatasourceEnvironmentFormValue = DatasourceEnvironment | typeof ENVIRONMENT_NOT_SET;

export interface EnvironmentOption {
  value: DatasourceEnvironmentFormValue;
  label: string;
}

/** The select options with the explicit "not set" entry first. */
export function datasourceEnvironmentOptions(
  t: TFunction,
  notSetLabel: string,
): EnvironmentOption[] {
  return [
    { value: ENVIRONMENT_NOT_SET, label: notSetLabel },
    ...DATASOURCE_ENVIRONMENTS.map((env) => ({
      value: env,
      label: datasourceEnvironmentLabel(t, env),
    })),
  ];
}

/** What the form shows for a stored datasource: the environment, or the sentinel when unset. */
export function toEnvironmentFormValue(
  environment: DatasourceEnvironment | null | undefined,
): DatasourceEnvironmentFormValue {
  return environment ?? ENVIRONMENT_NOT_SET;
}

/**
 * The update-body fragment for a form value. The API treats an omitted / null `environment` as
 * "unchanged", so clearing needs the explicit `clear_environment` flag (#861).
 */
export function toEnvironmentUpdate(
  value: DatasourceEnvironmentFormValue | undefined,
): Pick<UpdateDatasourceInput, 'environment' | 'clear_environment'> {
  if (!value || value === ENVIRONMENT_NOT_SET) {
    return { environment: null, clear_environment: true };
  }
  return { environment: value };
}

/** The create-body value: the environment, or nothing at all when not set. */
export function toEnvironmentCreate(
  value: DatasourceEnvironmentFormValue | undefined,
): DatasourceEnvironment | undefined {
  return !value || value === ENVIRONMENT_NOT_SET ? undefined : value;
}
