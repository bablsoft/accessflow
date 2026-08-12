import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { OverProvisionedGrantPage } from '@/types/api';

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }));

vi.mock('./client', () => ({ apiClient: { get: getMock } }));

const { exportOverProvisionedCsv, grantUsageKeys, listOverProvisionedGrants } =
  await import('./grantUsage');

const EMPTY: OverProvisionedGrantPage = {
  content: [],
  page: 0,
  size: 20,
  total_elements: 0,
  total_pages: 0,
};

describe('grantUsage api', () => {
  beforeEach(() => getMock.mockReset());

  it('sends the filters as query params on the report read', async () => {
    getMock.mockResolvedValue({ data: EMPTY, headers: {} });

    await listOverProvisionedGrants({ page: 1, size: 20, recommendation: ['STALE'] });

    expect(getMock).toHaveBeenCalledWith('/api/v1/admin/over-provisioned-access', {
      params: { page: 1, size: 20, recommendation: ['STALE'] },
    });
  });

  /**
   * The export must cover the whole filtered set, not the page on screen — the server applies its
   * own cap and reports truncation. Forwarding page/size would silently shrink the file.
   */
  it('strips page and size from the export while keeping every filter', async () => {
    getMock.mockResolvedValue({ data: new Blob(['a']), headers: {} });

    await exportOverProvisionedCsv({
      page: 3,
      size: 20,
      resource_kind: 'API_CONNECTOR',
      recommendation: ['NEVER_USED', 'STALE'],
      resource_id: 'r-1',
      user_id: 'u-1',
    });

    expect(getMock).toHaveBeenCalledWith('/api/v1/admin/over-provisioned-access/export.csv', {
      params: {
        resource_kind: 'API_CONNECTOR',
        recommendation: ['NEVER_USED', 'STALE'],
        resource_id: 'r-1',
        user_id: 'u-1',
      },
      responseType: 'blob',
    });
  });

  it('parses the filename out of content-disposition', async () => {
    getMock.mockResolvedValue({
      data: new Blob(['a']),
      headers: {
        'content-disposition': 'attachment; filename="over-provisioned-access-20260601T103000Z.csv"',
      },
    });

    const result = await exportOverProvisionedCsv({});

    expect(result.filename).toBe('over-provisioned-access-20260601T103000Z.csv');
  });

  it('falls back to a generic filename when the header is absent or unparseable', async () => {
    getMock.mockResolvedValue({ data: new Blob(['a']), headers: {} });
    expect((await exportOverProvisionedCsv({})).filename).toBe('over-provisioned-access.csv');

    getMock.mockResolvedValue({ data: new Blob(['a']), headers: { 'content-disposition': 'inline' } });
    expect((await exportOverProvisionedCsv({})).filename).toBe('over-provisioned-access.csv');
  });

  it('reads the truncation header case-insensitively and defaults to false', async () => {
    getMock.mockResolvedValue({
      data: new Blob(['a']),
      headers: { 'x-accessflow-export-truncated': 'TRUE' },
    });
    expect((await exportOverProvisionedCsv({})).truncated).toBe(true);

    getMock.mockResolvedValue({
      data: new Blob(['a']),
      headers: { 'x-accessflow-export-truncated': 'false' },
    });
    expect((await exportOverProvisionedCsv({})).truncated).toBe(false);

    getMock.mockResolvedValue({ data: new Blob(['a']), headers: {} });
    expect((await exportOverProvisionedCsv({})).truncated).toBe(false);
  });

  it('builds stable, filter-scoped query keys', () => {
    expect(grantUsageKeys.all).toEqual(['grant-usage']);
    expect(grantUsageKeys.reports()).toEqual(['grant-usage', 'report']);
    expect(grantUsageKeys.report({ page: 0 })).toEqual(['grant-usage', 'report', { page: 0 }]);
    expect(grantUsageKeys.report({ page: 1 })).not.toEqual(grantUsageKeys.report({ page: 0 }));
  });
});
