import { Button, Card, Tag, Tooltip } from 'antd';
import { DeleteOutlined, EditOutlined, HolderOutlined, WarningOutlined } from '@ant-design/icons';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useTranslation } from 'react-i18next';
import { RiskPill } from '@/components/common/RiskPill';
import type { ApiConnector, Datasource } from '@/types/api';
import { targetKindLabel } from '@/utils/enumLabels';
import { memberValid, type DraftMember } from './groupBuilder';

interface GroupMemberCardProps {
  member: DraftMember;
  index: number;
  readOnly?: boolean;
  datasources: Datasource[];
  connectors: ApiConnector[];
  /** Opens the full authoring drawer for this member (#559). */
  onEdit: () => void;
  onRemove: () => void;
}

/**
 * Compact summary card for one group step: sequence, kind, target, one-line preview, and risk.
 * All authoring happens in the {@link GroupMemberEditDrawer} opened via the Edit action, which
 * mounts the same panels as the standalone Query / API editors (#559).
 */
export function GroupMemberCard({
  member,
  index,
  readOnly = false,
  datasources,
  connectors,
  onEdit,
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

  const targetName =
    member.targetKind === 'QUERY'
      ? datasources.find((d) => d.id === member.datasourceId)?.name
      : connectors.find((c) => c.id === member.connectorId)?.name;
  const preview =
    member.targetKind === 'QUERY'
      ? member.sqlText.split('\n')[0]
      : member.requestPath
        ? `${member.verb} ${member.requestPath}`
        : '';
  const incomplete = !memberValid(member);

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
            {incomplete && (
              <Tooltip title={t('requestGroups.builder.stepIncomplete')}>
                <WarningOutlined
                  style={{ color: 'var(--af-warning)' }}
                  aria-label={t('requestGroups.builder.stepIncomplete')}
                />
              </Tooltip>
            )}
          </div>
        }
        extra={
          !readOnly && (
            <div style={{ display: 'flex', gap: 4 }}>
              <Button
                size="small"
                icon={<EditOutlined />}
                data-testid={`group-member-${index}-edit`}
                aria-label={t('requestGroups.builder.editStepAria', { index: index + 1 })}
                onClick={onEdit}
              >
                {t('requestGroups.builder.editStep')}
              </Button>
              <Button
                size="small"
                danger
                type="text"
                icon={<DeleteOutlined />}
                aria-label={t('requestGroups.builder.removeMember', { index: index + 1 })}
                onClick={onRemove}
              />
            </div>
          )
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
          <span className="muted" style={{ fontSize: 12 }}>
            {targetName ?? t('requestGroups.builder.noTargetYet')}
          </span>
          {preview ? (
            <span
              className="mono"
              style={{
                fontSize: 12,
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {preview}
            </span>
          ) : (
            <span className="muted" style={{ fontSize: 12 }}>
              {t('requestGroups.builder.emptyStepPreview')}
            </span>
          )}
        </div>
      </Card>
    </div>
  );
}
