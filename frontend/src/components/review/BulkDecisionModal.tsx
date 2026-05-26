import { useEffect, useState } from 'react';
import { Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import type { ReviewDecisionType } from '@/types/api';

interface BulkDecisionModalProps {
  open: boolean;
  decision: ReviewDecisionType;
  selectedCount: number;
  loading: boolean;
  onCancel: () => void;
  onConfirm: (comment: string) => void;
}

const MAX_COMMENT_LENGTH = 4000;

export function BulkDecisionModal({
  open,
  decision,
  selectedCount,
  loading,
  onCancel,
  onConfirm,
}: BulkDecisionModalProps) {
  const { t } = useTranslation();
  const [comment, setComment] = useState('');

  useEffect(() => {
    if (!open) setComment('');
  }, [open]);

  const trimmed = comment.trim();
  const commentRequired = decision !== 'APPROVED';
  const tooLong = comment.length > MAX_COMMENT_LENGTH;
  const canSubmit = !tooLong && (!commentRequired || trimmed.length > 0);

  const titleKey =
    decision === 'APPROVED'
      ? 'reviews.bulk.modal_title_approve'
      : decision === 'REJECTED'
        ? 'reviews.bulk.modal_title_reject'
        : 'reviews.bulk.modal_title_request_changes';

  const okTextKey =
    decision === 'APPROVED'
      ? 'reviews.bulk.approve_selected'
      : decision === 'REJECTED'
        ? 'reviews.bulk.reject_selected'
        : 'reviews.bulk.request_changes_selected';

  return (
    <Modal
      title={t(titleKey)}
      open={open}
      onCancel={onCancel}
      onOk={() => onConfirm(trimmed)}
      okText={t(okTextKey)}
      cancelText={t('common.cancel')}
      okButtonProps={{
        danger: decision === 'REJECTED',
        type: decision === 'APPROVED' ? 'primary' : 'default',
        disabled: !canSubmit,
        loading,
      }}
      destroyOnHidden
    >
      <p style={{ marginTop: 0 }}>
        {t('reviews.bulk.modal_subtitle', { count: selectedCount })}
      </p>
      <Input.TextArea
        aria-label={t(titleKey)}
        rows={4}
        placeholder={t('reviews.bulk.comment_placeholder')}
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
          {t('reviews.bulk.comment_required')}
        </div>
      )}
    </Modal>
  );
}
