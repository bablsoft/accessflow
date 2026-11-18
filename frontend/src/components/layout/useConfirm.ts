import type { ReactNode } from 'react';
import { useCallback } from 'react';
import { App } from 'antd';
import { useTranslation } from 'react-i18next';

export interface ConfirmDangerOptions {
  title: ReactNode;
  content?: ReactNode;
  /** Label of the destructive button; defaults to "Delete". */
  okText?: string;
  onOk: () => void | Promise<unknown>;
}

/** The one confirmation for destructive actions: a modal with a red confirm button. */
export function useConfirm() {
  const { modal } = App.useApp();
  const { t } = useTranslation();
  return useCallback(
    ({ title, content, okText, onOk }: ConfirmDangerOptions) =>
      modal.confirm({
        title,
        content,
        okText: okText ?? t('common.delete'),
        cancelText: t('common.cancel'),
        okButtonProps: { danger: true },
        onOk,
      }),
    [modal, t],
  );
}
