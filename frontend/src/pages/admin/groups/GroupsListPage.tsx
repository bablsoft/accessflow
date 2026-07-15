import { useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Skeleton,
  Space,
  Table,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  createGroup,
  deleteGroup,
  groupKeys,
  listGroups,
  updateGroup,
} from '@/api/groups';
import { adminErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import type {
  CreateUserGroupInput,
  UpdateUserGroupInput,
  UserGroup,
} from '@/types/api';

interface GroupFormValues {
  name: string;
  description?: string;
}

export function GroupsListPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [createOpen, setCreateOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState<UserGroup | null>(null);
  const [createForm] = Form.useForm<GroupFormValues>();
  const [editForm] = Form.useForm<GroupFormValues>();

  const groupsQuery = useQuery({
    queryKey: groupKeys.list({ page, size }),
    queryFn: () => listGroups({ page, size }),
  });

  const createMutation = useMutation({
    mutationFn: (input: CreateUserGroupInput) => createGroup(input),
    onSuccess: () => {
      message.success(t('admin.groups.created'));
      void queryClient.invalidateQueries({ queryKey: groupKeys.all });
      setCreateOpen(false);
      createForm.resetFields();
    },
    onError: (error) => showApiError(message, error, adminErrorMessage),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, input }: { id: string; input: UpdateUserGroupInput }) =>
      updateGroup(id, input),
    onSuccess: () => {
      message.success(t('admin.groups.updated'));
      void queryClient.invalidateQueries({ queryKey: groupKeys.all });
      setEditingGroup(null);
      editForm.resetFields();
    },
    onError: (error) => showApiError(message, error, adminErrorMessage),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteGroup(id),
    onSuccess: () => {
      message.success(t('admin.groups.deleted'));
      void queryClient.invalidateQueries({ queryKey: groupKeys.all });
    },
    onError: (error) => showApiError(message, error, adminErrorMessage),
  });

  const columns = [
    {
      title: t('admin.groups.columns.name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string, group: UserGroup) => (
        <Link to={`/admin/groups/${group.id}`}>{name}</Link>
      ),
    },
    {
      title: t('admin.groups.columns.description'),
      dataIndex: 'description',
      key: 'description',
      render: (desc: string | null) => desc ?? '—',
    },
    {
      title: t('admin.groups.columns.member_count'),
      dataIndex: 'member_count',
      key: 'member_count',
      width: 120,
    },
    {
      title: t('admin.groups.columns.created_at'),
      dataIndex: 'created_at',
      key: 'created_at',
      render: (val: string) => fmtDate(val),
      width: 180,
    },
    {
      title: t('admin.groups.columns.actions'),
      key: 'actions',
      width: 160,
      render: (_: unknown, group: UserGroup) => (
        <Space>
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            aria-label={t('admin.groups.edit')}
            onClick={() => {
              setEditingGroup(group);
              editForm.setFieldsValue({
                name: group.name,
                description: group.description ?? undefined,
              });
            }}
          />
          <Popconfirm
            title={t('admin.groups.delete_confirm', { name: group.name })}
            okText={t('common.delete')}
            cancelText={t('common.cancel')}
            okButtonProps={{ danger: true, loading: deleteMutation.isPending }}
            onConfirm={() => deleteMutation.mutate(group.id)}
          >
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              aria-label={t('admin.groups.delete')}
            />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const isEmpty = !groupsQuery.isLoading && (groupsQuery.data?.content.length ?? 0) === 0;

  return (
    <>
      <PageHeader
        docsAnchor="cfg-groups"
        title={t('admin.groups.title')}
        subtitle={t('admin.groups.description')}
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateOpen(true)}
          >
            {t('admin.groups.create')}
          </Button>
        }
      />

      {groupsQuery.isLoading ? (
        <Skeleton active />
      ) : isEmpty ? (
        <EmptyState
          title={t('admin.groups.empty.title')}
          description={t('admin.groups.empty.description')}
          action={
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setCreateOpen(true)}
            >
              {t('admin.groups.create')}
            </Button>
          }
        />
      ) : (
        <Table
          rowKey="id"
          dataSource={groupsQuery.data?.content ?? []}
          columns={columns}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: groupsQuery.data?.total_elements ?? 0,
            onChange: (newPage, newSize) => {
              setPage(newPage - 1);
              setSize(newSize);
            },
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50', '100'],
          }}
        />
      )}

      <Modal
        title={t('admin.groups.create_modal_title')}
        open={createOpen}
        destroyOnHidden
        onCancel={() => {
          setCreateOpen(false);
          createForm.resetFields();
        }}
        onOk={() => {
          createForm
            .validateFields()
            .then((values) =>
              createMutation.mutate({
                name: values.name,
                description: values.description?.trim() ? values.description : null,
              }),
            )
            .catch(() => {});
        }}
        okButtonProps={{ loading: createMutation.isPending }}
        okText={t('common.create')}
        cancelText={t('common.cancel')}
      >
        <Form layout="vertical" form={createForm} preserve={false}>
          <Form.Item
            name="name"
            label={t('admin.groups.fields.name')}
            rules={[
              { required: true, message: t('admin.groups.validation.name_required') },
              { min: 1, max: 128, message: t('admin.groups.validation.name_size') },
            ]}
          >
            <Input maxLength={128} autoFocus />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('admin.groups.fields.description')}
            rules={[{ max: 512, message: t('admin.groups.validation.description_size') }]}
          >
            <Input.TextArea maxLength={512} rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('admin.groups.edit_modal_title')}
        open={!!editingGroup}
        destroyOnHidden
        onCancel={() => {
          setEditingGroup(null);
          editForm.resetFields();
        }}
        onOk={() => {
          editForm
            .validateFields()
            .then((values) => {
              if (!editingGroup) return;
              updateMutation.mutate({
                id: editingGroup.id,
                input: {
                  name: values.name,
                  description: values.description?.trim() ? values.description : null,
                },
              });
            })
            .catch(() => {});
        }}
        okButtonProps={{ loading: updateMutation.isPending }}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
      >
        <Form layout="vertical" form={editForm} preserve={false}>
          <Form.Item
            name="name"
            label={t('admin.groups.fields.name')}
            rules={[
              { required: true, message: t('admin.groups.validation.name_required') },
              { min: 1, max: 128, message: t('admin.groups.validation.name_size') },
            ]}
          >
            <Input maxLength={128} autoFocus />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('admin.groups.fields.description')}
            rules={[{ max: 512, message: t('admin.groups.validation.description_size') }]}
          >
            <Input.TextArea maxLength={512} rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
