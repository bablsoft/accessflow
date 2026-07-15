import { PERMISSIONS, type Permission } from '@/utils/permissions';

/**
 * Test fixture (AF-522): representative permission sets for the built-in system roles,
 * mirroring the backend's seeded role → permission mapping closely enough for UI gating.
 * Production code never uses this — gating always reads the user's `permissions` array.
 */
export const SYSTEM_ROLE_PERMISSIONS: Record<
  'ADMIN' | 'REVIEWER' | 'ANALYST' | 'READONLY' | 'AUDITOR',
  Permission[]
> = {
  ADMIN: [...PERMISSIONS],
  REVIEWER: [
    'QUERY_SUBMIT_SELECT',
    'QUERY_SUBMIT_DML',
    'QUERY_VIEW_ALL',
    'QUERY_REVIEW',
    'API_REQUEST_REVIEW',
    'ACCESS_REQUEST_REVIEW',
    'ATTESTATION_REVIEW',
    'ERASURE_REVIEW',
  ],
  ANALYST: ['QUERY_SUBMIT_SELECT', 'QUERY_SUBMIT_DML'],
  READONLY: ['QUERY_SUBMIT_SELECT'],
  AUDITOR: [
    'COMPLIANCE_REPORT_VIEW',
    'AUDIT_LOG_VIEW',
    'BREAK_GLASS_VIEW',
    'ANOMALY_VIEW',
    'ATTESTATION_EVIDENCE_EXPORT',
  ],
};
