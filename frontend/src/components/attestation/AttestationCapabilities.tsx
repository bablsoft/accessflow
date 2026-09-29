import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import type { AttestationItem } from '@/types/api';
import { AttestationRowLimitNote } from './AttestationRowLimitNote';

/**
 * The capability chips of a snapshotted grant. Shared by the reviewer worklist and the admin
 * campaign detail page, which carried byte-identical copies before #625 added a column to both.
 * Beneath them, the row limit that actually applies when it differs from the configured one (#1084).
 */
export function AttestationCapabilities({ item }: { item: AttestationItem }) {
  const { t } = useTranslation();
  const caps: string[] = [];
  if (item.can_read) caps.push(t('attestation.detail.cap_read'));
  if (item.can_write) caps.push(t('attestation.detail.cap_write'));
  if (item.can_ddl) caps.push(t('attestation.detail.cap_ddl'));
  if (item.can_break_glass) caps.push(t('attestation.detail.cap_break_glass'));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 6 }}>
        {caps.map((c) => (
          <Pill key={c} size="sm">
            {c}
          </Pill>
        ))}
      </span>
      <AttestationRowLimitNote item={item} />
    </div>
  );
}
