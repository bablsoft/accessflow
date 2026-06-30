import { useMemo, useState } from 'react';
import {
  App,
  Button,
  Card,
  Checkbox,
  DatePicker,
  Dropdown,
  Form,
  Input,
  Space,
} from 'antd';
import { DatabaseOutlined, ApiOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { PageHeader } from '@/components/common/PageHeader';
import { RiskPill } from '@/components/common/RiskPill';
import { EmptyState } from '@/components/common/EmptyState';
import { datasourceKeys, listDatasources } from '@/api/datasources';
import { apiConnectorKeys, listApiConnectors } from '@/api/apiConnectors';
import {
  createRequestGroup,
  submitRequestGroup,
  updateRequestGroup,
} from '@/api/requestGroups';
import { aggregateRisk } from '@/utils/requestGroupRisk';
import { GroupMemberCard } from './GroupMemberCard';
import { memberToBody, memberValid, newMember, type DraftMember } from './groupBuilder';

export default function GroupBuilderPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [continueOnError, setContinueOnError] = useState(false);
  const [members, setMembers] = useState<DraftMember[]>([]);
  const [groupId, setGroupId] = useState<string | null>(null);
  const [breakGlass, setBreakGlass] = useState(false);
  const [scheduledFor, setScheduledFor] = useState<Dayjs | null>(null);

  const datasourcesQuery = useQuery({
    queryKey: datasourceKeys.list({ size: 100 }),
    queryFn: () => listDatasources({ size: 100 }),
  });
  const connectorsQuery = useQuery({
    queryKey: apiConnectorKeys.list({ size: 100 }),
    queryFn: () => listApiConnectors({ size: 100 }),
  });

  const datasources = useMemo(
    () => datasourcesQuery.data?.content ?? [],
    [datasourcesQuery.data],
  );
  const connectors = useMemo(() => connectorsQuery.data?.content ?? [], [connectorsQuery.data]);

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const aggregate = useMemo(
    () =>
      aggregateRisk(
        members.map((m) => ({ ai_risk_level: m.aiRiskLevel, ai_risk_score: m.aiRiskScore })),
      ),
    [members],
  );

  const nameValid = name.trim().length >= 1 && name.length <= 255;
  const descriptionValid = description.length <= 4000;
  const allMembersValid = members.length >= 1 && members.every(memberValid);
  const scheduleInPast = !!scheduledFor && !scheduledFor.isAfter(dayjs());
  const canSave = nameValid && descriptionValid && allMembersValid;

  const buildPayload = () => ({
    name: name.trim(),
    description: description.trim() || null,
    continue_on_error: continueOnError,
    items: members.map(memberToBody),
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const payload = buildPayload();
      return groupId
        ? updateRequestGroup(groupId, payload)
        : createRequestGroup(payload);
    },
    onSuccess: (saved) => {
      setGroupId(saved.id);
      message.success(t('requestGroups.builder.saved'));
    },
    onError: () => message.error(t('requestGroups.error')),
  });

  const submitMutation = useMutation({
    mutationFn: async () => {
      const payload = buildPayload();
      const saved = groupId
        ? await updateRequestGroup(groupId, payload)
        : await createRequestGroup(payload);
      await submitRequestGroup(saved.id, {
        break_glass: breakGlass,
        scheduled_for: scheduledFor ? scheduledFor.toISOString() : null,
      });
      return saved;
    },
    onSuccess: (saved) => {
      message.success(t('requestGroups.builder.submitted'));
      navigate(`/request-groups/${saved.id}`);
    },
    onError: () => message.error(t('requestGroups.error')),
  });

  const addMember = (kind: DraftMember['targetKind']) =>
    setMembers((prev) => [...prev, newMember(kind)]);

  const updateMember = (key: string, next: DraftMember) =>
    setMembers((prev) => prev.map((m) => (m.key === key ? next : m)));

  const removeMember = (key: string) =>
    setMembers((prev) => prev.filter((m) => m.key !== key));

  const onDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    setMembers((prev) => {
      const from = prev.findIndex((m) => m.key === active.id);
      const to = prev.findIndex((m) => m.key === over.id);
      if (from < 0 || to < 0) return prev;
      return arrayMove(prev, from, to);
    });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('requestGroups.builder.title')}
        subtitle={t('requestGroups.builder.subtitle')}
        actions={
          <Button onClick={() => navigate('/request-groups')}>{t('common.back')}</Button>
        }
      />
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: 24,
          display: 'grid',
          gridTemplateColumns: '1fr 320px',
          gap: 20,
          alignItems: 'start',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card size="small">
            <Form layout="vertical">
              <Form.Item
                label={t('requestGroups.builder.name')}
                required
                validateStatus={name && !nameValid ? 'error' : undefined}
                help={name && !nameValid ? t('requestGroups.builder.nameRule') : undefined}
              >
                <Input
                  value={name}
                  maxLength={255}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t('requestGroups.builder.namePlaceholder')}
                />
              </Form.Item>
              <Form.Item
                label={t('requestGroups.builder.description')}
                validateStatus={!descriptionValid ? 'error' : undefined}
                help={!descriptionValid ? t('requestGroups.builder.descriptionRule') : undefined}
              >
                <Input.TextArea
                  rows={2}
                  value={description}
                  maxLength={4000}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </Form.Item>
              <Checkbox
                checked={continueOnError}
                onChange={(e) => setContinueOnError(e.target.checked)}
              >
                {t('requestGroups.builder.continueOnError')}
              </Checkbox>
              <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                {t('requestGroups.builder.continueOnErrorHint')}
              </div>
            </Form>
          </Card>

          {members.length === 0 ? (
            <Card size="small">
              <EmptyState
                title={t('requestGroups.builder.emptyTitle')}
                description={t('requestGroups.builder.emptyDescription')}
              />
            </Card>
          ) : (
            <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
              <SortableContext
                items={members.map((m) => m.key)}
                strategy={verticalListSortingStrategy}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {members.map((m, i) => (
                    <GroupMemberCard
                      key={m.key}
                      member={m}
                      index={i}
                      datasources={datasources}
                      connectors={connectors}
                      onChange={(next) => updateMember(m.key, next)}
                      onRemove={() => removeMember(m.key)}
                    />
                  ))}
                </div>
              </SortableContext>
            </DndContext>
          )}

          <Dropdown
            menu={{
              items: [
                {
                  key: 'QUERY',
                  icon: <DatabaseOutlined />,
                  label: t('requestGroups.builder.addQuery'),
                  onClick: () => addMember('QUERY'),
                },
                {
                  key: 'API_CALL',
                  icon: <ApiOutlined />,
                  label: t('requestGroups.builder.addApiCall'),
                  onClick: () => addMember('API_CALL'),
                },
              ],
            }}
          >
            <Button icon={<PlusOutlined />}>{t('requestGroups.builder.addStep')}</Button>
          </Dropdown>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Card size="small" title={t('requestGroups.builder.aggregateRisk')}>
            {aggregate ? (
              <RiskPill level={aggregate.level} score={aggregate.score} />
            ) : (
              <span className="muted">{t('requestGroups.builder.noRiskYet')}</span>
            )}
            <div className="muted" style={{ fontSize: 12, marginTop: 8 }}>
              {t('requestGroups.builder.aggregateRiskHint')}
            </div>
          </Card>

          <Card size="small" title={t('requestGroups.builder.submitOptions')}>
            <Space direction="vertical" style={{ width: '100%' }} size={10}>
              <label style={{ display: 'block' }}>
                <div className="muted" style={{ marginBottom: 4 }}>
                  {t('requestGroups.builder.scheduleFor')}
                </div>
                <DatePicker
                  showTime
                  style={{ width: '100%' }}
                  format="YYYY-MM-DD HH:mm"
                  value={scheduledFor}
                  onChange={setScheduledFor}
                />
                {scheduleInPast && (
                  <div style={{ color: 'var(--af-error)', fontSize: 12, marginTop: 4 }}>
                    {t('requestGroups.builder.scheduleInPast')}
                  </div>
                )}
              </label>
              <Checkbox checked={breakGlass} onChange={(e) => setBreakGlass(e.target.checked)}>
                {t('requestGroups.builder.breakGlass')}
              </Checkbox>
            </Space>
          </Card>

          <Space>
            <Button
              onClick={() => saveMutation.mutate()}
              loading={saveMutation.isPending}
              disabled={!canSave}
            >
              {t('requestGroups.builder.saveDraft')}
            </Button>
            <Button
              type="primary"
              onClick={() => submitMutation.mutate()}
              loading={submitMutation.isPending}
              disabled={!canSave || scheduleInPast}
            >
              {t('requestGroups.builder.submit')}
            </Button>
          </Space>
        </div>
      </div>
    </div>
  );
}
