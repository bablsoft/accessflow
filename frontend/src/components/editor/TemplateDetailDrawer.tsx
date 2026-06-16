import { useEffect, useMemo, useState } from 'react';
import { App, Button, Descriptions, Drawer, Empty, List, Popconfirm, Select, Space, Tabs, Tag } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  listTemplateVersions,
  queryTemplateKeys,
  restoreTemplateVersion,
} from '@/api/queryTemplates';
import type { DbType, QueryTemplate, QueryTemplateChangeType } from '@/types/api';
import { queryTemplateChangeLabel } from '@/utils/enumLabels';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { SqlEditor } from './SqlEditor';
import { SqlDiffView } from './SqlDiffView';

interface TemplateDetailDrawerProps {
  open: boolean;
  onClose: () => void;
  template: QueryTemplate | null;
  currentDatasourceId: string | null;
  dbType?: DbType;
  defaultTab?: 'details' | 'history';
}

const PAGE_SIZE = 50;

const CHANGE_COLORS: Record<QueryTemplateChangeType, string> = {
  CREATED: 'green',
  UPDATED: 'blue',
  RESTORED: 'gold',
};

export function TemplateDetailDrawer({
  open,
  onClose,
  template,
  currentDatasourceId,
  dbType,
  defaultTab = 'details',
}: TemplateDetailDrawerProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<'details' | 'history'>(defaultTab);
  const [baseId, setBaseId] = useState<string | null>(null);
  const [targetId, setTargetId] = useState<string | null>(null);

  useEffect(() => {
    if (open) setTab(defaultTab);
  }, [open, defaultTab, template?.id]);

  const versionsQuery = useQuery({
    queryKey: template ? queryTemplateKeys.versions(template.id) : ['queryTemplates', 'versions', 'none'],
    queryFn: () => listTemplateVersions(template!.id, { page: 0, size: PAGE_SIZE }),
    enabled: open && !!template,
  });

  const versions = useMemo(() => versionsQuery.data?.content ?? [], [versionsQuery.data]);

  // Default: compare the two newest versions (newest on the right).
  useEffect(() => {
    const newest = versions[0];
    if (!newest) {
      setBaseId(null);
      setTargetId(null);
      return;
    }
    const secondNewest = versions[1] ?? newest;
    setTargetId((prev) => (prev && versions.some((v) => v.id === prev) ? prev : newest.id));
    setBaseId((prev) => (prev && versions.some((v) => v.id === prev) ? prev : secondNewest.id));
  }, [versions]);

  const restoreMutation = useMutation({
    mutationFn: (versionId: string) => restoreTemplateVersion(template!.id, versionId),
    onSuccess: () => {
      message.success(t('editor.templates.history.restore_success'));
      void queryClient.invalidateQueries({ queryKey: queryTemplateKeys.all });
      if (template) {
        void queryClient.invalidateQueries({ queryKey: queryTemplateKeys.versions(template.id) });
        void queryClient.invalidateQueries({ queryKey: queryTemplateKeys.detail(template.id) });
      }
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const base = versions.find((v) => v.id === baseId) ?? null;
  const target = versions.find((v) => v.id === targetId) ?? null;

  const versionOption = (versionId: string) => {
    const v = versions.find((it) => it.id === versionId);
    if (!v) return versionId;
    return t('editor.templates.history.version_option', {
      number: v.version_number,
      change: queryTemplateChangeLabel(t, v.change_type),
      when: timeAgo(v.created_at),
    });
  };

  const selectOptions = versions.map((v) => ({ value: v.id, label: versionOption(v.id) }));

  if (!template) return null;

  const detailsTab = (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label={t('editor.templates.fields.name')}>{template.name}</Descriptions.Item>
        <Descriptions.Item label={t('editor.templates.fields.visibility')}>
          <Tag color={template.visibility === 'TEAM' ? 'blue' : 'default'}>
            {template.visibility === 'TEAM'
              ? t('editor.templates.visibility_team')
              : t('editor.templates.visibility_private')}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label={t('editor.templates.history.owner')}>
          {template.owner_display_name ?? '—'}
        </Descriptions.Item>
        {template.datasource_id ? (
          <Descriptions.Item label={t('editor.templates.fields.pin_to_datasource')}>
            {template.datasource_id === currentDatasourceId
              ? t('editor.templates.pinned_to_current')
              : template.datasource_id}
          </Descriptions.Item>
        ) : null}
        {template.tags.length ? (
          <Descriptions.Item label={t('editor.templates.fields.tags')}>
            <Space size={4} wrap>
              {template.tags.map((tag) => (
                <Tag key={tag}>{tag}</Tag>
              ))}
            </Space>
          </Descriptions.Item>
        ) : null}
        {template.description ? (
          <Descriptions.Item label={t('editor.templates.fields.description')}>
            {template.description}
          </Descriptions.Item>
        ) : null}
      </Descriptions>
      <SqlEditor value={template.body} onChange={() => {}} dbType={dbType} readOnly height={260} />
    </Space>
  );

  const historyTab = versionsQuery.isLoading ? (
    <List loading dataSource={[]} renderItem={() => null} />
  ) : versions.length === 0 ? (
    <Empty description={t('editor.templates.history.empty_state')} />
  ) : (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
        <Space size={4}>
          <span className="muted">{t('editor.templates.history.compare_base')}</span>
          <Select
            size="small"
            style={{ minWidth: 220 }}
            value={baseId ?? undefined}
            options={selectOptions}
            onChange={setBaseId}
            aria-label={t('editor.templates.history.compare_base')}
          />
        </Space>
        <Space size={4}>
          <span className="muted">{t('editor.templates.history.compare_target')}</span>
          <Select
            size="small"
            style={{ minWidth: 220 }}
            value={targetId ?? undefined}
            options={selectOptions}
            onChange={setTargetId}
            aria-label={t('editor.templates.history.compare_target')}
          />
        </Space>
      </Space>
      {base && target ? (
        <SqlDiffView
          oldValue={base.body}
          newValue={target.body}
          dbType={dbType}
          oldLabel={t('editor.templates.history.diff_base_label', { number: base.version_number })}
          newLabel={t('editor.templates.history.diff_target_label', { number: target.version_number })}
        />
      ) : null}
      <List
        dataSource={versions}
        rowKey={(v) => v.id}
        renderItem={(v) => (
          <List.Item
            actions={
              template.editable
                ? [
                    <Popconfirm
                      key="restore"
                      title={t('editor.templates.history.restore_confirm_title')}
                      description={t('editor.templates.history.restore_confirm_body', {
                        number: v.version_number,
                      })}
                      okText={t('common.ok')}
                      cancelText={t('common.cancel')}
                      onConfirm={() => restoreMutation.mutate(v.id)}
                    >
                      <Button type="link" loading={restoreMutation.isPending}>
                        {t('editor.templates.history.restore_button')}
                      </Button>
                    </Popconfirm>,
                  ]
                : undefined
            }
          >
            <List.Item.Meta
              title={
                <Space size="small" wrap>
                  <strong>{t('editor.templates.history.version_label', { number: v.version_number })}</strong>
                  <Tag color={CHANGE_COLORS[v.change_type]}>
                    {queryTemplateChangeLabel(t, v.change_type)}
                  </Tag>
                </Space>
              }
              description={
                <span className="muted" style={{ fontSize: 11 }}>
                  {t('editor.templates.history.version_meta', {
                    author: v.author_display_name ?? t('editor.templates.history.author_unknown'),
                    when: fmtDate(v.created_at),
                  })}
                </span>
              }
            />
          </List.Item>
        )}
      />
    </Space>
  );

  return (
    <Drawer
      title={t('editor.templates.history.title', { name: template.name })}
      open={open}
      onClose={onClose}
      size="large"
      destroyOnHidden
    >
      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as 'details' | 'history')}
        items={[
          { key: 'details', label: t('editor.templates.history.tab_details') },
          { key: 'history', label: t('editor.templates.history.tab_history') },
        ]}
      />
      {tab === 'details' ? detailsTab : historyTab}
    </Drawer>
  );
}
