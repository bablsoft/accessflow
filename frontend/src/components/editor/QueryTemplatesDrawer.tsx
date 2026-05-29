import { useMemo, useState } from 'react';
import {
  App,
  Button,
  Drawer,
  Empty,
  Input,
  List,
  Popconfirm,
  Segmented,
  Space,
  Tag,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  deleteQueryTemplate,
  listQueryTemplates,
  queryTemplateKeys,
} from '@/api/queryTemplates';
import type { QueryTemplate, QueryTemplateVisibility } from '@/types/api';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';

type VisibilityFilter = 'ALL' | QueryTemplateVisibility;

interface QueryTemplatesDrawerProps {
  open: boolean;
  onClose: () => void;
  currentDatasourceId: string | null;
  onOpen: (template: QueryTemplate) => void;
}

const PAGE_SIZE = 25;

export function QueryTemplatesDrawer({
  open,
  onClose,
  currentDatasourceId,
  onOpen,
}: QueryTemplatesDrawerProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [visibility, setVisibility] = useState<VisibilityFilter>('ALL');

  const filters = useMemo(
    () => ({
      page: 0,
      size: PAGE_SIZE,
      q: search.trim() || undefined,
      visibility: visibility === 'ALL' ? undefined : visibility,
    }),
    [search, visibility],
  );

  const templatesQuery = useQuery({
    queryKey: queryTemplateKeys.list(filters),
    queryFn: () => listQueryTemplates(filters),
    enabled: open,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteQueryTemplate(id),
    onSuccess: () => {
      message.success(t('editor.templates.delete_success'));
      void queryClient.invalidateQueries({ queryKey: queryTemplateKeys.all });
    },
    onError: (err) => showApiError(message, err, adminErrorMessage),
  });

  const items = templatesQuery.data?.content ?? [];

  return (
    <Drawer
      title={t('editor.templates.drawer_title')}
      open={open}
      onClose={onClose}
      size="large"
      destroyOnHidden
    >
      <Space orientation="vertical" style={{ width: '100%' }} size="middle">
        <Input.Search
          allowClear
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t('editor.templates.search_placeholder')}
        />
        <Segmented<VisibilityFilter>
          block
          value={visibility}
          onChange={(value) => setVisibility(value)}
          options={[
            { label: t('editor.templates.filter_all'), value: 'ALL' },
            { label: t('editor.templates.filter_private'), value: 'PRIVATE' },
            { label: t('editor.templates.filter_team'), value: 'TEAM' },
          ]}
        />
        {items.length === 0 && !templatesQuery.isLoading ? (
          <Empty description={t('editor.templates.empty_state')} />
        ) : (
          <List<QueryTemplate>
            loading={templatesQuery.isLoading}
            dataSource={items}
            rowKey={(item) => item.id}
            renderItem={(item) => (
              <List.Item
                key={item.id}
                actions={[
                  <Button key="open" type="link" onClick={() => onOpen(item)}>
                    {t('editor.templates.open_button')}
                  </Button>,
                  item.editable ? (
                    <Popconfirm
                      key="delete"
                      title={t('editor.templates.delete_confirm_title')}
                      description={t('editor.templates.delete_confirm_body', { name: item.name })}
                      okText={t('common.delete')}
                      okButtonProps={{ danger: true }}
                      cancelText={t('common.cancel')}
                      onConfirm={() => deleteMutation.mutate(item.id)}
                    >
                      <Button type="link" danger>
                        {t('editor.templates.delete_button')}
                      </Button>
                    </Popconfirm>
                  ) : null,
                ]}
              >
                <List.Item.Meta
                  title={
                    <Space size="small" wrap>
                      <strong>{item.name}</strong>
                      <Tag color={item.visibility === 'TEAM' ? 'blue' : 'default'}>
                        {item.visibility === 'TEAM'
                          ? t('editor.templates.visibility_team')
                          : t('editor.templates.visibility_private')}
                      </Tag>
                      {item.datasource_id && item.datasource_id === currentDatasourceId ? (
                        <Tag color="green">{t('editor.templates.pinned_to_current')}</Tag>
                      ) : null}
                    </Space>
                  }
                  description={
                    <Space orientation="vertical" size={2} style={{ width: '100%' }}>
                      {item.description ? <span>{item.description}</span> : null}
                      {item.tags.length ? (
                        <Space size={4} wrap>
                          {item.tags.map((tag) => (
                            <Tag key={tag}>{tag}</Tag>
                          ))}
                        </Space>
                      ) : null}
                      <span className="muted" style={{ fontSize: 11 }}>
                        {t('editor.templates.owner_label', {
                          name: item.owner_display_name ?? '',
                        })}
                      </span>
                    </Space>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Space>
    </Drawer>
  );
}
