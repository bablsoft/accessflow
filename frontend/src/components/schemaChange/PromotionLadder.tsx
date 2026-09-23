import { Button, Popconfirm, Tag, Tooltip } from 'antd';
import { CheckCircleFilled, LockOutlined, RocketOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { SchemaChangeLadder, SchemaChangeLadderRung } from '@/types/api';
import { schemaChangePromotionStatusLabel } from '@/utils/enumLabels';
import { schemaChangeLadderRungColor, schemaChangePromotionStatusColor } from '@/utils/statusColors';
import { ladderRungReason } from '@/utils/schemaChange';
import { fmtDate } from '@/utils/dateFormat';

interface PromotionLadderProps {
  ladder: SchemaChangeLadder;
  onPromote: (rung: SchemaChangeLadderRung) => void;
  onCancel: (promotionId: string) => void;
  /** The environment a promotion is being submitted to, for the button spinner. */
  promotingEnvironmentId?: string | null;
  cancellingPromotionId?: string | null;
}

/**
 * The promotion ladder: every environment in `sort_order` with its promotion state. The next
 * promotable environment is actionable; every other rung says *why* it is not — a blocked step
 * never renders as a bare disabled control.
 */
export function PromotionLadder({
  ladder,
  onPromote,
  onCancel,
  promotingEnvironmentId,
  cancellingPromotionId,
}: PromotionLadderProps) {
  const { t } = useTranslation();

  return (
    <ol
      aria-label={t('schemaChange.ladder.ariaLabel')}
      style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 10 }}
    >
      {ladder.rungs.map((rung, index) => {
        const color = schemaChangeLadderRungColor(rung.state);
        const promotion = rung.latest_promotion;
        const reason = ladderRungReason(t, rung);
        const cancellable =
          rung.state === 'IN_PROGRESS' &&
          promotion != null &&
          (promotion.status === 'PENDING' || promotion.status === 'IN_REVIEW');
        return (
          <li
            key={rung.environment_id}
            data-testid={`ladder-rung-${rung.environment_name}`}
            style={{
              display: 'flex',
              gap: 12,
              alignItems: 'flex-start',
              padding: '10px 12px',
              border: `1px solid ${color.border}`,
              borderRadius: 8,
              background: 'var(--bg-elev)',
            }}
          >
            <span
              aria-hidden
              className="mono"
              style={{
                minWidth: 26,
                height: 26,
                borderRadius: 13,
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: color.fg,
                background: color.bg,
                fontSize: 12,
              }}
            >
              {rung.state === 'APPLIED' ? <CheckCircleFilled /> : index + 1}
            </span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <strong>{rung.environment_name}</strong>
                <Tag style={{ color: color.fg, background: color.bg, borderColor: color.border }}>
                  {t(`schemaChange.ladder.state.${rung.state}`)}
                </Tag>
                {promotion && (
                  <Tooltip title={fmtDate(promotion.submitted_at)}>
                    <Tag
                      style={{
                        color: schemaChangePromotionStatusColor(promotion.status).fg,
                        background: schemaChangePromotionStatusColor(promotion.status).bg,
                        borderColor: schemaChangePromotionStatusColor(promotion.status).border,
                      }}
                    >
                      {t('schemaChange.ladder.latestPromotion', {
                        status: schemaChangePromotionStatusLabel(t, promotion.status),
                      })}
                    </Tag>
                  </Tooltip>
                )}
                {promotion?.request_group_id && (
                  <Link to={`/request-groups/${promotion.request_group_id}`} style={{ fontSize: 12 }}>
                    {t('schemaChange.ladder.openGroup')}
                  </Link>
                )}
              </div>
              {reason && (
                <div
                  data-testid={`ladder-reason-${rung.environment_name}`}
                  style={{ fontSize: 12, marginTop: 4, color: 'var(--fg-muted)' }}
                >
                  <LockOutlined style={{ marginRight: 6 }} />
                  {reason}
                </div>
              )}
              {promotion?.status === 'APPROVED' && (
                <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                  {t('schemaChange.ladder.approvedQueued')}
                </div>
              )}
              {promotion?.error_message &&
                (promotion.status === 'FAILED' || promotion.status === 'PARTIALLY_APPLIED') && (
                  <div style={{ fontSize: 12, marginTop: 4, color: 'var(--risk-crit)' }}>
                    {promotion.error_message}
                  </div>
                )}
            </div>
            <div>
              {rung.state === 'PROMOTABLE' && (
                <Popconfirm
                  title={t('schemaChange.ladder.promoteConfirmTitle', { environment: rung.environment_name })}
                  description={t('schemaChange.ladder.promoteConfirmBody')}
                  okText={t('schemaChange.ladder.promote')}
                  cancelText={t('common.cancel')}
                  onConfirm={() => onPromote(rung)}
                >
                  <Button
                    type="primary"
                    size="small"
                    icon={<RocketOutlined />}
                    loading={promotingEnvironmentId === rung.environment_id}
                  >
                    {t('schemaChange.ladder.promoteTo', { environment: rung.environment_name })}
                  </Button>
                </Popconfirm>
              )}
              {cancellable && (
                <Popconfirm
                  title={t('schemaChange.ladder.cancelConfirm')}
                  okText={t('schemaChange.ladder.cancelPromotion')}
                  cancelText={t('common.cancel')}
                  onConfirm={() => onCancel(promotion.id)}
                >
                  <Button size="small" danger loading={cancellingPromotionId === promotion.id}>
                    {t('schemaChange.ladder.cancelPromotion')}
                  </Button>
                </Popconfirm>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
