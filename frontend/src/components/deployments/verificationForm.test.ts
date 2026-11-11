import { describe, expect, it } from 'vitest';
import {
  defaultCheckValues,
  defaultVerificationValues,
  isDuplicateCheckName,
  isoDurationSeconds,
  maxSamplesInWindow,
  minutesToIso,
  toVerificationFormValues,
  toVerificationWire,
  verificationCheckCount,
  verificationInvalidMessage,
  verificationUrlProblem,
  VERIFICATION_INVALID_REASONS,
} from './verificationForm';
import type { TFunction } from 'i18next';
import type { DeploymentVerificationConfig } from '@/types/api';

const config: DeploymentVerificationConfig = {
  window: 'PT15M',
  sample_interval: 'PT30S',
  failure_threshold: 3,
  checks: [
    { kind: 'HTTP_HEALTH', name: 'health', url: 'https://svc.example.com/healthz', expected_status: 204 },
    {
      kind: 'PROMQL_THRESHOLD',
      name: 'error-rate',
      prometheus_url: 'https://prom.example.com',
      query: 'sum(rate(errors[5m]))',
      comparator: 'GTE',
      threshold: 0.05,
      no_data_is_failure: false,
    },
  ],
};

describe('verificationForm', () => {
  it('parses ISO-8601 time durations to seconds', () => {
    expect(isoDurationSeconds('PT15M')).toBe(900);
    expect(isoDurationSeconds('PT30S')).toBe(30);
    expect(isoDurationSeconds('PT1H30M')).toBe(5400);
    expect(isoDurationSeconds('PT1.5S')).toBe(1.5);
    expect(isoDurationSeconds('P1D')).toBeNull();
    expect(isoDurationSeconds('PT')).toBeNull();
    expect(isoDurationSeconds(null)).toBeNull();
    expect(isoDurationSeconds('')).toBeNull();
  });

  it('formats minutes as whole minutes or seconds', () => {
    expect(minutesToIso(15)).toBe('PT15M');
    expect(minutesToIso(0.5)).toBe('PT30S');
    expect(minutesToIso(1.25)).toBe('PT75S');
  });

  it('defaults to off with one HTTP check', () => {
    const values = defaultVerificationValues();
    expect(values.enabled).toBe(false);
    expect(values.checks).toEqual([defaultCheckValues()]);
    expect(defaultCheckValues('PROMQL_THRESHOLD').kind).toBe('PROMQL_THRESHOLD');
  });

  it('maps a stored config into form values', () => {
    const values = toVerificationFormValues(config);
    expect(values.enabled).toBe(true);
    expect(values.window_minutes).toBe(15);
    expect(values.interval_minutes).toBe(0.5);
    expect(values.failure_threshold).toBe(3);
    expect(values.checks[0]).toMatchObject({ kind: 'HTTP_HEALTH', url: 'https://svc.example.com/healthz', expected_status: 204 });
    expect(values.checks[1]).toMatchObject({
      kind: 'PROMQL_THRESHOLD',
      comparator: 'GTE',
      threshold: 0.05,
      no_data_is_failure: false,
    });
  });

  it('falls back to defaults for absent config and omitted check fields', () => {
    expect(toVerificationFormValues(null).enabled).toBe(false);
    expect(toVerificationFormValues(undefined).enabled).toBe(false);
    const values = toVerificationFormValues({
      window: 'bogus',
      sample_interval: 'PT1M',
      failure_threshold: 1,
      checks: [{ kind: 'PROMQL_THRESHOLD', name: 'p' }],
    });
    expect(values.window_minutes).toBeNull();
    expect(values.checks[0]).toMatchObject({ comparator: 'LT', no_data_is_failure: true, expected_status: 200 });
  });

  it('round-trips form values back to the wire, dropping the other kind’s fields', () => {
    const wire = toVerificationWire(toVerificationFormValues(config));
    expect(wire).toEqual({
      window: 'PT15M',
      sample_interval: 'PT30S',
      failure_threshold: 3,
      checks: [
        {
          kind: 'HTTP_HEALTH',
          name: 'health',
          url: 'https://svc.example.com/healthz',
          expected_status: 204,
          body_contains: null,
        },
        {
          kind: 'PROMQL_THRESHOLD',
          name: 'error-rate',
          prometheus_url: 'https://prom.example.com',
          query: 'sum(rate(errors[5m]))',
          comparator: 'GTE',
          threshold: 0.05,
          no_data_is_failure: false,
        },
      ],
    });
  });

  it('returns null when verification is off', () => {
    expect(toVerificationWire(defaultVerificationValues())).toBeNull();
    expect(toVerificationWire(undefined)).toBeNull();
  });

  it('trims text and fills defaults for unset fields', () => {
    const wire = toVerificationWire({
      enabled: true,
      window_minutes: null,
      interval_minutes: null,
      failure_threshold: null,
      checks: [
        { ...defaultCheckValues(), name: ' h ', url: ' https://x.example ', body_contains: '  ', expected_status: null },
        { ...defaultCheckValues('PROMQL_THRESHOLD'), name: 'p', comparator: null, threshold: null, no_data_is_failure: undefined },
      ],
    });
    expect(wire?.window).toBe('PT0M');
    expect(wire?.failure_threshold).toBe(0);
    expect(wire?.checks[0]).toEqual({
      kind: 'HTTP_HEALTH',
      name: 'h',
      url: 'https://x.example',
      expected_status: null,
      body_contains: null,
    });
    expect(wire?.checks[1]).toMatchObject({ comparator: 'LT', threshold: 0, no_data_is_failure: true, prometheus_url: '', query: '' });
  });

  it('mirrors the network-free URL rules', () => {
    const http = { prometheus: false };
    const prom = { prometheus: true };
    expect(verificationUrlProblem('https://svc.example.com/h?probe=1', http)).toBeNull();
    expect(verificationUrlProblem('HTTP://svc', http)).toBeNull();
    expect(verificationUrlProblem('', http)).toBeNull();
    expect(verificationUrlProblem(null, http)).toBeNull();
    expect(verificationUrlProblem('ftp://svc', http)).toBe('scheme');
    expect(verificationUrlProblem('svc.example.com', http)).toBe('scheme');
    expect(verificationUrlProblem('https://', http)).toBe('invalid');
    expect(verificationUrlProblem('https://exa mple.com', http)).toBe('invalid');
    expect(verificationUrlProblem('https://u:p@svc.example.com', http)).toBe('userinfo');
    expect(verificationUrlProblem('https://u@svc.example.com', prom)).toBe('userinfo');
    expect(verificationUrlProblem('https://prom:9090/?x=1', prom)).toBe('query');
    expect(verificationUrlProblem('https://prom:9090/#a', prom)).toBe('query');
    expect(verificationUrlProblem('https://prom:9090/prefix', prom)).toBeNull();
  });

  it('names the rule a 422 DEPLOYMENT_VERIFICATION_INVALID reports', () => {
    const t = ((key: string, opts?: Record<string, unknown>) =>
      opts ? `${key}|${String(opts.check)}` : key) as unknown as TFunction;
    for (const reason of VERIFICATION_INVALID_REASONS) {
      expect(
        verificationInvalidMessage(t, {
          error: 'DEPLOYMENT_VERIFICATION_INVALID',
          reason,
          checkName: 'health',
        }),
      ).toBe(`deploygov.settings.verificationInvalid.${reason}|health`);
    }
    expect(
      verificationInvalidMessage(t, {
        error: 'DEPLOYMENT_VERIFICATION_INVALID',
        reason: 'window_out_of_range',
      }),
    ).toBe('deploygov.settings.verificationInvalid.window_out_of_range|—');
    expect(
      verificationInvalidMessage(t, {
        error: 'DEPLOYMENT_VERIFICATION_INVALID',
        reason: 'duplicate_check_name',
        check_name: 'h',
      }),
    ).toBe('deploygov.settings.verificationInvalid.duplicate_check_name|h');
    expect(
      verificationInvalidMessage(t, { error: 'DEPLOYMENT_VERIFICATION_INVALID', reason: 'new' }),
    ).toBeNull();
    expect(verificationInvalidMessage(t, { error: 'OTHER', reason: 'host_missing' })).toBeNull();
    expect(verificationInvalidMessage(t, null)).toBeNull();
    expect(verificationInvalidMessage(t, 'text')).toBeNull();
  });

  it('computes how many samples fit in the window', () => {
    expect(maxSamplesInWindow(15, 1)).toBe(15);
    expect(maxSamplesInWindow(1, 0.5)).toBe(2);
    expect(maxSamplesInWindow(5, 2)).toBe(2);
    expect(maxSamplesInWindow(null, 1)).toBeNull();
    expect(maxSamplesInWindow(5, 0)).toBeNull();
  });

  it('detects duplicate check names, ignoring blanks and the row itself', () => {
    const checks = [
      { ...defaultCheckValues(), name: 'health' },
      { ...defaultCheckValues(), name: ' health ' },
      { ...defaultCheckValues(), name: '' },
    ];
    expect(isDuplicateCheckName(checks, 0, 'health')).toBe(true);
    expect(isDuplicateCheckName(checks, 0, 'other')).toBe(false);
    expect(isDuplicateCheckName(checks, 2, '')).toBe(false);
    expect(isDuplicateCheckName(undefined, 0, 'x')).toBe(false);
    expect(isDuplicateCheckName([undefined], 1, 'x')).toBe(false);
  });

  it('counts checks for the table summary', () => {
    expect(verificationCheckCount(config)).toBe(2);
    expect(verificationCheckCount(null)).toBeNull();
  });
});
