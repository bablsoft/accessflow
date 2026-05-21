import { useEffect, useState } from 'react';
import { Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';

interface RejectModalProps {
  open: boolean;
  loading: boolean;
  onCancel: () => void;
  onConfirm: (comment: string) => void;
}

export function RejectModal({ open, loading, onCancel, onConfirm }: RejectModalProps) {
  const { t } = useTranslation();
  const [comment, setComment] = useState('');

  // Each open resets the textarea — without this the previous query's draft
  // would leak into the next reject. We only clear when the modal closes so
  // the loading-while-still-open state preserves what the user typed.
  useEffect(() => {
    if (!open) setComment('');
  }, [open]);

  const trimmed = comment.trim();
  return (
    <Modal
      title={t('reviews.reject_modal_title')}
      open={open}
      onCancel={onCancel}
      onOk={() => onConfirm(trimmed)}
      okText={t('reviews.reject_modal_confirm')}
      cancelText={t('common.cancel')}
      okButtonProps={{ danger: true, disabled: !trimmed, loading }}
      destroyOnHidden
    >
      <Input.TextArea
        aria-label={t('reviews.reject_modal_title')}
        rows={4}
        placeholder={t('reviews.reject_modal_placeholder')}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
      />
    </Modal>
  );
}
