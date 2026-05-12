import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, delete: del },
}));

import * as customDriversApi from './customDrivers';

const fixture = {
  id: 'driver-1',
  organization_id: 'org-1',
  vendor_name: 'Acme',
  target_db_type: 'ORACLE',
  driver_class: 'oracle.jdbc.OracleDriver',
  jar_filename: 'ojdbc.jar',
  jar_sha256: 'a'.repeat(64),
  jar_size_bytes: 1024,
  uploaded_by_user_id: 'u-1',
  uploaded_by_display_name: 'Admin',
  created_at: '2026-05-04T10:15:00Z',
};

describe('customDrivers API', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
  });

  it('listCustomDrivers unwraps the drivers array', async () => {
    get.mockResolvedValueOnce({ data: { drivers: [fixture] } });
    const result = await customDriversApi.listCustomDrivers();
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/drivers');
    expect(result).toEqual([fixture]);
  });

  it('uploadCustomDriver posts FormData with lower-cased SHA-256', async () => {
    post.mockResolvedValueOnce({ data: fixture });
    const jar = new File([new Uint8Array([1, 2, 3])], 'driver.jar');

    const result = await customDriversApi.uploadCustomDriver({
      jar,
      vendor_name: 'Acme',
      target_db_type: 'CUSTOM',
      driver_class: 'com.acme.JdbcDriver',
      expected_sha256: 'ABCDEF'.repeat(10) + 'AB'.repeat(2),
    });

    expect(post).toHaveBeenCalledTimes(1);
    const call = post.mock.calls[0]!;
    expect(call[0]).toBe('/api/v1/datasources/drivers');
    const formData = call[1] as FormData;
    expect(formData.get('vendor_name')).toBe('Acme');
    expect(formData.get('target_db_type')).toBe('CUSTOM');
    expect(formData.get('driver_class')).toBe('com.acme.JdbcDriver');
    expect((formData.get('expected_sha256') as string)).toBe(
      ('ABCDEF'.repeat(10) + 'AB'.repeat(2)).toLowerCase(),
    );
    expect(formData.get('jar')).toBeInstanceOf(File);
    expect(result).toEqual(fixture);
  });

  it('deleteCustomDriver calls DELETE on the resource path', async () => {
    del.mockResolvedValueOnce({});
    await customDriversApi.deleteCustomDriver('driver-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/drivers/driver-1');
  });

  it('customDriverKeys returns a stable hierarchy', () => {
    expect(customDriversApi.customDriverKeys.all).toEqual(['custom-drivers']);
    expect(customDriversApi.customDriverKeys.lists()).toEqual(['custom-drivers', 'list']);
    expect(customDriversApi.customDriverKeys.detail('x')).toEqual([
      'custom-drivers',
      'detail',
      'x',
    ]);
  });
});
