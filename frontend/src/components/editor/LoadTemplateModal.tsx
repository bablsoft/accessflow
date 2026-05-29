import { useEffect, useMemo } from 'react';
import { Form, Input, Modal } from 'antd';
import { useTranslation } from 'react-i18next';
import type { QueryTemplate } from '@/types/api';
import { extractPlaceholders, substitutePlaceholders } from '@/utils/sqlPlaceholders';

interface LoadTemplateModalProps {
  template: QueryTemplate | null;
  onCancel: () => void;
  onConfirm: (renderedSql: string) => void;
}

export function LoadTemplateModal({ template, onCancel, onConfirm }: LoadTemplateModalProps) {
  const { t } = useTranslation();
  const [form] = Form.useForm<Record<string, string>>();

  const placeholders = useMemo(
    () => (template ? extractPlaceholders(template.body) : []),
    [template],
  );
  const open = template !== null && placeholders.length > 0;

  // Auto-load when there are no placeholders.
  useEffect(() => {
    if (template && placeholders.length === 0) {
      onConfirm(template.body);
    }
  }, [template, placeholders, onConfirm]);

  useEffect(() => {
    if (open) {
      form.resetFields();
    }
  }, [open, template, form]);

  if (!template || placeholders.length === 0) {
    return null;
  }

  return (
    <Modal
      title={t('editor.templates.load_modal_title')}
      open={open}
      destroyOnHidden
      onCancel={onCancel}
      onOk={() => {
        form
          .validateFields()
          .then((values) => {
            const rendered = substitutePlaceholders(template.body, values);
            onConfirm(rendered);
          })
          .catch(() => {});
      }}
      okText={t('editor.templates.load_button')}
      cancelText={t('common.cancel')}
    >
      <p className="muted" style={{ marginTop: 0 }}>
        {t('editor.templates.load_modal_intro', { count: placeholders.length })}
      </p>
      <Form layout="vertical" form={form} preserve={false}>
        {placeholders.map((name) => (
          <Form.Item
            key={name}
            name={name}
            label={<code>:{name}</code>}
            rules={[
              {
                required: true,
                message: t('editor.templates.validation.placeholder_required', { name }),
              },
            ]}
          >
            <Input style={{ fontFamily: 'var(--font-mono)' }} />
          </Form.Item>
        ))}
      </Form>
    </Modal>
  );
}
