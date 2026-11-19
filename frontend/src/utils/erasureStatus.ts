import type { ErasureStatus } from '@/types/api';
import type { StatusTone } from '@/components/layout/StatusTag';

/**
 * Maps an erasure-request status to a `StatusTag` tone. Colour is paired with the localized
 * status label (never colour alone), so this is presentational only.
 */
export function erasureStatusTagColor(status: ErasureStatus): StatusTone {
  switch (status) {
    case 'PENDING_SCOPE_AI':
      return 'info';
    case 'PENDING_REVIEW':
      return 'warning';
    case 'APPROVED':
      return 'accent';
    case 'EXECUTED':
      return 'success';
    case 'REJECTED':
    case 'FAILED':
      return 'danger';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'neutral';
  }
}
