import type { ApiBodyType, ApiFormField } from '@/types/api';

export const MAX_BODY_BYTES = 5 * 1024 * 1024;

export interface KeyValuePair {
  key: string;
  value: string;
}

export interface FormFieldRow {
  key: string;
  type: 'TEXT' | 'FILE';
  value: string;
  filename?: string | null;
  content_type?: string | null;
}

export interface ApiRequestComposition {
  queryParams: KeyValuePair[];
  headers: KeyValuePair[];
  bodyType: ApiBodyType;
  contentType: string;
  rawBody: string;
  formFields: FormFieldRow[];
  binaryFilename: string | null;
  binaryBase64: string | null;
  /** AF-613: per-request overrides for connector variables marked overridable. */
  variableOverrides: KeyValuePair[];
}

export const emptyComposition: ApiRequestComposition = {
  queryParams: [],
  headers: [],
  bodyType: 'RAW',
  contentType: 'application/json',
  rawBody: '',
  formFields: [],
  binaryFilename: null,
  binaryBase64: null,
  variableOverrides: [],
};

/** A fresh composition with unshared array instances (emptyComposition shares its arrays). */
export function newComposition(): ApiRequestComposition {
  return { ...emptyComposition, queryParams: [], headers: [], formFields: [], variableOverrides: [] };
}

/** The composition fields as they come back on a saved item (group member, snake_case wire). */
export interface SavedComposition {
  request_headers?: Record<string, string> | null;
  query_params?: Record<string, string> | null;
  body_type?: ApiBodyType | null;
  request_content_type?: string | null;
  request_body?: string | null;
  form_fields?: ApiFormField[] | null;
  binary_filename?: string | null;
  variable_overrides?: Record<string, string> | null;
}

/** Rebuilds editor state from a saved item so `compositionToSubmit(compositionFromSaved(x)) ≈ x`. */
export function compositionFromSaved(saved: SavedComposition): ApiRequestComposition {
  const bodyType = saved.body_type ?? 'RAW';
  return {
    queryParams: recordToPairs(saved.query_params),
    headers: recordToPairs(saved.request_headers),
    bodyType,
    contentType:
      bodyType === 'RAW' ? (saved.request_content_type ?? 'application/json') : 'application/json',
    rawBody: bodyType === 'RAW' ? (saved.request_body ?? '') : '',
    formFields: (saved.form_fields ?? []).map((f) => ({
      key: f.key,
      type: f.type,
      value: f.value,
      filename: f.filename ?? null,
      content_type: f.content_type ?? null,
    })),
    binaryFilename: bodyType === 'BINARY' ? (saved.binary_filename ?? null) : null,
    binaryBase64: bodyType === 'BINARY' ? (saved.request_body ?? null) : null,
    variableOverrides: recordToPairs(saved.variable_overrides),
  };
}

/** Drops blank-key rows and collapses pairs to a plain object for the wire. */
export function pairsToRecord(pairs: KeyValuePair[]): Record<string, string> {
  const record: Record<string, string> = {};
  for (const { key, value } of pairs) {
    if (key.trim()) record[key.trim()] = value;
  }
  return record;
}

export function recordToPairs(record: Record<string, string> | undefined | null): KeyValuePair[] {
  return Object.entries(record ?? {}).map(([key, value]) => ({ key, value }));
}

/** Strips the `data:...;base64,` prefix a FileReader data URL carries. */
export function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result);
      resolve(result.slice(result.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

/** Translates the composer state into the body/param wire fields of a SubmitApiRequestInput. */
export function compositionToSubmit(c: ApiRequestComposition): {
  query_params: Record<string, string>;
  request_headers: Record<string, string>;
  body_type: ApiBodyType;
  request_content_type: string | null;
  request_body: string | null;
  form_fields: ApiFormField[];
  binary_filename: string | null;
  variable_overrides: Record<string, string>;
} {
  const formFields: ApiFormField[] =
    c.bodyType === 'FORM_DATA' || c.bodyType === 'FORM_URLENCODED'
      ? c.formFields
          .filter((f) => f.key.trim())
          .map((f) => ({
            key: f.key.trim(),
            type: f.type,
            value: f.value,
            filename: f.filename ?? null,
            content_type: f.content_type ?? null,
          }))
      : [];
  return {
    query_params: pairsToRecord(c.queryParams),
    request_headers: pairsToRecord(c.headers),
    body_type: c.bodyType,
    request_content_type: c.bodyType === 'RAW' ? c.contentType || null : null,
    request_body:
      c.bodyType === 'RAW' ? c.rawBody || null : c.bodyType === 'BINARY' ? c.binaryBase64 : null,
    form_fields: formFields,
    binary_filename: c.bodyType === 'BINARY' ? c.binaryFilename : null,
    variable_overrides: pairsToRecord(c.variableOverrides),
  };
}
