import { useState } from 'react';
import { Button, Space, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { auditKeys, listAuditEvents } from '@/api/admin';
import { OnBehalfOfTag } from '@/components/common/OnBehalfOfTag';
import type { AuditEvent, ServiceAccount } from '@/types/api';
import { fmtDate } from '@/utils/dateFormat';

const PAGE_SIZE = 20;

/** The audit log pre-filtered to this account as the actor (#875). */
export function ServiceAccountActivityTab({ account }: { account: ServiceAccount }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const filters = { actor_id: account.id, page, size: PAGE_SIZE };
  const query = useQuery({
    queryKey: auditKeys.list(filters),
    queryFn: () => listAuditEvents(filters),
  });

  const columns: ColumnsType<AuditEvent> = [
    {
      title: t('admin.service_accounts.activity.col_when'),
      dataIndex: 'created_at',
      width: 180,
      render: (v: string) => fmtDate(v),
    },
    {
      title: t('admin.service_accounts.activity.col_action'),
      dataIndex: 'action',
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: t('admin.service_accounts.activity.col_resource'),
      key: 'resource',
      render: (_v, row) => (
        <span>
          {row.resource_type}
          {row.resource_id && (
            <span className="mono muted" style={{ fontSize: 12, marginLeft: 6 }}>
              {row.resource_id}
            </span>
          )}
        </span>
      ),
    },
    {
      title: t('admin.service_accounts.activity.col_on_behalf_of'),
      key: 'on_behalf_of',
      render: (_v, row) => {
        const id = row.metadata.on_behalf_of_user_id;
        return <OnBehalfOfTag email={row.on_behalf_of_email} userId={typeof id === 'string' ? id : null} />;
      },
    },
  ];

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        {t('admin.service_accounts.activity.description')}
      </Typography.Paragraph>
      <div>
        <Link to={`/admin/audit-log?actor_id=${encodeURIComponent(account.id)}`}>
          <Button>{t('admin.service_accounts.activity.open_full')}</Button>
        </Link>
      </div>
      <Table<AuditEvent>
        rowKey="id"
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.content ?? []}
        locale={{
          emptyText: query.isError
            ? t('admin.service_accounts.activity.load_error')
            : t('admin.service_accounts.activity.empty'),
        }}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: query.data?.total_elements ?? 0,
          showSizeChanger: false,
          onChange: (p) => setPage(p - 1),
        }}
      />
    </Space>
  );
}
