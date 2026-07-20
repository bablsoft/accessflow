import { useEffect, useState } from 'react';
import { App, Alert, Button, Form, Input, Modal, Select, Switch, Table, Tag, Tooltip } from 'antd';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import {
  apiConnectorVariableKeys,
  createApiConnectorVariable,
  deleteApiConnectorVariable,
  listApiConnectorVariables,
  reorderApiConnectorVariables,
  updateApiConnectorVariable,
} from '@/api/apiConnectorVariables';
import {
  API_VARIABLE_ALGORITHMS,
  API_VARIABLE_ENCODINGS,
  API_VARIABLE_KINDS,
  apiVariableAlgorithmLabel,
  apiVariableEncodingLabel,
  apiVariableKindLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  ApiConnectorVariable,
  ApiVariableAlgorithm,
  ApiVariableEncoding,
  ApiVariableKind,
  CreateApiConnectorVariableInput,
  UpdateApiConnectorVariableInput,
} from '@/types/api';

/** Which optional fields a kind uses. Mirrors the server-side save-time validation exactly. */
const KIND_RULES: Record<
  ApiVariableKind,
  {
    expression: 'required' | 'optional' | 'none';
    algorithms: readonly ApiVariableAlgorithm[];
    encoding: 'required' | 'optional' | 'none';
    secret: boolean;
  }
> = {
  CONSTANT: { expression: 'required', algorithms: [], encoding: 'none', secret: false },
  UUID: { expression: 'none', algorithms: [], encoding: 'none', secret: false },
  TIMESTAMP: { expression: 'optional', algorithms: [], encoding: 'none', secret: false },
  EPOCH_MILLIS: { expression: 'none', algorithms: [], encoding: 'none', secret: false },
  RANDOM_HEX: { expression: 'optional', algorithms: [], encoding: 'optional', secret: false },
  HASH: { expression: 'required', algorithms: ['SHA256', 'MD5'], encoding: 'optional', secret: false },
  HMAC: {
    expression: 'required',
    algorithms: ['HMAC_SHA256', 'HMAC_SHA512'],
    encoding: 'optional',
    secret: true,
  },
  ENCODE: { expression: 'required', algorithms: [], encoding: 'required', secret: false },
};

export function ApiConnectorVariablesTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const { message, modal } = App.useApp();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ApiConnectorVariable | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const variablesQuery = useQuery({
    queryKey: apiConnectorVariableKeys.list(connectorId),
    queryFn: () => listApiConnectorVariables(connectorId),
  });
  const variables = variablesQuery.data ?? [];

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: apiConnectorVariableKeys.list(connectorId) });

  const deleteMutation = useMutation({
    mutationFn: (variableId: string) => deleteApiConnectorVariable(connectorId, variableId),
    onSuccess: () => {
      void invalidate();
      message.success(t('apiGov.settings.variables.delete_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('apiGov.settings.variables.delete_error')),
      );
    },
  });

  const reorderMutation = useMutation({
    mutationFn: (variableIds: string[]) => reorderApiConnectorVariables(connectorId, variableIds),
    onSuccess: () => void invalidate(),
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('apiGov.settings.variables.reorder_error')),
      );
    },
  });

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= variables.length) return;
    const next = variables.map((v) => v.id);
    const moved = next[index] as string;
    next[index] = next[target] as string;
    next[target] = moved;
    reorderMutation.mutate(next);
  };

  const onDelete = (variable: ApiConnectorVariable) => {
    modal.confirm({
      title: t('apiGov.settings.variables.delete_confirm_title'),
      content: t('apiGov.settings.variables.delete_confirm_body', { name: variable.name }),
      okType: 'danger',
      okText: t('apiGov.settings.variables.delete'),
      cancelText: t('common.cancel'),
      onOk: () => deleteMutation.mutateAsync(variable.id),
    });
  };

  return (
    <div style={{ padding: 28 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: 16,
          gap: 16,
        }}
      >
        <div>
          <div style={{ fontWeight: 600 }}>{t('apiGov.settings.variables.title')}</div>
          <div className="muted" style={{ fontSize: 12, maxWidth: 640 }}>
            {t('apiGov.settings.variables.description')}
          </div>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditing(null);
            setModalOpen(true);
          }}
        >
          {t('apiGov.settings.variables.add')}
        </Button>
      </div>

      {!variablesQuery.isLoading && variables.length === 0 ? (
        <EmptyState
          title={t('apiGov.settings.variables.empty_title')}
          description={t('apiGov.settings.variables.empty_description')}
        />
      ) : (
        <Table<ApiConnectorVariable>
          rowKey="id"
          size="middle"
          loading={variablesQuery.isLoading}
          dataSource={variables}
          pagination={false}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('apiGov.settings.variables.col_name'),
              width: 200,
              render: (_v, variable) => (
                <span className="mono" style={{ fontSize: 12 }}>{`{{${variable.name}}}`}</span>
              ),
            },
            {
              title: t('apiGov.settings.variables.col_kind'),
              dataIndex: 'kind',
              width: 150,
              render: (v: ApiVariableKind) => <Tag>{apiVariableKindLabel(t, v)}</Tag>,
            },
            {
              title: t('apiGov.settings.variables.col_expression'),
              render: (_v, variable) => (
                <span className="mono" style={{ fontSize: 12 }}>
                  {variable.expression ?? '—'}
                </span>
              ),
            },
            {
              title: t('apiGov.settings.variables.col_target'),
              width: 180,
              render: (_v, variable) =>
                variable.target ? (
                  <span className="mono" style={{ fontSize: 12 }}>
                    {variable.target}
                  </span>
                ) : (
                  <span className="muted" style={{ fontSize: 12 }}>
                    {t('apiGov.settings.variables.target_none')}
                  </span>
                ),
            },
            {
              title: t('apiGov.settings.variables.col_flags'),
              width: 170,
              render: (_v, variable) => (
                <>
                  {variable.has_secret && (
                    <Tooltip title={t('apiGov.settings.variables.has_secret')}>
                      <Tag icon={<KeyOutlined aria-label={t('apiGov.settings.variables.has_secret')} />}>
                        {t('apiGov.settings.variables.secret_tag')}
                      </Tag>
                    </Tooltip>
                  )}
                  {variable.overridable && (
                    <Tooltip title={t('apiGov.settings.variables.overridable_hint')}>
                      <Tag color="blue">{t('apiGov.settings.variables.overridable_tag')}</Tag>
                    </Tooltip>
                  )}
                </>
              ),
            },
            {
              title: t('apiGov.settings.variables.col_actions'),
              width: 170,
              align: 'right',
              render: (_v, variable, index) => (
                <>
                  <Button
                    size="small"
                    type="text"
                    icon={<ArrowUpOutlined />}
                    aria-label={t('apiGov.settings.variables.move_up')}
                    disabled={index === 0 || reorderMutation.isPending}
                    onClick={() => move(index, -1)}
                  />
                  <Button
                    size="small"
                    type="text"
                    icon={<ArrowDownOutlined />}
                    aria-label={t('apiGov.settings.variables.move_down')}
                    disabled={index === variables.length - 1 || reorderMutation.isPending}
                    onClick={() => move(index, 1)}
                  />
                  <Button
                    size="small"
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={t('apiGov.settings.variables.edit')}
                    onClick={() => {
                      setEditing(variable);
                      setModalOpen(true);
                    }}
                  />
                  <Button
                    size="small"
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={t('apiGov.settings.variables.delete')}
                    onClick={() => onDelete(variable)}
                    disabled={deleteMutation.isPending}
                  />
                </>
              ),
            },
          ]}
        />
      )}

      <VariableModal
        open={modalOpen}
        connectorId={connectorId}
        variable={editing}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}

interface VariableFormValues {
  name: string;
  kind: ApiVariableKind;
  expression?: string;
  algorithm?: ApiVariableAlgorithm;
  encoding?: ApiVariableEncoding;
  secret?: string;
  target?: string;
  overridable: boolean;
  description?: string;
}

interface VariableModalProps {
  open: boolean;
  connectorId: string;
  variable: ApiConnectorVariable | null;
  onClose: () => void;
}

function VariableModal({ open, connectorId, variable, onClose }: VariableModalProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<VariableFormValues>();
  const kind = Form.useWatch('kind', form) ?? 'CONSTANT';
  const algorithm = Form.useWatch('algorithm', form);
  const rules = KIND_RULES[kind];

  useEffect(() => {
    if (!open) return;
    if (variable) {
      form.setFieldsValue({
        name: variable.name,
        kind: variable.kind,
        expression: variable.expression ?? undefined,
        algorithm: variable.algorithm ?? undefined,
        encoding: variable.encoding ?? undefined,
        // Never round-tripped: the server does not return it, so an empty field means "unchanged".
        secret: undefined,
        target: variable.target ?? undefined,
        overridable: variable.overridable,
        description: variable.description ?? undefined,
      });
    } else {
      form.resetFields();
    }
  }, [open, variable, form]);

  // Changing the kind can invalidate fields the previous kind allowed; clear them so a stale value
  // is never submitted (the server would reject it as forbidden-for-this-kind).
  useEffect(() => {
    if (!open) return;
    const next: Partial<VariableFormValues> = {};
    if (rules.algorithms.length === 0) next.algorithm = undefined;
    if (rules.encoding === 'none') next.encoding = undefined;
    if (rules.expression === 'none') next.expression = undefined;
    if (!rules.secret) next.secret = undefined;
    if (rules.secret) next.overridable = false;
    form.setFieldsValue(next);
  }, [kind, open, rules, form]);

  const saveMutation = useMutation({
    mutationFn: (input: UpdateApiConnectorVariableInput) =>
      variable
        ? updateApiConnectorVariable(connectorId, variable.id, input)
        : createApiConnectorVariable(connectorId, input as CreateApiConnectorVariableInput),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: apiConnectorVariableKeys.list(connectorId),
      });
      message.success(t('apiGov.settings.variables.save_success'));
      onClose();
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('apiGov.settings.variables.save_error')),
      );
    },
  });

  const onFinish = (values: VariableFormValues) => {
    const kindRules = KIND_RULES[values.kind];
    saveMutation.mutate({
      name: values.name.trim(),
      kind: values.kind,
      expression: kindRules.expression === 'none' ? null : (values.expression?.trim() ?? null),
      algorithm: kindRules.algorithms.length > 0 ? (values.algorithm ?? null) : null,
      encoding: kindRules.encoding === 'none' ? null : (values.encoding ?? null),
      // Blank means "leave the stored secret alone" — it is never sent back to the client.
      secret: values.secret?.trim() ? values.secret.trim() : undefined,
      target: values.target?.trim() ? values.target.trim() : null,
      overridable: values.overridable,
      description: values.description?.trim() ?? null,
    });
  };

  return (
    <Modal
      open={open}
      title={
        variable
          ? t('apiGov.settings.variables.edit_title')
          : t('apiGov.settings.variables.create_title')
      }
      onCancel={onClose}
      onOk={() => form.submit()}
      okText={t('common.save')}
      cancelText={t('common.cancel')}
      confirmLoading={saveMutation.isPending}
      destroyOnHidden
      width={620}
    >
      <Form<VariableFormValues>
        form={form}
        // Namespaces the generated input ids (`connectorVariable_name`, …). Without it AntD emits a
        // bare `id="name"`, which collides with the Configuration tab's own name field — that tab
        // stays mounted behind this modal, so the duplicate id makes each `<label for>` resolve to
        // the wrong (hidden) input and the fields stop being reachable by their label.
        name="connectorVariable"
        layout="vertical"
        initialValues={{ kind: 'CONSTANT', overridable: false }}
        onFinish={onFinish}
      >
        <Form.Item
          name="name"
          label={t('apiGov.settings.variables.label_name')}
          extra={t('apiGov.settings.variables.name_hint')}
          rules={[
            { required: true, message: t('apiGov.settings.variables.name_required') },
            {
              pattern: /^[A-Za-z][A-Za-z0-9_]{0,63}$/,
              message: t('apiGov.settings.variables.name_invalid'),
            },
          ]}
        >
          <Input placeholder={t('apiGov.settings.variables.placeholder_name')} />
        </Form.Item>

        <Form.Item
          name="kind"
          label={t('apiGov.settings.variables.label_kind')}
          rules={[{ required: true, message: t('apiGov.settings.variables.kind_required') }]}
        >
          <Select<ApiVariableKind>
            options={enumOptions(API_VARIABLE_KINDS, apiVariableKindLabel, t)}
          />
        </Form.Item>

        {rules.expression !== 'none' && (
          <Form.Item
            name="expression"
            label={t('apiGov.settings.variables.label_expression')}
            extra={t(`apiGov.settings.variables.expression_hint_${kind.toLowerCase()}`)}
            rules={[
              {
                required: rules.expression === 'required',
                message: t('apiGov.settings.variables.expression_required'),
              },
              { max: 8192, message: t('apiGov.settings.variables.expression_too_long') },
            ]}
          >
            <Input.TextArea
              rows={2}
              placeholder={t('apiGov.settings.variables.placeholder_expression')}
            />
          </Form.Item>
        )}

        {rules.algorithms.length > 0 && (
          <Form.Item
            name="algorithm"
            label={t('apiGov.settings.variables.label_algorithm')}
            rules={[{ required: true, message: t('apiGov.settings.variables.algorithm_required') }]}
          >
            <Select<ApiVariableAlgorithm>
              options={enumOptions(
                API_VARIABLE_ALGORITHMS.filter((a) => rules.algorithms.includes(a)),
                apiVariableAlgorithmLabel,
                t,
              )}
            />
          </Form.Item>
        )}

        {algorithm === 'MD5' && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('apiGov.settings.variables.md5_warning')}
          />
        )}

        {rules.encoding !== 'none' && (
          <Form.Item
            name="encoding"
            label={t('apiGov.settings.variables.label_encoding')}
            extra={
              rules.encoding === 'optional'
                ? t('apiGov.settings.variables.encoding_default_hint')
                : undefined
            }
            rules={[
              {
                required: rules.encoding === 'required',
                message: t('apiGov.settings.variables.encoding_required'),
              },
            ]}
          >
            <Select<ApiVariableEncoding>
              allowClear={rules.encoding === 'optional'}
              options={enumOptions(API_VARIABLE_ENCODINGS, apiVariableEncodingLabel, t)}
            />
          </Form.Item>
        )}

        {rules.secret && (
          <Form.Item
            name="secret"
            label={t('apiGov.settings.variables.label_secret')}
            extra={
              variable?.has_secret
                ? t('apiGov.settings.variables.secret_unchanged_hint')
                : t('apiGov.settings.variables.secret_hint')
            }
            rules={[
              {
                required: !variable?.has_secret,
                message: t('apiGov.settings.variables.secret_required'),
              },
              { max: 4096, message: t('apiGov.settings.variables.secret_too_long') },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        )}

        <Form.Item
          name="target"
          label={t('apiGov.settings.variables.label_target')}
          extra={t('apiGov.settings.variables.target_hint')}
          rules={[
            { max: 140, message: t('apiGov.settings.variables.target_invalid') },
            {
              pattern: /^(header|query):[A-Za-z0-9!#$%&'*+\-.^_`|~]{1,128}$/,
              message: t('apiGov.settings.variables.target_invalid'),
            },
          ]}
        >
          <Input placeholder={t('apiGov.settings.variables.placeholder_target')} />
        </Form.Item>

        <Form.Item
          name="overridable"
          label={t('apiGov.settings.variables.label_overridable')}
          extra={
            rules.secret
              ? t('apiGov.settings.variables.overridable_secret_hint')
              : t('apiGov.settings.variables.overridable_hint')
          }
          valuePropName="checked"
        >
          {/* Mirrors the server rule (and the DB CHECK): a secret-bearing variable is never overridable. */}
          <Switch disabled={rules.secret} />
        </Form.Item>

        <Form.Item
          name="description"
          label={t('apiGov.settings.variables.label_description')}
          rules={[{ max: 512, message: t('apiGov.settings.variables.description_too_long') }]}
        >
          <Input placeholder={t('apiGov.settings.variables.placeholder_description')} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
