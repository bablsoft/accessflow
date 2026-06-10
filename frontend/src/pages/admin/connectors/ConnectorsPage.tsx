import { useMemo, useState } from 'react';
import { App, Button, Input, Skeleton, Tag, Typography } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { DriverStatusBadge } from '@/components/datasources/DriverStatusBadge';
import { connectorKeys, installConnector, listConnectors } from '@/api/connectors';
import { datasourceKeys } from '@/api/datasources';
import { connectorErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { dbTypeLabel } from '@/utils/enumLabels';
import type { Connector, ConnectorCategory, DbType } from '@/types/api';

const DB_TYPE_COLOR: Record<DbType, string> = {
  POSTGRESQL: 'blue',
  MYSQL: 'orange',
  MARIADB: 'gold',
  ORACLE: 'red',
  MSSQL: 'cyan',
  CUSTOM: 'purple',
  MONGODB: 'green',
};

const CATEGORY_ORDER: ConnectorCategory[] = ['RELATIONAL', 'DOCUMENT'];

export default function ConnectorsPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [installingId, setInstallingId] = useState<string | null>(null);

  const listQuery = useQuery({
    queryKey: connectorKeys.lists(),
    queryFn: listConnectors,
  });

  const installMutation = useMutation({
    mutationFn: installConnector,
    onMutate: (id: string) => setInstallingId(id),
    onSuccess: (connector) => {
      void queryClient.invalidateQueries({ queryKey: connectorKeys.lists() });
      void queryClient.invalidateQueries({ queryKey: datasourceKeys.types() });
      message.success(t('connectors.install_success', { name: connector.name }));
    },
    onError: (err: unknown) => showApiError(message, err, connectorErrorMessage),
    onSettled: () => setInstallingId(null),
  });

  const filtered = useMemo(() => {
    const items = listQuery.data ?? [];
    const q = search.trim().toLowerCase();
    if (!q) return items;
    return items.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.db_type.toLowerCase().includes(q) ||
        (c.vendor ?? '').toLowerCase().includes(q),
    );
  }, [listQuery.data, search]);

  const groups = useMemo(
    () =>
      CATEGORY_ORDER.map((category) => ({
        category,
        label:
          category === 'DOCUMENT'
            ? t('connectors.category_document')
            : t('connectors.category_relational'),
        items: filtered.filter((c) => c.category === category),
      })).filter((g) => g.items.length > 0),
    [filtered, t],
  );

  return (
    <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}>
      <PageHeader title={t('connectors.title')} subtitle={t('connectors.subtitle')} />
      <Input.Search
        allowClear
        placeholder={t('connectors.search_placeholder')}
        aria-label={t('connectors.search_placeholder')}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        style={{ maxWidth: 360 }}
      />
      {listQuery.isLoading ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : listQuery.isError ? (
        <EmptyState title={t('connectors.load_error')} description={t('errors.connector_generic')} />
      ) : filtered.length === 0 ? (
        <EmptyState title={t('connectors.empty_title')} description={t('connectors.empty_description')} />
      ) : (
        groups.map((group) => (
          <section key={group.category} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {group.label}
            </Typography.Title>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))',
                gap: 16,
              }}
            >
              {group.items.map((connector) => (
                <ConnectorCard
                  key={connector.id}
                  connector={connector}
                  installing={installingId === connector.id}
                  onInstall={() => installMutation.mutate(connector.id)}
                />
              ))}
            </div>
          </section>
        ))
      )}
    </div>
  );
}

interface ConnectorCardProps {
  connector: Connector;
  installing: boolean;
  onInstall: () => void;
}

function ConnectorCard({ connector, installing, onInstall }: ConnectorCardProps) {
  const { t } = useTranslation();
  const installed = connector.bundled || connector.driver_status === 'READY';
  const unavailable = connector.driver_status === 'UNAVAILABLE';

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        padding: 16,
        borderRadius: 'var(--radius-md)',
        border: '1px solid var(--border)',
        background: 'var(--bg-elev)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <img
          src={connector.icon_url}
          alt=""
          width={40}
          height={40}
          style={{
            borderRadius: 8,
            background: 'var(--bg-sunken)',
            padding: 4,
            objectFit: 'contain',
          }}
        />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 600, fontSize: 15 }}>{connector.name}</div>
          <Tag color={DB_TYPE_COLOR[connector.db_type]} style={{ marginTop: 2 }}>
            {dbTypeLabel(t, connector.db_type)}
          </Tag>
        </div>
        <DriverStatusBadge status={connector.driver_status} bundled={connector.bundled} size="sm" />
      </div>

      {connector.description && (
        <div className="muted" style={{ fontSize: 12, lineHeight: 1.4 }}>
          {connector.description}
        </div>
      )}

      <div className="muted" style={{ fontSize: 12 }}>
        {connector.vendor && <span>{connector.vendor}</span>}
        {connector.documentation_url && (
          <>
            {connector.vendor ? ' · ' : ''}
            <Typography.Link href={connector.documentation_url} target="_blank" rel="noopener noreferrer">
              {t('connectors.docs_link')}
            </Typography.Link>
          </>
        )}
      </div>

      <div style={{ marginTop: 'auto' }}>
        {installed ? (
          <Tag color="green">{t('connectors.installed')}</Tag>
        ) : (
          <Button
            type="primary"
            icon={<DownloadOutlined />}
            loading={installing}
            disabled={unavailable}
            onClick={onInstall}
          >
            {unavailable ? t('connectors.unavailable') : t('connectors.install')}
          </Button>
        )}
      </div>
    </div>
  );
}
