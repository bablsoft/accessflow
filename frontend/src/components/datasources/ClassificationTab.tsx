import { useMemo, useState } from 'react';
import {
  App,
  AutoComplete,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Switch,
  Table,
  Tag,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createClassificationTags,
  dataClassificationKeys,
  deleteClassificationTag,
  listClassificationTags,
} from '@/api/dataClassifications';
import { datasourceKeys, getDatasourceSchema } from '@/api/datasources';
import { maskingPolicyKeys } from '@/api/maskingPolicies';
import {
  DATA_CLASSIFICATIONS,
  dataClassificationLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { showApiError } from '@/utils/showApiError';
import { ClassificationDerivationPanel } from './ClassificationDerivationPanel';
import type {
  CreateDataClassificationTagInput,
  DataClassification,
  DataClassificationTag,
} from '@/types/api';

export function ClassificationTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [modalOpen, setModalOpen] = useState(false);

  const tagsQuery = useQuery({
    queryKey: dataClassificationKeys.list(dsId),
    queryFn: () => listClassificationTags(dsId),
  });
  const tags = tagsQuery.data ?? [];

  const deleteMutation = useMutation({
    mutationFn: (tagId: string) => deleteClassificationTag(dsId, tagId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dataClassificationKeys.list(dsId) });
      void queryClient.invalidateQueries({ queryKey: dataClassificationKeys.derivation(dsId) });
      message.success(t('datasources.settings.classification.delete_success'));
    },
    onError: () => {
      message.error(t('datasources.settings.classification.delete_error'));
    },
  });

  const onDelete = (tag: DataClassificationTag) => {
    modal.confirm({
      title: t('datasources.settings.classification.delete_confirm_title'),
      content: t('datasources.settings.classification.delete_confirm_body'),
      okType: 'danger',
      okText: t('datasources.settings.classification.delete'),
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
          <div style={{ fontWeight: 600 }}>{t('datasources.settings.classification.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('datasources.settings.classification.description')}
          </div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          {t('datasources.settings.classification.add')}
        </Button>
      </div>

      {!tagsQuery.isLoading && tags.length === 0 ? (
        <EmptyState
          title={t('datasources.settings.classification.empty_title')}
          description={t('datasources.settings.classification.empty_description')}
        />
      ) : (
        <Table<DataClassificationTag>
          rowKey="id"
          size="middle"
          loading={tagsQuery.isLoading}
          dataSource={tags}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('datasources.settings.classification.col_object'),
              render: (_v, tag) => (
                <span className="mono" style={{ fontSize: 12 }}>
                  {tag.column_name ? `${tag.table_name}.${tag.column_name}` : tag.table_name}
                </span>
              ),
            },
            {
              title: t('datasources.settings.classification.col_scope'),
              width: 110,
              render: (_v, tag) => (
                <span className="muted" style={{ fontSize: 12 }}>
                  {tag.column_name
                    ? t('datasources.settings.classification.scope_column')
                    : t('datasources.settings.classification.scope_table')}
                </span>
              ),
            },
            {
              title: t('datasources.settings.classification.col_classification'),
              dataIndex: 'classification',
              width: 150,
              render: (v: DataClassification) => <Tag color="volcano">{dataClassificationLabel(t, v)}</Tag>,
            },
            {
              title: t('datasources.settings.classification.col_note'),
              dataIndex: 'note',
              render: (v: string | null) =>
                v ? <span style={{ fontSize: 12 }}>{v}</span> : <span className="muted">—</span>,
            },
            {
              title: t('datasources.settings.classification.col_actions'),
              width: 80,
              align: 'right',
              render: (_v, tag) => (
                <Button
                  size="small"
                  type="text"
                  danger
                  icon={<DeleteOutlined />}
                  aria-label={t('datasources.settings.classification.delete')}
                  onClick={() => onDelete(tag)}
                  disabled={deleteMutation.isPending}
                />
              ),
            },
          ]}
        />
      )}

      <ClassificationDerivationPanel dsId={dsId} />

      <ClassificationTagModal open={modalOpen} dsId={dsId} onClose={() => setModalOpen(false)} />
    </div>
  );
}

interface ClassificationFormValues {
  table_name: string;
  column_name?: string;
  classifications: DataClassification[];
  note?: string;
  apply_masking: boolean;
}

function ClassificationTagModal({
  open,
  dsId,
  onClose,
}: {
  open: boolean;
  dsId: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ClassificationFormValues>();
  const selectedTable = Form.useWatch('table_name', form);
  const selectedColumn = Form.useWatch('column_name', form);

  const schemaQuery = useQuery({
    queryKey: datasourceKeys.schema(dsId),
    queryFn: () => getDatasourceSchema(dsId),
    enabled: open,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const tables = useMemo(() => {
    const out: { value: string; columns: string[] }[] = [];
    for (const s of schemaQuery.data?.schemas ?? []) {
      for (const tab of s.tables) {
        out.push({ value: `${s.name}.${tab.name}`, columns: tab.columns.map((c) => c.name) });
      }
    }
    out.sort((a, b) => a.value.localeCompare(b.value));
    return out;
  }, [schemaQuery.data]);

  const tableOptions = useMemo(() => tables.map((tab) => ({ value: tab.value })), [tables]);
  const columnOptions = useMemo(() => {
    const match = tables.find((tab) => tab.value === selectedTable);
    return (match?.columns ?? []).map((c) => ({ value: c }));
  }, [tables, selectedTable]);

  const saveMutation = useMutation({
    mutationFn: (input: CreateDataClassificationTagInput) => createClassificationTags(dsId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dataClassificationKeys.list(dsId) });
      void queryClient.invalidateQueries({ queryKey: dataClassificationKeys.derivation(dsId) });
      // Tagging a column may auto-create a masking policy.
      void queryClient.invalidateQueries({ queryKey: maskingPolicyKeys.list(dsId) });
      message.success(t('datasources.settings.classification.save_success'));
      form.resetFields();
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, () => t('datasources.settings.classification.save_error'));
    },
  });

  const onFinish = (values: ClassificationFormValues) => {
    const column = values.column_name?.trim();
    saveMutation.mutate({
      table_name: values.table_name.trim(),
      column_name: column ? column : undefined,
      classifications: values.classifications,
      note: values.note?.trim() ? values.note.trim() : undefined,
      apply_masking: column ? values.apply_masking : undefined,
    });
  };

  const hasColumn = !!selectedColumn?.trim();

  return (
    <Modal
      open={open}
      title={t('datasources.settings.classification.create_title')}
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
        initialValues={{ apply_masking: true, classifications: [] }}
        onFinish={onFinish}
      >
        <Form.Item
          name="table_name"
          label={t('datasources.settings.classification.label_table')}
          rules={[
            { required: true, message: t('datasources.settings.classification.table_required') },
            { max: 256, message: t('datasources.settings.classification.table_max') },
          ]}
        >
          <AutoComplete
            options={tableOptions}
            filterOption={(input, option) =>
              (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
            }
            placeholder={t('datasources.settings.classification.placeholder_table')}
          />
        </Form.Item>

        <Form.Item
          name="column_name"
          label={t('datasources.settings.classification.label_column')}
          rules={[{ max: 256, message: t('datasources.settings.classification.column_max') }]}
          extra={t('datasources.settings.classification.column_hint')}
        >
          <AutoComplete
            options={columnOptions}
            filterOption={(input, option) =>
              (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
            }
            allowClear
            placeholder={t('datasources.settings.classification.placeholder_column')}
          />
        </Form.Item>

        <Form.Item
          name="classifications"
          label={t('datasources.settings.classification.label_classifications')}
          rules={[
            {
              required: true,
              message: t('datasources.settings.classification.classifications_required'),
            },
          ]}
        >
          <Select<DataClassification[]>
            mode="multiple"
            allowClear
            options={enumOptions(DATA_CLASSIFICATIONS, dataClassificationLabel, t)}
            placeholder={t('datasources.settings.classification.placeholder_classifications')}
          />
        </Form.Item>

        <Form.Item
          name="apply_masking"
          label={t('datasources.settings.classification.label_apply_masking')}
          valuePropName="checked"
          extra={
            hasColumn
              ? t('datasources.settings.classification.apply_masking_hint')
              : t('datasources.settings.classification.apply_masking_table_hint')
          }
        >
          <Switch disabled={!hasColumn} />
        </Form.Item>

        <Form.Item
          name="note"
          label={t('datasources.settings.classification.label_note')}
          rules={[{ max: 1000, message: t('datasources.settings.classification.note_max') }]}
        >
          <Input.TextArea rows={2} maxLength={1000} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
