import { useCallback, useState } from 'react';
import { App, Form, Input, Modal, Select, Upload, Button } from 'antd';
import type { UploadFile } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { customDriverKeys, uploadCustomDriver } from '@/api/customDrivers';
import { customDriverErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { DbType } from '@/types/api';

interface FormValues {
  vendor_name: string;
  target_db_type: DbType;
  driver_class: string;
  expected_sha256: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
}

const MAX_BYTES = 50 * 1024 * 1024;

export function CustomDriverUploadModal({ open, onClose }: Props) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<FormValues>();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [fileError, setFileError] = useState<string | null>(null);

  const close = useCallback(() => {
    form.resetFields();
    setFiles([]);
    setFileError(null);
    onClose();
  }, [form, onClose]);

  const upload = useMutation({
    mutationFn: async (values: FormValues) => {
      const fileEntry = files[0];
      const blob = fileEntry?.originFileObj;
      if (!blob) {
        throw new Error('missing-file');
      }
      const file = blob instanceof File
        ? blob
        : new File([blob], fileEntry?.name ?? 'driver.jar');
      return uploadCustomDriver({
        jar: file,
        vendor_name: values.vendor_name,
        target_db_type: values.target_db_type,
        driver_class: values.driver_class,
        expected_sha256: values.expected_sha256,
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: customDriverKeys.lists() });
      void queryClient.invalidateQueries({ queryKey: ['datasources', 'types'] });
      message.success(t('drivers.upload.success'));
      close();
    },
    onError: (err: unknown) => {
      if (err instanceof Error && err.message === 'missing-file') {
        setFileError(t('drivers.upload.missing_jar'));
        return;
      }
      showApiError(message, err, customDriverErrorMessage);
    },
  });

  const submit = useCallback(
    (values: FormValues) => {
      if (files.length === 0) {
        setFileError(t('drivers.upload.missing_jar'));
        return;
      }
      upload.mutate(values);
    },
    [files, t, upload],
  );

  return (
    <Modal
      open={open}
      onCancel={() => (upload.isPending ? undefined : close())}
      maskClosable={!upload.isPending}
      title={t('drivers.upload.title')}
      width={560}
      footer={[
        <Button key="cancel" onClick={close} disabled={upload.isPending}>
          {t('drivers.upload.cancel')}
        </Button>,
        <Button
          key="submit"
          type="primary"
          loading={upload.isPending}
          onClick={() => form.submit()}
        >
          {upload.isPending ? t('drivers.upload.submitting') : t('drivers.upload.submit_button')}
        </Button>,
      ]}
    >
      <p className="muted" style={{ fontSize: 13, marginTop: 0 }}>
        {t('drivers.upload.subtitle')}
      </p>
      <Form<FormValues>
        layout="vertical"
        form={form}
        onFinish={submit}
        initialValues={{ target_db_type: 'CUSTOM' as DbType }}
      >
        <Form.Item
          label={t('drivers.upload.jar_label')}
          required
          extra={t('drivers.upload.jar_help')}
          validateStatus={fileError ? 'error' : undefined}
          help={fileError ?? undefined}
        >
          <Upload.Dragger
            multiple={false}
            maxCount={1}
            accept=".jar"
            beforeUpload={(file) => {
              setFileError(null);
              if (!file.name.toLowerCase().endsWith('.jar')) {
                setFileError(t('drivers.upload.invalid_jar_extension'));
                return Upload.LIST_IGNORE;
              }
              if (file.size > MAX_BYTES) {
                setFileError(
                  t('errors.custom_driver_too_large', { maxMb: 50 }),
                );
                return Upload.LIST_IGNORE;
              }
              return false; // prevent auto-upload; we POST manually
            }}
            onChange={({ fileList }) => setFiles(fileList.slice(-1))}
            fileList={files}
            onRemove={() => {
              setFiles([]);
              setFileError(null);
            }}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">{t('drivers.upload.jar_select_button')}</p>
          </Upload.Dragger>
        </Form.Item>
        <Form.Item
          label={t('drivers.upload.vendor_label')}
          name="vendor_name"
          rules={[
            { required: true, message: t('drivers.validation.vendor_required') },
            { max: 100, message: t('drivers.validation.vendor_max') },
          ]}
        >
          <Input placeholder={t('drivers.upload.vendor_placeholder')} maxLength={100} />
        </Form.Item>
        <Form.Item
          label={t('drivers.upload.target_db_type_label')}
          name="target_db_type"
          extra={t('drivers.upload.target_db_type_help')}
          rules={[{ required: true }]}
        >
          <Select
            options={[
              { value: 'CUSTOM', label: 'Custom / Dynamic JDBC' },
              { value: 'POSTGRESQL', label: 'PostgreSQL' },
              { value: 'MYSQL', label: 'MySQL' },
              { value: 'MARIADB', label: 'MariaDB' },
              { value: 'ORACLE', label: 'Oracle Database' },
              { value: 'MSSQL', label: 'Microsoft SQL Server' },
            ]}
          />
        </Form.Item>
        <Form.Item
          label={t('drivers.upload.driver_class_label')}
          name="driver_class"
          extra={t('drivers.upload.driver_class_help')}
          rules={[
            { required: true, message: t('drivers.validation.driver_class_required') },
            { max: 255, message: t('drivers.validation.driver_class_max') },
            {
              pattern: /^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$/,
              message: t('drivers.validation.driver_class_pattern'),
            },
          ]}
        >
          <Input placeholder={t('drivers.upload.driver_class_placeholder')} />
        </Form.Item>
        <Form.Item
          label={t('drivers.upload.sha256_label')}
          name="expected_sha256"
          extra={t('drivers.upload.sha256_help')}
          rules={[
            { required: true, message: t('drivers.validation.sha256_required') },
            {
              pattern: /^[a-fA-F0-9]{64}$/,
              message: t('drivers.validation.sha256_pattern'),
            },
          ]}
        >
          <Input
            className="mono"
            placeholder={t('drivers.upload.sha256_placeholder')}
            maxLength={64}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
