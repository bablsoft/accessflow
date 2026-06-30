import { Button, Card, Checkbox, Input, Select, Space, Tag } from 'antd';
import { DeleteOutlined, HolderOutlined } from '@ant-design/icons';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useTranslation } from 'react-i18next';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { RiskPill } from '@/components/common/RiskPill';
import type { ApiConnector, Datasource } from '@/types/api';
import { targetKindLabel } from '@/utils/enumLabels';
import type { DraftMember } from './groupBuilder';

interface GroupMemberCardProps {
  member: DraftMember;
  index: number;
  readOnly?: boolean;
  datasources: Datasource[];
  connectors: ApiConnector[];
  onChange: (next: DraftMember) => void;
  onRemove: () => void;
}

export function GroupMemberCard({
  member,
  index,
  readOnly = false,
  datasources,
  connectors,
  onChange,
  onRemove,
}: GroupMemberCardProps) {
  const { t } = useTranslation();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: member.key,
    disabled: readOnly,
  });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.6 : 1,
  };

  const datasource = datasources.find((d) => d.id === member.datasourceId);

  return (
    <div ref={setNodeRef} style={style} data-testid={`group-member-${index}`}>
      <Card
        size="small"
        styles={{ body: { padding: 14 } }}
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {!readOnly && (
              <button
                type="button"
                className="af-icon-btn"
                aria-label={t('requestGroups.builder.dragHandle', { index: index + 1 })}
                style={{ cursor: 'grab', touchAction: 'none' }}
                {...attributes}
                {...listeners}
              >
                <HolderOutlined />
              </button>
            )}
            <span className="mono muted">#{index + 1}</span>
            <Tag>{targetKindLabel(t, member.targetKind)}</Tag>
            {member.aiRiskLevel != null && (
              <RiskPill level={member.aiRiskLevel} score={member.aiRiskScore} size="sm" />
            )}
          </div>
        }
        extra={
          !readOnly && (
            <Button
              size="small"
              danger
              type="text"
              icon={<DeleteOutlined />}
              aria-label={t('requestGroups.builder.removeMember', { index: index + 1 })}
              onClick={onRemove}
            />
          )
        }
      >
        {member.targetKind === 'QUERY' ? (
          <Space direction="vertical" style={{ width: '100%' }} size={10}>
            <label style={{ display: 'block' }}>
              <div className="muted" style={{ marginBottom: 4 }}>
                {t('requestGroups.builder.datasource')}
              </div>
              <Select
                style={{ width: '100%' }}
                disabled={readOnly}
                showSearch
                optionFilterProp="label"
                placeholder={t('requestGroups.builder.selectDatasource')}
                value={member.datasourceId ?? undefined}
                onChange={(v) => onChange({ ...member, datasourceId: v })}
                options={datasources.map((d) => ({ value: d.id, label: d.name }))}
              />
            </label>
            <SqlEditor
              value={member.sqlText}
              onChange={(next) => onChange({ ...member, sqlText: next })}
              dbType={datasource?.db_type}
              readOnly={readOnly}
              height={160}
            />
            <Checkbox
              checked={member.transactional}
              disabled={readOnly}
              onChange={(e) => onChange({ ...member, transactional: e.target.checked })}
            >
              {t('requestGroups.builder.transactional')}
            </Checkbox>
          </Space>
        ) : (
          <Space direction="vertical" style={{ width: '100%' }} size={10}>
            <label style={{ display: 'block' }}>
              <div className="muted" style={{ marginBottom: 4 }}>
                {t('requestGroups.builder.connector')}
              </div>
              <Select
                style={{ width: '100%' }}
                disabled={readOnly}
                showSearch
                optionFilterProp="label"
                placeholder={t('requestGroups.builder.selectConnector')}
                value={member.connectorId ?? undefined}
                onChange={(v) => onChange({ ...member, connectorId: v })}
                options={connectors.map((c) => ({ value: c.id, label: c.name }))}
              />
            </label>
            <Space.Compact style={{ width: '100%' }}>
              <Input
                style={{ width: 110 }}
                value={member.verb}
                disabled={readOnly}
                maxLength={16}
                onChange={(e) => onChange({ ...member, verb: e.target.value })}
                aria-label={t('requestGroups.builder.verb')}
              />
              <Input
                value={member.requestPath}
                disabled={readOnly}
                maxLength={4000}
                placeholder="/v1/resource"
                onChange={(e) => onChange({ ...member, requestPath: e.target.value })}
                aria-label={t('requestGroups.builder.path')}
              />
            </Space.Compact>
            <label style={{ display: 'block' }}>
              <div className="muted" style={{ marginBottom: 4 }}>
                {t('requestGroups.builder.requestBody')}
              </div>
              <Input.TextArea
                rows={3}
                disabled={readOnly}
                value={member.requestBody}
                onChange={(e) => onChange({ ...member, requestBody: e.target.value })}
                placeholder={t('requestGroups.builder.requestBodyHint')}
              />
            </label>
          </Space>
        )}
      </Card>
    </div>
  );
}
