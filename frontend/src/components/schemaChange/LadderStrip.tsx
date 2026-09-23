import { Skeleton, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { getSchemaChangeLadder, schemaChangeKeys } from '@/api/schemaChange';
import { schemaChangeLadderRungColor } from '@/utils/statusColors';
import { ladderRungReason } from '@/utils/schemaChange';

/** A compact, one-line ladder: one chip per environment, its state and reason on hover. */
export function LadderStrip({ changeSetId }: { changeSetId: string }) {
  const { t } = useTranslation();
  const ladderQuery = useQuery({
    queryKey: schemaChangeKeys.ladder(changeSetId),
    queryFn: () => getSchemaChangeLadder(changeSetId),
  });

  if (ladderQuery.isLoading) {
    return <Skeleton.Button active size="small" style={{ width: 120 }} />;
  }
  if (ladderQuery.isError || !ladderQuery.data) {
    return <span className="muted">—</span>;
  }
  if (ladderQuery.data.rungs.length === 0) {
    return <span className="muted">{t('schemaChange.ladder.noEnvironments')}</span>;
  }
  return (
    <div
      role="list"
      aria-label={t('schemaChange.ladder.ariaLabel')}
      style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}
    >
      {ladderQuery.data.rungs.map((rung) => {
        const color = schemaChangeLadderRungColor(rung.state);
        const state = t(`schemaChange.ladder.state.${rung.state}`);
        const reason = ladderRungReason(t, rung);
        const label = `${rung.environment_name}: ${state}`;
        return (
          <Tooltip key={rung.environment_id} title={reason ? `${label} — ${reason}` : label}>
            <span
              role="listitem"
              aria-label={label}
              style={{
                fontSize: 11,
                padding: '1px 6px',
                borderRadius: 4,
                color: color.fg,
                background: color.bg,
                border: `1px solid ${color.border}`,
                whiteSpace: 'nowrap',
              }}
            >
              {rung.environment_name}
            </span>
          </Tooltip>
        );
      })}
    </div>
  );
}
