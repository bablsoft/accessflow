import { useMemo, useState } from 'react';
import { Button, Input, Select, Table } from 'antd';
import { DownloadOutlined, MoreOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { Avatar } from '@/components/common/Avatar';
import { RolePill } from '@/components/common/RolePill';
import { Pill } from '@/components/common/Pill';
import { USERS } from '@/mocks/data';
import { fmtDate, timeAgo } from '@/utils/dateFormat';

export function UsersPage() {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const filtered = useMemo(
    () =>
      USERS.filter(
        (u) =>
          !q ||
          u.email.toLowerCase().includes(q.toLowerCase()) ||
          u.display_name.toLowerCase().includes(q.toLowerCase()),
      ),
    [q],
  );
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('admin.users.title')}
        subtitle={t('admin.users.subtitle_count', { count: USERS.length })}
        actions={
          <>
            <Button icon={<DownloadOutlined />}>{t('common.export')}</Button>
            <Button type="primary" icon={<PlusOutlined />}>{t('common.invite')}</Button>
          </>
        }
      />
      <div
        style={{
          padding: '12px 28px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-elev)',
          display: 'flex',
          gap: 8,
        }}
      >
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder={t('admin.users.search_placeholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ width: 280 }}
        />
        <Select
          defaultValue="all"
          style={{ width: 130 }}
          options={[
            { value: 'all', label: t('admin.users.filter_all_roles') },
            { value: 'ADMIN', label: 'ADMIN' },
            { value: 'REVIEWER', label: 'REVIEWER' },
            { value: 'ANALYST', label: 'ANALYST' },
            { value: 'READONLY', label: 'READONLY' },
          ]}
        />
        <Select
          defaultValue="all"
          style={{ width: 150 }}
          options={[
            { value: 'all', label: t('admin.users.filter_all_providers') },
            { value: 'LOCAL', label: 'LOCAL' },
            { value: 'SAML', label: 'SAML' },
          ]}
        />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        <Table
          rowKey="id"
          size="middle"
          dataSource={filtered}
          pagination={{ pageSize: 12 }}
          columns={[
            {
              title: t('admin.users.col_user'),
              render: (_v, u) => (
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <Avatar name={u.display_name} size={28} />
                  <div>
                    <div style={{ fontSize: 13 }}>{u.display_name}</div>
                    <div className="mono muted" style={{ fontSize: 11 }}>{u.email}</div>
                  </div>
                </div>
              ),
            },
            { title: t('admin.users.col_role'), dataIndex: 'role', width: 110, render: (v) => <RolePill role={v} size="sm" /> },
            {
              title: t('admin.users.col_auth'),
              dataIndex: 'auth_provider',
              width: 110,
              render: (v) => <span className="mono" style={{ fontSize: 11 }}>{v}</span>,
            },
            {
              title: t('admin.users.col_status'),
              dataIndex: 'active',
              width: 90,
              render: (v) =>
                v ? (
                  <Pill fg="var(--risk-low)" bg="var(--risk-low-bg)" border="var(--risk-low-border)" withDot size="sm">
                    {t('admin.users.status_active')}
                  </Pill>
                ) : (
                  <Pill fg="var(--fg-muted)" bg="var(--status-neutral-bg)" border="var(--status-neutral-border)" withDot size="sm">
                    {t('admin.users.status_inactive')}
                  </Pill>
                ),
            },
            {
              title: t('admin.users.col_last_login'),
              dataIndex: 'last_login',
              width: 140,
              render: (v) => <span className="muted">{timeAgo(v)}</span>,
            },
            {
              title: t('admin.users.col_created'),
              dataIndex: 'created_at',
              width: 140,
              render: (v) => <span className="muted">{fmtDate(v).split(',')[0]}</span>,
            },
            {
              title: '',
              width: 50,
              render: () => (
                <Button size="small" type="text" icon={<MoreOutlined />} />
              ),
            },
          ]}
        />
      </div>
    </div>
  );
}
