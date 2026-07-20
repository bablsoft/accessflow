import { describe, expect, it } from 'vitest';
import {
  compositionFromSaved,
  compositionToSubmit,
  emptyComposition,
  fileToBase64,
  newComposition,
  pairsToRecord,
  recordToPairs,
  type ApiRequestComposition,
} from '../apiRequestComposition';

describe('pairsToRecord / recordToPairs', () => {
  it('drops blank-key rows and trims keys', () => {
    expect(
      pairsToRecord([
        { key: ' X-A ', value: '1' },
        { key: '', value: 'ignored' },
        { key: '   ', value: 'ignored' },
      ]),
    ).toEqual({ 'X-A': '1' });
  });

  it('round-trips a record into pairs', () => {
    expect(recordToPairs({ a: '1', b: '2' })).toEqual([
      { key: 'a', value: '1' },
      { key: 'b', value: '2' },
    ]);
    expect(recordToPairs(null)).toEqual([]);
    expect(recordToPairs(undefined)).toEqual([]);
  });
});

describe('compositionToSubmit', () => {
  const base = (over: Partial<ApiRequestComposition>): ApiRequestComposition => ({
    ...emptyComposition,
    ...over,
  });

  it('emits raw body with its content type and no form fields', () => {
    const out = compositionToSubmit(
      base({
        bodyType: 'RAW',
        contentType: 'application/json',
        rawBody: '{"x":1}',
        queryParams: [{ key: 'q', value: '1' }],
        headers: [{ key: 'X-H', value: 'v' }],
      }),
    );
    expect(out.body_type).toBe('RAW');
    expect(out.request_body).toBe('{"x":1}');
    expect(out.request_content_type).toBe('application/json');
    expect(out.form_fields).toEqual([]);
    expect(out.query_params).toEqual({ q: '1' });
    expect(out.request_headers).toEqual({ 'X-H': 'v' });
    expect(out.binary_filename).toBeNull();
  });

  it('maps form-data fields including a file part', () => {
    const out = compositionToSubmit(
      base({
        bodyType: 'FORM_DATA',
        formFields: [
          { key: 'name', type: 'TEXT', value: 'v' },
          { key: '', type: 'TEXT', value: 'dropped' },
          { key: 'doc', type: 'FILE', value: 'AAA=', filename: 'a.txt', content_type: 'text/plain' },
        ],
      }),
    );
    expect(out.request_content_type).toBeNull();
    expect(out.request_body).toBeNull();
    expect(out.form_fields).toEqual([
      { key: 'name', type: 'TEXT', value: 'v', filename: null, content_type: null },
      { key: 'doc', type: 'FILE', value: 'AAA=', filename: 'a.txt', content_type: 'text/plain' },
    ]);
  });

  it('emits binary base64 + filename', () => {
    const out = compositionToSubmit(
      base({ bodyType: 'BINARY', binaryBase64: 'QklO', binaryFilename: 'f.bin' }),
    );
    expect(out.request_body).toBe('QklO');
    expect(out.binary_filename).toBe('f.bin');
    expect(out.form_fields).toEqual([]);
  });

  it('empty raw body collapses to null', () => {
    expect(compositionToSubmit(base({ bodyType: 'RAW', rawBody: '' })).request_body).toBeNull();
  });
});

describe('newComposition', () => {
  it('returns fresh array instances so drafts do not share state', () => {
    const a = newComposition();
    const b = newComposition();
    a.headers.push({ key: 'X', value: '1' });
    expect(b.headers).toEqual([]);
    expect(emptyComposition.headers).toEqual([]);
  });
});

describe('compositionFromSaved', () => {
  it('rebuilds a RAW composition', () => {
    const c = compositionFromSaved({
      request_headers: { 'X-H': 'v' },
      query_params: { q: '1' },
      body_type: 'RAW',
      request_content_type: 'text/csv',
      request_body: 'a,b',
    });
    expect(c.bodyType).toBe('RAW');
    expect(c.contentType).toBe('text/csv');
    expect(c.rawBody).toBe('a,b');
    expect(c.headers).toEqual([{ key: 'X-H', value: 'v' }]);
    expect(c.queryParams).toEqual([{ key: 'q', value: '1' }]);
    expect(c.binaryBase64).toBeNull();
  });

  it('rebuilds form fields and routes BINARY bodies to binaryBase64', () => {
    const form = compositionFromSaved({
      body_type: 'FORM_DATA',
      form_fields: [
        { key: 'name', type: 'TEXT', value: 'v', filename: null, content_type: null },
        { key: 'doc', type: 'FILE', value: 'AAA=', filename: 'a.txt', content_type: 'text/plain' },
      ],
    });
    expect(form.formFields).toHaveLength(2);
    expect(form.formFields[1]).toEqual({
      key: 'doc',
      type: 'FILE',
      value: 'AAA=',
      filename: 'a.txt',
      content_type: 'text/plain',
    });
    expect(form.rawBody).toBe('');

    const bin = compositionFromSaved({
      body_type: 'BINARY',
      request_body: 'QklO',
      binary_filename: 'f.bin',
    });
    expect(bin.binaryBase64).toBe('QklO');
    expect(bin.binaryFilename).toBe('f.bin');
    expect(bin.rawBody).toBe('');
  });

  it('defaults an empty saved shape to a blank RAW composition', () => {
    const c = compositionFromSaved({});
    expect(c).toEqual(newComposition());
  });

  it('round-trips through compositionToSubmit', () => {
    const saved = {
      request_headers: { 'X-H': 'v' },
      query_params: { q: '1' },
      body_type: 'RAW' as const,
      request_content_type: 'application/json',
      request_body: '{"x":1}',
      form_fields: [],
      binary_filename: null,
      variable_overrides: { nonce: 'fixed' },
    };
    expect(compositionToSubmit(compositionFromSaved(saved))).toEqual(saved);
  });

  // AF-613: per-request connector-variable overrides.
  it('carries variable overrides onto the wire', () => {
    const c = newComposition();
    c.variableOverrides = [
      { key: 'nonce', value: 'fixed' },
      { key: '', value: 'dropped' },
    ];

    expect(compositionToSubmit(c).variable_overrides).toEqual({ nonce: 'fixed' });
  });

  it('submits an empty override map when none are set', () => {
    expect(compositionToSubmit(newComposition()).variable_overrides).toEqual({});
  });

  it('rebuilds overrides from a saved item', () => {
    expect(compositionFromSaved({ variable_overrides: { a: '1' } }).variableOverrides).toEqual([
      { key: 'a', value: '1' },
    ]);
  });

  it('gives each fresh composition its own overrides array', () => {
    const first = newComposition();
    first.variableOverrides.push({ key: 'a', value: '1' });

    expect(newComposition().variableOverrides).toEqual([]);
  });
});

describe('fileToBase64', () => {
  it('strips the data-url prefix', async () => {
    const file = new File(['hi'], 'hi.txt', { type: 'text/plain' });
    const base64 = await fileToBase64(file);
    expect(atob(base64)).toBe('hi');
  });
});
