import { useEffect } from 'react';
import { App, Form, Input, Modal, Select } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { createQueryTemplate, queryTemplateKeys } from '@/api/queryTemplates';
import type { QueryTemplateVisibility } from '@/types/api';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';

interface SaveTemplateModalProps {
  open: boolean;
  sql: string;
  datasourceId: string | null;
  onClose: () => void;
}

interface FormValues {
  name: string;
  description?: string;
  visibility: QueryTemplateVisibility;
  tags?: string[];
  pinned: boolean;
}

export function SaveTemplateModal({ open, sql, datasourceId, onClose }: SaveTemplateModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<FormValues>();

  useEffect(() => {
    if (open) {
      form.setFieldsValue({ visibility: 'PRIVATE', pinned: true });
    } else {
      form.resetFields();
    }
  }, [open, form]);

  const mutation = useMutation({
    mutationFn: (values: FormValues) =>
      createQueryTemplate({
        name: values.name.trim(),
        body: sql,
        description: values.description?.trim() || null,
        tags: values.tags ?? [],
        datasource_id: values.pinned ? datasourceId : null,
        visibility: values.visibility,
      }),
    onSuccess: () => {
      message.success(t('editor.templates.save_success'));
      void queryClient.invalidateQueries({ queryKey: queryTemplateKeys.all });
      onClose();
      form.resetFields();
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  return (
    <Modal
      title={t('editor.templates.save_modal_title')}
      open={open}
      destroyOnHidden
      onCancel={() => {
        if (mutation.isPending) return;
        onClose();
        form.resetFields();
      }}
      onOk={() => {
        form
          .validateFields()
          .then((values) => mutation.mutate(values))
          .catch(() => {});
      }}
      okButtonProps={{ loading: mutation.isPending }}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
    >
      <Form layout="vertical" form={form} preserve={false}>
        <Form.Item
          name="name"
          label={t('editor.templates.fields.name')}
          rules={[
            { required: true, message: t('editor.templates.validation.name_required') },
            { max: 128, message: t('editor.templates.validation.name_size') },
          ]}
        >
          <Input
            maxLength={128}
            autoFocus
            placeholder={t('editor.templates.fields.name_placeholder')}
          />
        </Form.Item>
        <Form.Item
          name="description"
          label={t('editor.templates.fields.description')}
          rules={[{ max: 1000, message: t('editor.templates.validation.description_max') }]}
        >
          <Input.TextArea
            rows={2}
            maxLength={1000}
            placeholder={t('editor.templates.fields.description_placeholder')}
          />
        </Form.Item>
        <Form.Item
          name="visibility"
          label={t('editor.templates.fields.visibility')}
          rules={[{ required: true, message: t('editor.templates.validation.visibility_required') }]}
        >
          <Select
            options={[
              {
                value: 'PRIVATE',
                label: t('editor.templates.visibility_private'),
              },
              { value: 'TEAM', label: t('editor.templates.visibility_team') },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="tags"
          label={t('editor.templates.fields.tags')}
          rules={[
            {
              validator: async (_, value: string[] | undefined) => {
                if (!value) return;
                if (value.length > 10) {
                  throw new Error(t('editor.templates.validation.tags_max'));
                }
                if (value.some((tag) => (tag ?? '').length > 32)) {
                  throw new Error(t('editor.templates.validation.tag_size'));
                }
              },
            },
          ]}
        >
          <Select
            mode="tags"
            tokenSeparators={[',']}
            placeholder={t('editor.templates.fields.tags_placeholder')}
          />
        </Form.Item>
        {datasourceId ? (
          <Form.Item
            name="pinned"
            valuePropName="checked"
            label={t('editor.templates.fields.pin_to_datasource')}
            extra={t('editor.templates.fields.pin_to_datasource_help')}
          >
            <Input type="checkbox" style={{ width: 16, height: 16 }} />
          </Form.Item>
        ) : null}
      </Form>
    </Modal>
  );
}
