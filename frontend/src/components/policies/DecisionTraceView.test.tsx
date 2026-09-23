import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import '@/i18n';
import type { DecisionTraceStep, QueryDecisionStepKind } from '@/types/api';
import { DecisionTraceView } from './DecisionTraceView';

const label = (k: string) => `label:${k}`;

const STEPS: DecisionTraceStep<QueryDecisionStepKind>[] = [
  { step: 'DATASOURCE_GATES', outcome: 'ALLOW', reason: 'Datasource is active', details: {} },
  { step: 'QUOTA', outcome: 'SKIP', reason: 'No quota configured for this user', details: {} },
  { step: 'EFFECTIVE_PERMISSION', outcome: 'DENY', reason: 'No write permission', details: { query_admin_short_circuit: false } },
  {
    step: 'ROUTING_POLICIES',
    outcome: 'MATCH',
    reason: 'Policy "Block deletes" matched',
    details: {
      matched_policy_name: 'Block deletes',
      policies: [
        { policy_id: 'p-1', name: 'Block deletes', priority: 0, action: 'AUTO_REJECT', matched: true, decisive: true },
        { policy_id: 'p-2', name: 'Near miss', priority: 1, action: 'REQUIRE_APPROVALS', required_approvals: 2, matched: false, decisive: false },
      ],
    },
  },
  { step: 'MASKING', outcome: 'NO_MATCH', reason: 'No masking policy applies', details: {} },
];

describe('DecisionTraceView', () => {
  it('renders every step, every outcome, and a SKIP with its reason', () => {
    render(
      <DecisionTraceView steps={STEPS} stepLabel={label} resultingStatus="REJECTED" caveats={[]} />,
    );
    for (const s of STEPS) {
      const item = screen.getByTestId(`trace-step-${s.step}`);
      expect(within(item).getByText(`label:${s.step}`)).toBeInTheDocument();
      expect(within(item).getByText(s.reason)).toBeInTheDocument();
    }
    const skip = screen.getByTestId('trace-step-QUOTA');
    expect(skip).toHaveAttribute('data-outcome', 'SKIP');
    expect(within(skip).getByText('Skipped')).toBeInTheDocument();
    expect(screen.getByText('Allow')).toBeInTheDocument();
    expect(screen.getByText('Deny')).toBeInTheDocument();
    expect(screen.getByText('Match')).toBeInTheDocument();
    expect(screen.getByText('No match')).toBeInTheDocument();
    expect(screen.getByTestId('decision-trace-status')).toHaveTextContent('Rejected');
  });

  it('does not drop a step whose details are empty', () => {
    render(<DecisionTraceView steps={[STEPS[0]!]} stepLabel={label} caveats={[]} />);
    const item = screen.getByTestId('trace-step-DATASOURCE_GATES');
    expect(item.querySelector('dl')).toBeNull();
  });

  it('renders the routing policies table with a non-matched, non-decisive row', () => {
    render(<DecisionTraceView steps={STEPS} stepLabel={label} caveats={[]} />);
    const table = screen.getByTestId('routing-policies-trace');
    const nearMiss = within(table).getByText('Near miss').closest('tr') as HTMLElement;
    expect(within(nearMiss).getAllByText('No')).toHaveLength(2);
    expect(within(nearMiss).getByText('Require approvals')).toBeInTheDocument();
    expect(within(nearMiss).getByText('2')).toBeInTheDocument();
    const winner = within(table).getByText('Block deletes').closest('tr') as HTMLElement;
    expect(winner).toHaveClass('af-trace-decisive-row');
    expect(within(winner).getAllByText('Yes')).toHaveLength(2);
  });

  it('shows "not evaluated" when the routing step carries no policy list', () => {
    render(
      <DecisionTraceView
        steps={[{ step: 'ROUTING_POLICIES', outcome: 'SKIP', reason: 'AI failed', details: {} }]}
        stepLabel={label}
        caveats={[]}
      />,
    );
    expect(screen.getByText('Policy list not evaluated on this path')).toBeInTheDocument();
  });

  it('shows an empty-list state when no policy is enabled', () => {
    render(
      <DecisionTraceView
        steps={[{ step: 'ROUTING_POLICIES', outcome: 'NO_MATCH', reason: 'none', details: { policies: [] } }]}
        stepLabel={label}
        caveats={[]}
      />,
    );
    expect(screen.getByText('No enabled routing policies')).toBeInTheDocument();
  });

  it('says the request would be refused when there is no resulting status, and lists caveats and the headline', () => {
    render(
      <DecisionTraceView
        steps={[]}
        stepLabel={label}
        caveats={['CLIENT_CONTEXT_ABSENT']}
        headline={<div>headline here</div>}
      />,
    );
    expect(screen.getByTestId('decision-trace-status')).toHaveTextContent(
      'Refused — no request would be created',
    );
    expect(screen.getByText(/carries no source IP/)).toBeInTheDocument();
    expect(screen.getByText('headline here')).toBeInTheDocument();
  });

  it('renders list-valued details one line each', () => {
    render(
      <DecisionTraceView
        steps={[{ step: 'SQL_PARSE', outcome: 'ALLOW', reason: 'ok', details: { referenced_tables: ['orders', 'customers'] } }]}
        stepLabel={label}
        caveats={[]}
      />,
    );
    expect(screen.getByText('Referenced tables')).toBeInTheDocument();
    expect(screen.getByText('orders')).toBeInTheDocument();
    expect(screen.getByText('customers')).toBeInTheDocument();
  });
});
