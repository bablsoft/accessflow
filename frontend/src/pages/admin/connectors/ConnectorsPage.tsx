import { useMemo, useState } from 'react';
import { App, Button, Input, Skeleton, Typography, Tag } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Page, StatusTag } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { DriverStatusBadge } from '@/components/datasources/DriverStatusBadge';
import { connectorKeys, installConnector, listConnectors } from '@/api/connectors';
import { datasourceKeys } from '@/api/datasources';
import { connectorErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { dbTypeLabel } from '@/utils/enumLabels';
import { DB_TYPE_COLOR } from '@/utils/engineModes';
import type { Connector } from '@/types/api';

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

  // Three umbrellas: RELATIONAL is the SQL family, WAREHOUSE the cloud data warehouses
  // (SQL-dialect but engine-managed), and every other category (DOCUMENT, KEY_VALUE,
  // WIDE_COLUMN, SEARCH, GRAPH) groups under NoSQL.
  const groups = useMemo(
    () =>
      [
        {
          category: 'sql',
          label: t('connectors.category_relational'),
          items: filtered.filter((c) => c.category === 'RELATIONAL'),
        },
        {
          category: 'warehouse',
          label: t('connectors.category_warehouse'),
          items: filtered.filter((c) => c.category === 'WAREHOUSE'),
        },
        {
          category: 'nosql',
          label: t('connectors.category_nosql'),
          items: filtered.filter((c) => c.category !== 'RELATIONAL' && c.category !== 'WAREHOUSE'),
        },
      ].filter((g) => g.items.length > 0),
    [filtered, t],
  );

  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-connectors"
        title={t('connectors.title')}
        subtitle={t('connectors.subtitle')}
      />
      <div
        className="u-border-b u-bg-elev" style={{ padding: '12px var(--gutter)' }}
      >
        <Input.Search
          allowClear
          placeholder={t('connectors.search_placeholder')}
          aria-label={t('connectors.search_placeholder')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="u-max-w-xs"
        />
      </div>
      <div
        className="u-flex-1 u-p-6 u-flex u-col u-gap-4 u-overflow-auto"
      >
        {listQuery.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : listQuery.isError ? (
          <EmptyState title={t('connectors.load_error')} description={t('errors.connector_generic')} />
        ) : filtered.length === 0 ? (
          <EmptyState title={t('connectors.empty_title')} description={t('connectors.empty_description')} />
        ) : (
          groups.map((group) => (
            <section key={group.category} className="u-flex u-col u-gap-3">
              <Typography.Title level={5} className="u-m-0">
                {group.label}
              </Typography.Title>
              <div
                className="u-grid u-gap-4" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))' }}
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
    </Page>
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
      className="af-box u-flex u-col u-gap-3 u-p-4"
    >
      <div className="u-flex u-items-center u-gap-3">
        <img
          src={connector.icon_url}
          alt=""
          width={40}
          height={40}
          className="u-p-1 u-rounded-md u-bg-sunken" style={{ objectFit: 'contain' }}
        />
        <div className="u-flex-1 u-min-w-0">
          <div className="u-fw-600 u-text-lg">{connector.name}</div>
          <Tag color={DB_TYPE_COLOR[connector.db_type]} style={{ marginTop: 2 }}>
            {dbTypeLabel(t, connector.db_type)}
          </Tag>
        </div>
        <DriverStatusBadge status={connector.driver_status} bundled={connector.bundled} size="sm" />
      </div>

      {connector.description && (
        <div className="muted u-text-xs" style={{ lineHeight: 1.4 }}>
          {connector.description}
        </div>
      )}

      <div className="muted u-text-xs">
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
          <StatusTag tone="success">{t('connectors.installed')}</StatusTag>
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
