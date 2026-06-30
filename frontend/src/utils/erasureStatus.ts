import type { ErasureStatus } from '@/types/api';

/**
 * Maps an erasure-request status to an Ant Design Tag colour. Colour is paired with the localized
 * status label (never colour alone), so this is presentational only.
 */
export function erasureStatusTagColor(status: ErasureStatus): string {
  switch (status) {
    case 'PENDING_SCOPE_AI':
      return 'processing';
    case 'PENDING_REVIEW':
      return 'warning';
    case 'APPROVED':
      return 'cyan';
    case 'EXECUTED':
      return 'success';
    case 'REJECTED':
    case 'FAILED':
      return 'error';
    case 'CANCELLED':
      return 'default';
    default:
      return 'default';
  }
}
