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
      className="u-m-0 u-p-0 u-flex u-col u-gap-2" style={{ listStyle: 'none' }}
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
            className="u-flex u-gap-3 u-items-start u-rounded-md u-bg-elev u-py-2 u-px-3" style={{ border: `1px solid ${color.border}` }}
          >
            <span
              aria-hidden
              className="mono u-inline-flex u-items-center u-justify-center u-text-xs"
              style={{ minWidth: 26, height: 26, borderRadius: 13, color: color.fg, background: color.bg }}
            >
              {rung.state === 'APPLIED' ? <CheckCircleFilled /> : index + 1}
            </span>
            <div className="u-flex-1 u-min-w-0">
              <div className="u-flex u-gap-2 u-items-center u-wrap">
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
                  <Link to={`/request-groups/${promotion.request_group_id}`} className="u-text-xs">
                    {t('schemaChange.ladder.openGroup')}
                  </Link>
                )}
              </div>
              {reason && (
                <div
                  data-testid={`ladder-reason-${rung.environment_name}`}
                  className="u-text-xs u-mt-1 u-fg-muted"
                >
                  <LockOutlined className="u-mr-2" />
                  {reason}
                </div>
              )}
              {promotion?.status === 'APPROVED' && (
                <div className="muted u-text-xs u-mt-1">
                  {t('schemaChange.ladder.approvedQueued')}
                </div>
              )}
              {promotion?.error_message &&
                (promotion.status === 'FAILED' || promotion.status === 'PARTIALLY_APPLIED') && (
                  <div className="u-text-xs u-mt-1 u-fg-danger">
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
