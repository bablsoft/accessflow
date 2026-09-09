import { useState } from 'react';
import { Alert, Button, Drawer, Flex, Segmented, Skeleton, Space, Statistic, Table, Tabs } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { apiErrorMessage } from '@/utils/apiErrors';
import { windowForDays } from './policyImpact';
import type { SimulationCaveat } from '@/types/api';

const RANGE_DAYS = [7, 30, 90] as const;
type RangeDays = (typeof RANGE_DAYS)[number];

/** What every simulation renders the same way, whatever the policy kind. */
export interface SimulationSummary {
  evaluatedCount: number;
  changedCount: number;
  truncated: boolean;
  caveats: SimulationCaveat[];
  /** Headline numbers, already labelled by the caller. */
  stats: { key: string; label: string; value: number }[];
  /** The two drill-down tabs; either may be omitted. */
  userColumns: Parameters<typeof Table>[0]['columns'];
  userRows: readonly Record<string, unknown>[];
  detailLabel: string;
  detailColumns: Parameters<typeof Table>[0]['columns'];
  detailRows: readonly Record<string, unknown>[];
}

export interface PolicySimulationDrawerProps<TResult> {
  open: boolean;
  onClose: () => void;
  title: string;
  /** Runs the simulation for a window. Re-created by the caller when the draft changes. */
  run: (window: { from: string; to: string }) => Promise<TResult>;
  /** Reduces a result into the shape the drawer renders. */
  summarize: (result: TResult) => SimulationSummary;
  /** Identity of the draft being simulated; changing it resets the drawer. */
  draftKey: string;
  /** Called once a run succeeds, so the form can drop its "simulate first" nudge. */
  onSimulated?: (draftKey: string) => void;
}

function SimulationBody<TResult>({
  run,
  summarize,
  draftKey,
  onSimulated,
}: Pick<PolicySimulationDrawerProps<TResult>, 'run' | 'summarize' | 'draftKey' | 'onSimulated'>) {
  const { t } = useTranslation();
  const [days, setDays] = useState<RangeDays>(30);

  // A mutation, not a query: a simulation takes a draft and must never run on its own.
  const simulation = useMutation({
    mutationFn: (range: RangeDays) => run(windowForDays(range)),
    onSuccess: () => onSimulated?.(draftKey),
  });

  // Not memoised: `summarize` is an inline arrow at every call site, so a useMemo keyed on it
  // would recompute anyway. Reducing one capped result is cheap.
  const summary = simulation.data ? summarize(simulation.data) : null;

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Segmented<RangeDays>
        value={days}
        onChange={(value) => setDays(value)}
        options={RANGE_DAYS.map((value) => ({
          value,
          label: t('policySimulation.range_days', { count: value }),
        }))}
        aria-label={t('policySimulation.range_label')}
      />
      <Button
        type="primary"
        loading={simulation.isPending}
        onClick={() => simulation.mutate(days)}
      >
        {t('policySimulation.run')}
      </Button>

      {simulation.isPending && <Skeleton active paragraph={{ rows: 6 }} />}

      {/* Never fall through to the empty state on a failure — "nothing would change" is a
          positive claim about governance data, and an error must not be able to make it. */}
      {simulation.isError && (
        <EmptyState
          title={t('policySimulation.error')}
          description={apiErrorMessage(simulation.error, () => t('policySimulation.error'))}
          size="sm"
        />
      )}

      {summary && (
        <>
          {summary.truncated && (
            <Alert
              type="warning"
              showIcon
              title={t('policySimulation.truncated', { count: summary.evaluatedCount })}
            />
          )}
          {summary.caveats.map((caveat) => (
            <Alert
              key={caveat}
              type="info"
              showIcon
              title={t(`policySimulation.caveats.${caveat}`)}
            />
          ))}
          <Flex gap="large" wrap>
            <Statistic
              title={t('policySimulation.evaluated')}
              value={summary.evaluatedCount}
            />
            {summary.stats.map((stat) => (
              <Statistic key={stat.key} title={stat.label} value={stat.value} />
            ))}
          </Flex>
          {summary.changedCount === 0 ? (
            <EmptyState title={t('policySimulation.no_change')} size="sm" />
          ) : (
            <Tabs
              items={[
                {
                  key: 'users',
                  label: t('policySimulation.tab_users'),
                  children: (
                    <Table
                      size="small"
                      rowKey="key"
                      columns={summary.userColumns}
                      dataSource={[...summary.userRows]}
                      pagination={false}
                    />
                  ),
                },
                {
                  key: 'detail',
                  label: summary.detailLabel,
                  children: (
                    <Table
                      size="small"
                      rowKey="key"
                      columns={summary.detailColumns}
                      dataSource={[...summary.detailRows]}
                      pagination={false}
                    />
                  ),
                },
              ]}
            />
          )}
        </>
      )}
    </Space>
  );
}

/**
 * Results drawer for a policy dry run (AF-630). The body is keyed on the draft, so editing the form
 * and reopening starts a clean run instead of showing a stale one — no `useEffect` needed.
 */
export function PolicySimulationDrawer<TResult>({
  open,
  onClose,
  title,
  run,
  summarize,
  draftKey,
  onSimulated,
}: PolicySimulationDrawerProps<TResult>) {
  return (
    <Drawer open={open} onClose={onClose} title={title} size={720} destroyOnHidden>
      {open && (
        <SimulationBody
          key={draftKey}
          run={run}
          summarize={summarize}
          draftKey={draftKey}
          onSimulated={onSimulated}
        />
      )}
    </Drawer>
  );
}
