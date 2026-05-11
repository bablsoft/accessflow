import { useState } from 'react';
import { App, Button, Modal, Popconfirm, Skeleton, Table, Tag } from 'antd';
import { DeleteOutlined, EditOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  aiConfigKeys,
  deleteAiConfig,
  listAiConfigs,
  setupProgressKeys,
  testAiConfig,
} from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { AiConfig, AiProvider } from '@/types/api';

const PROVIDER_COLOR: Record<AiProvider, string> = {
  ANTHROPIC: 'purple',
  OPENAI: 'blue',
  OLLAMA: 'cyan',
};

export function AiConfigListPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [inUseError, setInUseError] = useState<Array<{ id: string; name: string }> | null>(null);

  const listQuery = useQuery({
    queryKey: aiConfigKeys.lists(),
    queryFn: listAiConfigs,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteAiConfig(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: aiConfigKeys.all });
      void queryClient.invalidateQueries({ queryKey: setupProgressKeys.current() });
      message.success(t('admin.ai_configs.delete_success'));
    },
    onError: (err: unknown) => {
      const bound = extractBoundDatasources(err);
      if (bound) {
        setInUseError(bound);
        return;
      }
      showApiError(message, err, adminErrorMessage);
    },
  });

  const testMutation = useMutation({
    mutationFn: (id: string) => testAiConfig(id),
    onSuccess: (result) => {
      if (result.status === 'OK') {
        message.success(t('admin.ai_configs.test_ok'));
      } else {
        message.error(t('admin.ai_configs.test_failed', { detail: result.detail }));
      }
    },
    onError: (err: unknown) => showApiError(message, err, adminErrorMessage),
  });

  const columns: ColumnsType<AiConfig> = [
    {
      title: t('admin.ai_configs.column_name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string) => <span className="mono">{name}</span>,
    },
    {
      title: t('admin.ai_configs.column_provider'),
      dataIndex: 'provider',
      key: 'provider',
      render: (provider: AiProvider) => (
        <Tag color={PROVIDER_COLOR[provider]}>{provider}</Tag>
      ),
    },
    {
      title: t('admin.ai_configs.column_model'),
      dataIndex: 'model',
      key: 'model',
      render: (model: string) => <span className="mono">{model}</span>,
    },
    {
      title: t('admin.ai_configs.column_in_use'),
      dataIndex: 'in_use_count',
      key: 'in_use_count',
      render: (count: number) => (
        <span className="mono muted">
          {t('admin.ai_configs.in_use_count', { count })}
        </span>
      ),
    },
    {
      title: t('admin.ai_configs.column_actions'),
      key: 'actions',
      width: 220,
      render: (_value, row) => (
        <div style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            icon={<EditOutlined />}
            onClick={() => navigate(`/admin/ai-configs/${row.id}`)}
            aria-label={t('admin.ai_configs.action_edit')}
          >
            {t('admin.ai_configs.action_edit')}
          </Button>
          <Button
            size="small"
            icon={<PlayCircleOutlined />}
            loading={testMutation.isPending && testMutation.variables === row.id}
            onClick={() => testMutation.mutate(row.id)}
            aria-label={t('admin.ai_configs.action_test')}
          >
            {t('admin.ai_configs.action_test')}
          </Button>
          <Popconfirm
            title={t('admin.ai_configs.delete_confirm_title')}
            description={t('admin.ai_configs.delete_confirm_body', { name: row.name })}
            okText={t('common.delete')}
            okButtonProps={{ danger: true }}
            cancelText={t('common.cancel')}
            onConfirm={() => deleteMutation.mutate(row.id)}
          >
            <Button
              size="small"
              icon={<DeleteOutlined />}
              danger
              aria-label={t('admin.ai_configs.action_delete')}
            />
          </Popconfirm>
        </div>
      ),
    },
  ];

  if (listQuery.isLoading) {
    return (
      <div style={{ padding: 28 }}>
        <Skeleton active paragraph={{ rows: 6 }} />
      </div>
    );
  }

  if (listQuery.isError) {
    return (
      <EmptyState
        title={t('admin.ai_configs.load_error')}
        description={adminErrorMessage(listQuery.error)}
      />
    );
  }

  const configs = listQuery.data ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.ai_configs.title')}
        subtitle={t('admin.ai_configs.subtitle')}
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/admin/ai-configs/new')}
          >
            {t('admin.ai_configs.add_button')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: 28 }}>
        {configs.length === 0 ? (
          <EmptyState
            title={t('admin.ai_configs.empty_title')}
            description={t('admin.ai_configs.empty_body')}
            action={
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => navigate('/admin/ai-configs/new')}
              >
                {t('admin.ai_configs.add_button')}
              </Button>
            }
          />
        ) : (
          <Table<AiConfig>
            rowKey="id"
            columns={columns}
            dataSource={configs}
            pagination={false}
            aria-label={t('admin.ai_configs.title')}
          />
        )}
      </div>
      <Modal
        open={inUseError !== null}
        title={t('admin.ai_configs.delete_in_use_title')}
        onCancel={() => setInUseError(null)}
        onOk={() => setInUseError(null)}
        cancelButtonProps={{ style: { display: 'none' } }}
      >
        <p>{t('admin.ai_configs.delete_in_use_body')}</p>
        <ul>
          {(inUseError ?? []).map((d) => (
            <li key={d.id}>{d.name}</li>
          ))}
        </ul>
      </Modal>
    </div>
  );
}

function extractBoundDatasources(err: unknown): Array<{ id: string; name: string }> | null {
  if (typeof err !== 'object' || err === null) return null;
  const response = (err as { response?: { data?: unknown } }).response;
  const data = response?.data;
  if (typeof data !== 'object' || data === null) return null;
  const body = data as { error?: string; boundDatasources?: Array<{ id: string; name: string }> };
  if (body.error !== 'AI_CONFIG_IN_USE') return null;
  return body.boundDatasources ?? [];
}
