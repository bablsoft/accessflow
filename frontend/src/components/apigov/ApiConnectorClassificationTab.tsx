import { useMemo, useState } from 'react';
import { App, Button, Form, Input, Modal, Select, Switch, Table, Tag } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import {
  apiConnectorClassificationKeys,
  createApiConnectorClassificationTags,
  deleteApiConnectorClassificationTag,
  listApiConnectorClassificationTags,
} from '@/api/apiConnectorClassifications';
import { apiConnectorKeys, listApiOperations } from '@/api/apiConnectors';
import { apiConnectorMaskingPolicyKeys } from '@/api/apiConnectorMaskingPolicies';
import {
  API_MASKING_MATCHER_TYPES,
  apiMaskingMatcherTypeLabel,
  DATA_CLASSIFICATIONS,
  dataClassificationLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import { ApiConnectorClassificationDerivationPanel } from './ApiConnectorClassificationDerivationPanel';
import type {
  ApiConnectorClassificationTag,
  ApiMaskingMatcherType,
  CreateApiConnectorClassificationTagInput,
  DataClassification,
} from '@/types/api';

export function ApiConnectorClassificationTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [modalOpen, setModalOpen] = useState(false);

  const tagsQuery = useQuery({
    queryKey: apiConnectorClassificationKeys.list(connectorId),
    queryFn: () => listApiConnectorClassificationTags(connectorId),
  });
  const tags = tagsQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (tagId: string) => deleteApiConnectorClassificationTag(connectorId, tagId),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: apiConnectorClassificationKeys.list(connectorId),
      });
      void queryClient.invalidateQueries({
        queryKey: apiConnectorClassificationKeys.derivation(connectorId),
      });
      message.success(t('apiGov.settings.classification.delete_success'));
    },
    onError: () => {
      message.error(t('apiGov.settings.classification.delete_error'));
    },
  });

  const onDelete = (tag: ApiConnectorClassificationTag) => {
    modal.confirm({
      title: t('apiGov.settings.classification.delete_confirm_title'),
      content: t('apiGov.settings.classification.delete_confirm_body'),
      okType: 'danger',
      okText: t('apiGov.settings.classification.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(tag.id),
    });
  };

  return (
    <div style={{ padding: 28 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: 16,
          gap: 16,
        }}
      >
        <div>
          <div style={{ fontWeight: 600 }}>{t('apiGov.settings.classification.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('apiGov.settings.classification.description')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          {t('apiGov.settings.classification.add')}
        </Button>
      </div>

      {!tagsQuery.isLoading && tags.length === 0 ? (
        <EmptyState
          title={t('apiGov.settings.classification.empty_title')}
          description={t('apiGov.settings.classification.empty_description')}
        />
      ) : (
        <Table<ApiConnectorClassificationTag>
          rowKey="id"
          size="middle"
          loading={tagsQuery.isLoading}
          dataSource={tags}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('apiGov.settings.classification.col_field'),
              render: (_v, tag) => (
                <span className="mono" style={{ fontSize: 12 }}>
                  {tag.operation_id ? `${tag.operation_id} · ` : ''}
                  {tag.field_ref}
                </span>
              ),
            },
            {
              title: t('apiGov.settings.classification.col_matcher'),
              dataIndex: 'matcher_type',
              width: 130,
              render: (v: ApiMaskingMatcherType) => <Tag>{apiMaskingMatcherTypeLabel(t, v)}</Tag>,
            },
            {
              title: t('apiGov.settings.classification.col_classification'),
              dataIndex: 'classification',
              width: 130,
              render: (v: DataClassification) => <Tag color="volcano">{dataClassificationLabel(t, v)}</Tag>,
            },
            {
              title: t('apiGov.settings.classification.col_note'),
              dataIndex: 'note',
              render: (v: string | null) =>
                v ? <span style={{ fontSize: 12 }}>{v}</span> : <span className="muted">—</span>,
            },
            {
              title: t('apiGov.settings.classification.col_actions'),
              width: 80,
              align: 'right',
              render: (_v, tag) => (
                <Button
                  size="small"
                  type="text"
                  danger
                  icon={<DeleteOutlined />}
                  aria-label={t('apiGov.settings.classification.delete')}
                  onClick={() => onDelete(tag)}
                  disabled={deleteMutation.isPending}
                />
              ),
            },
          ]}
        />
      )}

      <ApiConnectorClassificationDerivationPanel connectorId={connectorId} />

      <ClassificationTagModal
        open={modalOpen}
        connectorId={connectorId}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

interface ClassificationFormValues {
  matcher_type: ApiMaskingMatcherType;
  operation_id?: string;
  field_ref: string;
  classifications: DataClassification[];
  note?: string;
  apply_masking: boolean;
}

function ClassificationTagModal({
  open,
  connectorId,
  onClose,
}: {
  open: boolean;
  connectorId: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ClassificationFormValues>();
  const matcherType = Form.useWatch('matcher_type', form);

  const operationsQuery = useQuery({
    queryKey: apiConnectorKeys.operations(connectorId),
    queryFn: () => listApiOperations(connectorId),
    enabled: open,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const operationOptions = useMemo(
    () =>
      (operationsQuery.data ?? []).map((op) => ({
        value: op.operation_id,
        label: `${op.verb} ${op.path}`,
      })),
    [operationsQuery.data],
  );

  const saveMutation = useMutation({
    mutationFn: (input: CreateApiConnectorClassificationTagInput) =>
      createApiConnectorClassificationTags(connectorId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: apiConnectorClassificationKeys.list(connectorId),
      });
      void queryClient.invalidateQueries({
        queryKey: apiConnectorClassificationKeys.derivation(connectorId),
      });
      // Tagging a field may auto-create a masking policy.
      void queryClient.invalidateQueries({
        queryKey: apiConnectorMaskingPolicyKeys.list(connectorId),
      });
      message.success(t('apiGov.settings.classification.save_success'));
      form.resetFields();
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, () => t('apiGov.settings.classification.save_error'));
    },
  });

  const onFinish = (values: ClassificationFormValues) => {
    saveMutation.mutate({
      matcher_type: values.matcher_type,
      operation_id: values.matcher_type === 'SCHEMA_FIELD' ? values.operation_id : undefined,
      field_ref: values.field_ref.trim(),
      classifications: values.classifications,
      note: values.note?.trim() ? values.note.trim() : undefined,
      apply_masking: values.apply_masking,
    });
  };

  return (
    <Modal
      open={open}
      title={t('apiGov.settings.classification.create_title')}
      onCancel={() => {
        form.resetFields();
        onClose();
      }}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={saveMutation.isPending}
      destroyOnHidden
      width={560}
    >
      <Form<ClassificationFormValues>
        form={form}
        layout="vertical"
        initialValues={{ matcher_type: 'JSON_PATH', apply_masking: true, classifications: [] }}
        onFinish={onFinish}
      >
        <Form.Item
          name="matcher_type"
          label={t('apiGov.settings.classification.label_matcher')}
          rules={[{ required: true, message: t('apiGov.settings.classification.matcher_required') }]}
        >
          <Select<ApiMaskingMatcherType>
            options={enumOptions(API_MASKING_MATCHER_TYPES, apiMaskingMatcherTypeLabel, t)}
          />
        </Form.Item>

        {matcherType === 'SCHEMA_FIELD' && (
          <Form.Item
            name="operation_id"
            label={t('apiGov.settings.classification.label_operation')}
            rules={[
              { required: true, message: t('apiGov.settings.classification.operation_required') },
            ]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={operationOptions}
              loading={operationsQuery.isLoading}
              placeholder={t('apiGov.settings.classification.operation_placeholder')}
            />
          </Form.Item>
        )}

        <Form.Item
          name="field_ref"
          label={t('apiGov.settings.classification.label_field')}
          extra={t(`apiGov.settings.classification.field_hint_${(matcherType ?? 'JSON_PATH').toLowerCase()}`)}
          rules={[
            { required: true, message: t('apiGov.settings.classification.field_required') },
            { max: 2048, message: t('apiGov.settings.classification.field_required') },
          ]}
        >
          <Input placeholder={t('apiGov.settings.classification.placeholder_field')} />
        </Form.Item>

        <Form.Item
          name="classifications"
          label={t('apiGov.settings.classification.label_classifications')}
          rules={[
            { required: true, message: t('apiGov.settings.classification.classifications_required') },
          ]}
        >
          <Select<DataClassification[]>
            mode="multiple"
            allowClear
            options={enumOptions(DATA_CLASSIFICATIONS, dataClassificationLabel, t)}
            placeholder={t('apiGov.settings.classification.placeholder_classifications')}
          />
        </Form.Item>

        <Form.Item
          name="apply_masking"
          label={t('apiGov.settings.classification.label_apply_masking')}
          valuePropName="checked"
          extra={t('apiGov.settings.classification.apply_masking_hint')}
        >
          <Switch />
        </Form.Item>

        <Form.Item
          name="note"
          label={t('apiGov.settings.classification.label_note')}
          rules={[{ max: 1000, message: t('apiGov.settings.classification.note_max') }]}
        >
          <Input.TextArea rows={2} maxLength={1000} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
