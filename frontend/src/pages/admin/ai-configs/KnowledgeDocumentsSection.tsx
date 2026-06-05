import { useState } from 'react';
import { App, Button, Form, Input, Modal, Popconfirm, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, ExperimentOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  aiConfigKeys,
  createKnowledgeDocument,
  deleteKnowledgeDocument,
  listKnowledgeDocuments,
  testRag,
} from '@/api/admin';
import { EmptyState } from '@/components/common/EmptyState';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate, fmtNum } from '@/utils/dateFormat';
import type { CreateKnowledgeDocumentInput, KnowledgeDocument } from '@/types/api';

interface DocFormValues {
  title: string;
  content: string;
}

/**
 * Knowledge-document management for a RAG-enabled AI configuration. Rendered on the edit page only
 * (the config must already be saved with RAG enabled, since ingestion embeds + stores immediately).
 */
export function KnowledgeDocumentsSection({
  configId,
  ragEnabled,
}: {
  configId: string;
  ragEnabled: boolean;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm<DocFormValues>();

  const docsQuery = useQuery({
    queryKey: aiConfigKeys.knowledge(configId),
    queryFn: () => listKnowledgeDocuments(configId),
    enabled: ragEnabled,
  });

  const addMutation = useMutation({
    mutationFn: (input: CreateKnowledgeDocumentInput) => createKnowledgeDocument(configId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: aiConfigKeys.knowledge(configId) });
      message.success(t('admin.ai_configs.rag.document_added'));
      setModalOpen(false);
      form.resetFields();
    },
    onError: (err: unknown) => showApiError(message, err, adminErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (documentId: string) => deleteKnowledgeDocument(configId, documentId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: aiConfigKeys.knowledge(configId) });
      message.success(t('admin.ai_configs.rag.document_deleted'));
    },
    onError: (err: unknown) => showApiError(message, err, adminErrorMessage),
  });

  const testMutation = useMutation({
    mutationFn: () => testRag(configId),
    onSuccess: (result) => {
      if (result.status === 'OK') {
        message.success(t('admin.ai_configs.rag.test_ok'));
      } else {
        message.error(t('admin.ai_configs.rag.test_failed', { detail: result.detail }));
      }
    },
    onError: (err: unknown) => showApiError(message, err, adminErrorMessage),
  });

  const columns: ColumnsType<KnowledgeDocument> = [
    { title: t('admin.ai_configs.rag.document_title'), dataIndex: 'title', key: 'title' },
    {
      title: t('admin.ai_configs.rag.document_chars'),
      dataIndex: 'char_count',
      key: 'char_count',
      render: (n: number) => fmtNum(n),
    },
    {
      title: t('admin.ai_configs.rag.document_chunks'),
      dataIndex: 'chunk_count',
      key: 'chunk_count',
    },
    {
      title: t('admin.ai_configs.rag.document_status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Tag color={status === 'INDEXED' ? 'green' : 'red'}>{status}</Tag>
      ),
    },
    {
      title: t('admin.ai_configs.rag.document_created'),
      dataIndex: 'created_at',
      key: 'created_at',
      render: (iso: string) => fmtDate(iso),
    },
    {
      title: t('admin.ai_configs.rag.document_actions'),
      key: 'actions',
      render: (_, record) => (
        <Popconfirm
          title={t('admin.ai_configs.rag.document_delete_confirm')}
          onConfirm={() => deleteMutation.mutate(record.id)}
          okText={t('common.delete')}
          cancelText={t('common.cancel')}
        >
          <Button
            type="text"
            danger
            icon={<DeleteOutlined />}
            aria-label={t('admin.ai_configs.rag.document_delete')}
          />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div style={{ borderTop: '1px solid var(--border)', paddingTop: 16, marginTop: 24 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 8,
        }}
      >
        <h3 style={{ margin: 0 }}>{t('admin.ai_configs.rag.documents_title')}</h3>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button
            icon={<ExperimentOutlined />}
            onClick={() => testMutation.mutate()}
            loading={testMutation.isPending}
            disabled={!ragEnabled}
          >
            {t('admin.ai_configs.rag.test_button')}
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setModalOpen(true)}
            disabled={!ragEnabled}
          >
            {t('admin.ai_configs.rag.add_document')}
          </Button>
        </div>
      </div>
      <p className="muted" style={{ marginTop: 0 }}>
        {ragEnabled
          ? t('admin.ai_configs.rag.documents_help')
          : t('admin.ai_configs.rag.documents_save_first')}
      </p>
      {ragEnabled && (
        <Table<KnowledgeDocument>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={docsQuery.data ?? []}
          loading={docsQuery.isLoading}
          pagination={false}
          locale={{
            emptyText: <EmptyState title={t('admin.ai_configs.rag.documents_empty')} />,
          }}
        />
      )}
      <Modal
        title={t('admin.ai_configs.rag.add_document')}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={addMutation.isPending}
        okText={t('admin.ai_configs.rag.add_document')}
        cancelText={t('common.cancel')}
        destroyOnHidden
      >
        <Form<DocFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) =>
            addMutation.mutate({ title: values.title.trim(), content: values.content })
          }
        >
          <Form.Item
            name="title"
            label={t('admin.ai_configs.rag.document_title')}
            rules={[
              { required: true, message: t('admin.ai_configs.rag.document_title_required') },
              { max: 255 },
            ]}
          >
            <Input maxLength={255} />
          </Form.Item>
          <Form.Item
            name="content"
            label={t('admin.ai_configs.rag.document_content')}
            rules={[
              { required: true, message: t('admin.ai_configs.rag.document_content_required') },
              { max: 100000 },
            ]}
          >
            <Input.TextArea rows={10} maxLength={100000} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
