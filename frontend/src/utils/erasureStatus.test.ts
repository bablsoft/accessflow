import { describe, expect, it } from 'vitest';
import { erasureStatusTagColor } from './erasureStatus';
import type { ErasureStatus } from '@/types/api';

describe('erasureStatusTagColor', () => {
  it('maps each status to a distinct semantic colour', () => {
    expect(erasureStatusTagColor('PENDING_SCOPE_AI')).toBe('info');
    expect(erasureStatusTagColor('PENDING_REVIEW')).toBe('warning');
    expect(erasureStatusTagColor('APPROVED')).toBe('accent');
    expect(erasureStatusTagColor('EXECUTED')).toBe('success');
    expect(erasureStatusTagColor('REJECTED')).toBe('danger');
    expect(erasureStatusTagColor('FAILED')).toBe('danger');
    expect(erasureStatusTagColor('CANCELLED')).toBe('neutral');
  });

  it('falls back to default for an unknown status', () => {
    expect(erasureStatusTagColor('SOMETHING_NEW' as ErasureStatus)).toBe('neutral');
  });
});
