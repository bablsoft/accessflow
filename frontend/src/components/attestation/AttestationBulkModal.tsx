import { useEffect, useState } from 'react';
import { Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import type { AttestationItemDecision } from '@/types/api';

export type AttestationBulkDecision = Extract<AttestationItemDecision, 'CERTIFIED' | 'REVOKED'>;

interface AttestationBulkModalProps {
  open: boolean;
  decision: AttestationBulkDecision;
  selectedCount: number;
  loading: boolean;
  onCancel: () => void;
  onConfirm: (comment: string) => void;
}

const MAX_COMMENT_LENGTH = 4000;

export function AttestationBulkModal({
  open,
  decision,
  selectedCount,
  loading,
  onCancel,
  onConfirm,
}: AttestationBulkModalProps) {
  const { t } = useTranslation();
  const [comment, setComment] = useState('');

  useEffect(() => {
    if (!open) setComment('');
  }, [open]);

  const trimmed = comment.trim();
  const commentRequired = decision === 'REVOKED';
  const tooLong = comment.length > MAX_COMMENT_LENGTH;
  const canSubmit = !tooLong && (!commentRequired || trimmed.length > 0);

  const titleKey =
    decision === 'CERTIFIED'
      ? 'attestation.worklist.bulk.modal_title_certify'
      : 'attestation.worklist.bulk.modal_title_revoke';
  const okTextKey =
    decision === 'CERTIFIED'
      ? 'attestation.worklist.bulk.certify_selected'
      : 'attestation.worklist.bulk.revoke_selected';

  return (
    <Modal
      title={t(titleKey)}
      open={open}
      onCancel={onCancel}
      onOk={() => onConfirm(trimmed)}
      okText={t(okTextKey)}
      cancelText={t('common.cancel')}
      okButtonProps={{
        danger: decision === 'REVOKED',
        type: decision === 'CERTIFIED' ? 'primary' : 'default',
        disabled: !canSubmit,
        loading,
      }}
      destroyOnHidden
    >
      <p style={{ marginTop: 0 }}>
        {t('attestation.worklist.bulk.modal_subtitle', { count: selectedCount })}
      </p>
      <Input.TextArea
        aria-label={t(titleKey)}
        rows={4}
        placeholder={t('attestation.worklist.bulk.comment_placeholder')}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        maxLength={MAX_COMMENT_LENGTH}
        showCount
      />
      {commentRequired && !trimmed && (
        <div
          role="alert"
          style={{ marginTop: 8, color: 'var(--af-color-error, #cf1322)', fontSize: 12 }}
        >
          {t('attestation.worklist.comment_required')}
        </div>
      )}
    </Modal>
  );
}
