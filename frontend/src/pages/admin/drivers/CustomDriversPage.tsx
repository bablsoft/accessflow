import { useState } from 'react';
import { App, Button, Popconfirm, Skeleton, Table, Tag, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  customDriverKeys,
  deleteCustomDriver,
  listCustomDrivers,
} from '@/api/customDrivers';
import { customDriverErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { CustomDriver, DbType } from '@/types/api';
import { CustomDriverUploadModal } from './CustomDriverUploadModal';

const DB_TYPE_COLOR: Record<DbType, string> = {
  POSTGRESQL: 'blue',
  MYSQL: 'orange',
  MARIADB: 'gold',
  ORACLE: 'red',
  MSSQL: 'cyan',
  CUSTOM: 'purple',
};

export default function CustomDriversPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [uploadOpen, setUploadOpen] = useState(false);

  const listQuery = useQuery({
    queryKey: customDriverKeys.lists(),
    queryFn: listCustomDrivers,
  });

  const deleteMutation = useMutation({
    mutationFn: deleteCustomDriver,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: customDriverKeys.lists() });
      void queryClient.invalidateQueries({ queryKey: ['datasources', 'types'] });
      message.success(t('drivers.list.delete_success'));
    },
    onError: (err: unknown) => showApiError(message, err, customDriverErrorMessage),
  });

  const columns: ColumnsType<CustomDriver> = [
    {
      title: t('drivers.list.col_vendor'),
      dataIndex: 'vendor_name',
      key: 'vendor_name',
      render: (value: string) => <strong>{value}</strong>,
    },
    {
      title: t('drivers.list.col_target'),
      dataIndex: 'target_db_type',
      key: 'target_db_type',
      render: (value: DbType) => <Tag color={DB_TYPE_COLOR[value]}>{value}</Tag>,
    },
    {
      title: t('drivers.list.col_class'),
      dataIndex: 'driver_class',
      key: 'driver_class',
      render: (value: string) => <span className="mono">{value}</span>,
    },
    {
      title: t('drivers.list.col_jar'),
      dataIndex: 'jar_filename',
      key: 'jar_filename',
      render: (value: string) => <span className="mono">{value}</span>,
    },
    {
      title: t('drivers.list.col_size'),
      dataIndex: 'jar_size_bytes',
      key: 'jar_size_bytes',
      render: (bytes: number) =>
        t('drivers.upload.jar_size_label', { kb: Math.round(bytes / 1024) }),
    },
    {
      title: t('drivers.list.col_sha256'),
      dataIndex: 'jar_sha256',
      key: 'jar_sha256',
      render: (value: string) => (
        <Typography.Text className="mono" copyable={{ text: value }}>
          {value.slice(0, 12)}…
        </Typography.Text>
      ),
    },
    {
      title: t('drivers.list.col_uploaded_by'),
      dataIndex: 'uploaded_by_display_name',
      key: 'uploaded_by_display_name',
    },
    {
      title: t('drivers.list.col_uploaded_at'),
      dataIndex: 'created_at',
      key: 'created_at',
      render: (value: string) => new Date(value).toLocaleString(),
    },
    {
      key: 'actions',
      width: 96,
      render: (_, record) => (
        <Popconfirm
          title={t('drivers.list.delete_confirm_title')}
          description={t('drivers.list.delete_confirm_body', {
            vendor: record.vendor_name,
            filename: record.jar_filename,
          })}
          okText={t('common.delete')}
          okButtonProps={{ danger: true }}
          cancelText={t('common.cancel')}
          onConfirm={() => deleteMutation.mutate(record.id)}
        >
          <Button
            type="text"
            danger
            aria-label={t('drivers.list.delete_action')}
            icon={<DeleteOutlined />}
          />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}>
      <PageHeader
        title={t('drivers.list.title')}
        subtitle={t('drivers.list.subtitle')}
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setUploadOpen(true)}
          >
            {t('drivers.list.upload_button')}
          </Button>
        }
      />
      {listQuery.isLoading ? (
        <Skeleton active paragraph={{ rows: 5 }} />
      ) : listQuery.isError ? (
        <EmptyState
          title={t('drivers.list.load_error')}
          description={t('errors.custom_driver_generic')}
        />
      ) : (listQuery.data ?? []).length === 0 ? (
        <EmptyState
          title={t('drivers.list.empty_title')}
          description={t('drivers.list.empty_description')}
          action={
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setUploadOpen(true)}
            >
              {t('drivers.list.upload_button')}
            </Button>
          }
        />
      ) : (
        <Table<CustomDriver>
          rowKey="id"
          dataSource={listQuery.data ?? []}
          columns={columns}
          pagination={false}
          size="middle"
        />
      )}
      <CustomDriverUploadModal open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </div>
  );
}
