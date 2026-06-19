import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get },
}));

import * as api from './compliance';
import { complianceKeys } from './compliance';

const params = { from: '2026-01-01T00:00:00Z', to: '2026-04-01T00:00:00Z' };

describe('api/compliance', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(complianceKeys.report('CLASSIFIED_ACCESS', params)).toEqual([
      'compliance',
      'report',
      'CLASSIFIED_ACCESS',
      params,
    ]);
    expect(complianceKeys.signingCertificate()).toEqual(['compliance', 'signing-certificate']);
  });

  it('fetches the classified-access report with period params', async () => {
    get.mockResolvedValue({ data: { type: 'CLASSIFIED_ACCESS' } });

    await api.fetchComplianceReport('CLASSIFIED_ACCESS', params);

    expect(get).toHaveBeenCalledWith('/api/v1/admin/compliance/reports/classified-access', {
      params: { from: params.from, to: params.to },
    });
  });

  it('routes regulatory report and includes datasourceId when set', async () => {
    get.mockResolvedValue({ data: { type: 'REGULATORY_AUDIT_TRAIL' } });

    await api.fetchComplianceReport('REGULATORY_AUDIT_TRAIL', { ...params, datasourceId: 'ds-1' });

    expect(get).toHaveBeenCalledWith('/api/v1/admin/compliance/reports/regulatory-audit-trail', {
      params: { from: params.from, to: params.to, datasourceId: 'ds-1' },
    });
  });

  it('fetches the signing certificate', async () => {
    get.mockResolvedValue({ data: { algorithm: 'SHA256withRSA', public_key_pem: 'PEM' } });

    const cert = await api.fetchSigningCertificate();

    expect(get).toHaveBeenCalledWith('/api/v1/admin/compliance/signing-certificate');
    expect(cert.algorithm).toBe('SHA256withRSA');
  });

  it('parses export headers and content-disposition filename', async () => {
    get.mockResolvedValue({
      data: new Blob(['%PDF']),
      headers: {
        'content-disposition': 'attachment; filename="compliance-classified-access-20260101T000000Z.pdf"',
        'x-accessflow-signature': 'sig123',
        'x-accessflow-signature-algorithm': 'SHA256withRSA',
        'x-accessflow-content-sha256': 'abc',
        'x-accessflow-export-truncated': 'true',
      },
    });

    const result = await api.exportComplianceReport('CLASSIFIED_ACCESS', 'PDF', params);

    expect(get).toHaveBeenCalledWith(
      '/api/v1/admin/compliance/reports/export',
      expect.objectContaining({
        responseType: 'blob',
        params: expect.objectContaining({ type: 'CLASSIFIED_ACCESS', format: 'PDF' }),
      }),
    );
    expect(result.filename).toBe('compliance-classified-access-20260101T000000Z.pdf');
    expect(result.signature).toBe('sig123');
    expect(result.signatureAlgorithm).toBe('SHA256withRSA');
    expect(result.contentSha256).toBe('abc');
    expect(result.truncated).toBe(true);
  });

  it('falls back to a synthesized filename and null headers when absent', async () => {
    get.mockResolvedValue({ data: new Blob(['a,b']), headers: {} });

    const result = await api.exportComplianceReport('REGULATORY_AUDIT_TRAIL', 'CSV', params);

    expect(result.filename).toBe('compliance-regulatory-audit-trail.csv');
    expect(result.signature).toBeNull();
    expect(result.truncated).toBe(false);
  });
});
