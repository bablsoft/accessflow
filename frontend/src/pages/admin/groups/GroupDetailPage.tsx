import { useState } from 'react';
import {
  App,
  Button,
  Form,
  Modal,
  Popconfirm,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useParams, useNavigate } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  addGroupMember,
  getGroup,
  groupKeys,
  listGroupMembers,
  removeGroupMember,
} from '@/api/groups';
import { listUsers, userKeys } from '@/api/admin';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import type { GroupMembershipSource, UserGroupMember } from '@/types/api';

const SOURCE_TAG_COLOR: Record<GroupMembershipSource, string> = {
  MANUAL: 'blue',
  IDP: 'gold',
};

export function GroupDetailPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { id } = useParams<{ id: string }>();
  const [addOpen, setAddOpen] = useState(false);
  const [addForm] = Form.useForm<{ userId: string }>();

  const groupQuery = useQuery({
    queryKey: groupKeys.detail(id ?? ''),
    queryFn: () => getGroup(id!),
    enabled: !!id,
  });

  const membersQuery = useQuery({
    queryKey: groupKeys.members(id ?? ''),
    queryFn: () => listGroupMembers(id!),
    enabled: !!id,
  });

  const usersQuery = useQuery({
    queryKey: userKeys.list({ size: 200 }),
    queryFn: () => listUsers({ size: 200 }),
    enabled: addOpen,
  });

  const addMutation = useMutation({
    mutationFn: (userId: string) => addGroupMember(id!, userId),
    onSuccess: () => {
      message.success(t('admin.groups.member_added'));
      void queryClient.invalidateQueries({ queryKey: groupKeys.members(id!) });
      void queryClient.invalidateQueries({ queryKey: groupKeys.detail(id!) });
      void queryClient.invalidateQueries({ queryKey: groupKeys.lists() });
      setAddOpen(false);
      addForm.resetFields();
    },
    onError: (error) => showApiError(message, error, adminErrorMessage),
  });

  const removeMutation = useMutation({
    mutationFn: (userId: string) => removeGroupMember(id!, userId),
    onSuccess: () => {
      message.success(t('admin.groups.member_removed'));
      void queryClient.invalidateQueries({ queryKey: groupKeys.members(id!) });
      void queryClient.invalidateQueries({ queryKey: groupKeys.detail(id!) });
      void queryClient.invalidateQueries({ queryKey: groupKeys.lists() });
    },
    onError: (error) => showApiError(message, error, adminErrorMessage),
  });

  const memberUserIds = new Set(membersQuery.data?.map((m) => m.user_id) ?? []);
  const availableUsers = (usersQuery.data?.content ?? []).filter(
    (u) => u.active && !memberUserIds.has(u.id),
  );

  const columns = [
    {
      title: t('admin.groups.members_columns.email'),
      dataIndex: 'email',
      key: 'email',
    },
    {
      title: t('admin.groups.members_columns.display_name'),
      dataIndex: 'display_name',
      key: 'display_name',
      render: (val: string | null) => val ?? '—',
    },
    {
      title: t('admin.groups.members_columns.source'),
      dataIndex: 'source',
      key: 'source',
      width: 100,
      render: (source: GroupMembershipSource) => (
        <Tag color={SOURCE_TAG_COLOR[source]}>
          {t(`admin.groups.source.${source}`)}
        </Tag>
      ),
    },
    {
      title: t('admin.groups.members_columns.joined_at'),
      dataIndex: 'joined_at',
      key: 'joined_at',
      render: (val: string) => fmtDate(val),
      width: 180,
    },
    {
      title: t('admin.groups.members_columns.actions'),
      key: 'actions',
      width: 100,
      render: (_: unknown, member: UserGroupMember) => (
        <Popconfirm
          title={t('admin.groups.remove_member_confirm', { email: member.email })}
          okText={t('common.remove')}
          cancelText={t('common.cancel')}
          okButtonProps={{ danger: true, loading: removeMutation.isPending }}
          onConfirm={() => removeMutation.mutate(member.user_id)}
        >
          <Button
            type="text"
            size="small"
            danger
            icon={<DeleteOutlined />}
            aria-label={t('admin.groups.remove_member')}
          />
        </Popconfirm>
      ),
    },
  ];

  if (!id) return null;

  return (
    <>
      <PageHeader
        title={groupQuery.data?.name ?? t('admin.groups.title')}
        subtitle={groupQuery.data?.description ?? undefined}
        breadcrumbs={[t('admin.groups.title'), groupQuery.data?.name ?? '']}
        actions={
          <Space>
            <Button onClick={() => navigate('/admin/groups')}>
              {t('common.back')}
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setAddOpen(true)}
            >
              {t('admin.groups.add_member')}
            </Button>
          </Space>
        }
      />

      {membersQuery.isLoading ? (
        <Skeleton active />
      ) : (membersQuery.data?.length ?? 0) === 0 ? (
        <EmptyState
          title={t('admin.groups.members_empty.title')}
          description={t('admin.groups.members_empty.description')}
          action={
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setAddOpen(true)}
            >
              {t('admin.groups.add_member')}
            </Button>
          }
        />
      ) : (
        <Table
          rowKey="user_id"
          dataSource={membersQuery.data ?? []}
          columns={columns}
          pagination={false}
        />
      )}

      <Modal
        title={t('admin.groups.add_member_modal_title')}
        open={addOpen}
        destroyOnHidden
        onCancel={() => {
          setAddOpen(false);
          addForm.resetFields();
        }}
        onOk={() => {
          addForm
            .validateFields()
            .then((values) => addMutation.mutate(values.userId))
            .catch(() => {});
        }}
        okButtonProps={{ loading: addMutation.isPending }}
        okText={t('common.add')}
        cancelText={t('common.cancel')}
      >
        <Form layout="vertical" form={addForm} preserve={false}>
          <Form.Item
            name="userId"
            label={t('admin.groups.fields.user')}
            rules={[
              { required: true, message: t('admin.groups.validation.user_required') },
            ]}
          >
            <Select
              loading={usersQuery.isLoading}
              showSearch
              placeholder={t('admin.groups.fields.user_placeholder')}
              filterOption={(input, option) =>
                String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={availableUsers.map((u) => ({
                value: u.id,
                label: u.display_name ? `${u.display_name} (${u.email})` : u.email,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
