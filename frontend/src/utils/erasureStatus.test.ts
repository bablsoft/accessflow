import { describe, expect, it } from 'vitest';
import { erasureStatusTagColor } from './erasureStatus';
import type { ErasureStatus } from '@/types/api';

describe('erasureStatusTagColor', () => {
  it('maps each status to a distinct semantic colour', () => {
    expect(erasureStatusTagColor('PENDING_SCOPE_AI')).toBe('processing');
    expect(erasureStatusTagColor('PENDING_REVIEW')).toBe('warning');
    expect(erasureStatusTagColor('APPROVED')).toBe('cyan');
    expect(erasureStatusTagColor('EXECUTED')).toBe('success');
    expect(erasureStatusTagColor('REJECTED')).toBe('error');
    expect(erasureStatusTagColor('FAILED')).toBe('error');
    expect(erasureStatusTagColor('CANCELLED')).toBe('default');
  });

  it('falls back to default for an unknown status', () => {
    expect(erasureStatusTagColor('SOMETHING_NEW' as ErasureStatus)).toBe('default');
  });
});
