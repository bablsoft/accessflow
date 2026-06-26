import { useEffect, useState } from 'react';
import { Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';

interface AttestationRevokeModalProps {
  open: boolean;
  loading: boolean;
  onCancel: () => void;
  onConfirm: (comment: string) => void;
}

export function AttestationRevokeModal({
  open,
  loading,
  onCancel,
  onConfirm,
}: AttestationRevokeModalProps) {
  const { t } = useTranslation();
  const [comment, setComment] = useState('');

  useEffect(() => {
    if (!open) setComment('');
  }, [open]);

  const trimmed = comment.trim();
  return (
    <Modal
      title={t('attestation.worklist.revoke_modal_title')}
      open={open}
      onCancel={onCancel}
      onOk={() => onConfirm(trimmed)}
      okText={t('attestation.worklist.revoke_modal_confirm')}
      cancelText={t('common.cancel')}
      okButtonProps={{ danger: true, disabled: !trimmed, loading }}
      destroyOnHidden
    >
      <Input.TextArea
        aria-label={t('attestation.worklist.revoke_modal_title')}
        rows={4}
        placeholder={t('attestation.worklist.revoke_modal_placeholder')}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
      />
    </Modal>
  );
}
