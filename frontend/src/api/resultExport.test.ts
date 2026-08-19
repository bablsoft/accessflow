import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get } = vi.hoisted(() => ({
  get: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get },
}));

import { downloadResultExport, fetchExportDecision, resultExportKeys } from './resultExport';

const QUERY_ID = '3fa85f64-5717-4562-b3fc-2c963f66afa6';

describe('api/resultExport', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(resultExportKeys.decision(QUERY_ID)).toEqual([
      'result-export',
      'decision',
      QUERY_ID,
    ]);
  });

  it('fetchExportDecision GETs the decision endpoint', async () => {
    const decision = {
      allowed: false,
      effective_mode: 'DENY_CLASSIFIED',
      row_cap: null,
      watermark: false,
      policy_ids: ['p-1'],
      classifications_present: ['PCI'],
    };
    get.mockResolvedValueOnce({ data: decision });
    const result = await fetchExportDecision(QUERY_ID);
    expect(get).toHaveBeenCalledWith(`/api/v1/queries/${QUERY_ID}/results/export-decision`);
    expect(result.allowed).toBe(false);
    expect(result.classifications_present).toEqual(['PCI']);
  });

  it('downloadResultExport requests a blob with the format param and parses headers', async () => {
    const blob = new Blob(['a,b\r\n1,2\r\n'], { type: 'text/csv' });
    get.mockResolvedValueOnce({
      data: blob,
      headers: {
        'content-disposition':
          'attachment; filename="query-results-3fa85f64-20260818T093000Z.csv"',
        'x-accessflow-signature': 'c2ln',
        'x-accessflow-signature-algorithm': 'SHA256withRSA',
        'x-accessflow-content-sha256': 'abc123',
        'x-accessflow-export-truncated': 'true',
      },
    });

    const result = await downloadResultExport(QUERY_ID, 'CSV');

    expect(get.mock.calls[0]?.[0]).toBe(`/api/v1/queries/${QUERY_ID}/results/export`);
    expect(get.mock.calls[0]?.[1]).toEqual({ params: { format: 'CSV' }, responseType: 'blob' });
    expect(result.filename).toBe('query-results-3fa85f64-20260818T093000Z.csv');
    expect(result.signature).toBe('c2ln');
    expect(result.signatureAlgorithm).toBe('SHA256withRSA');
    expect(result.contentSha256).toBe('abc123');
    expect(result.truncated).toBe(true);
    expect(result.blob).toBe(blob);
  });

  it('falls back to a synthetic filename and null headers when absent', async () => {
    get.mockResolvedValueOnce({ data: new Blob([]), headers: {} });

    const result = await downloadResultExport(QUERY_ID, 'PDF');

    expect(result.filename).toBe(`query-results-${QUERY_ID.slice(0, 8)}.pdf`);
    expect(result.signature).toBeNull();
    expect(result.truncated).toBe(false);
  });
});
